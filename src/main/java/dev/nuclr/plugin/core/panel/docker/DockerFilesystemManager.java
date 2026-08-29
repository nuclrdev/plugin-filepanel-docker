/*

  Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.

*/
package dev.nuclr.plugin.core.panel.docker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

/**
 * Shared snapshot/index implementation for container and image filesystems.
 *
 * <p>Each browsed container or image is exported once to a temporary tar and kept until the panel
 * is refreshed or the plugin unloads. Two rules keep that from turning into a disk problem: the
 * cache is bounded, evicting whichever snapshot was used least recently, and a snapshot still being
 * read is deleted at exit rather than failing the eviction.
 *
 * <p>Exports are slow — minutes for a large image — so the map lock is held only long enough to look
 * a snapshot up or publish it. The export itself runs under a lock private to that one source, so a
 * long image export never blocks a listing of an unrelated container, nor the plugin's own unload.
 */
@Slf4j
public final class DockerFilesystemManager implements AutoCloseable {

	public static final String SOURCE_CONTAINER = "container";
	public static final String SOURCE_IMAGE = "image";

	/** Prefix of every snapshot file, also used to recognise the leftovers of a crashed run. */
	static final String SNAPSHOT_PREFIX = "nuclr-docker-filesystem-";
	static final String SNAPSHOT_SUFFIX = ".tar";

	/** How many snapshots are kept before the least recently used one is dropped. */
	private static final int MAX_SNAPSHOTS = 4;

	/** How much temporary disk all snapshots together may occupy. */
	private static final long MAX_SNAPSHOT_BYTES = 8L * 1024 * 1024 * 1024;

	/** Orphans older than this are from a previous run and are safe to delete. */
	private static final Duration ORPHAN_AGE = Duration.ofHours(24);

	private record Source(String type, String id) {
	}

	private final DockerService service;

	/** Access-ordered, so the eldest entry is genuinely the least recently used one. */
	private final Map<Source, TarDockerFilesystem> filesystems = new LinkedHashMap<>(8, 0.75f, true);

	private final Map<Source, Object> exportLocks = new ConcurrentHashMap<>();
	private volatile boolean closed;

	public DockerFilesystemManager(DockerService service) {
		this.service = service;
	}

	public List<DockerFileInfo> list(String sourceType, String sourceId, String path,
			BooleanSupplier cancelled) throws DockerException {
		try {
			return filesystem(sourceType, sourceId, cancelled).list(path);
		} catch (DockerException e) {
			throw e;
		} catch (IOException e) {
			throw new DockerException(DockerException.Kind.COMMAND_FAILED, e.getMessage(), e);
		}
	}

	/**
	 * Open a file inside a snapshot.
	 *
	 * @param cancelled polled while a snapshot has to be exported first; never {@code null}
	 */
	public InputStream open(String sourceType, String sourceId, String path, BooleanSupplier cancelled)
			throws IOException {
		try {
			return filesystem(sourceType, sourceId, cancelled).open(path);
		} catch (DockerException e) {
			throw new IOException(e.userMessage(), e);
		}
	}

	/**
	 * The real path an entry names once every link in it is followed.
	 *
	 * @see TarDockerFilesystem#resolve(String)
	 */
	public String resolve(String sourceType, String sourceId, String path, BooleanSupplier cancelled)
			throws DockerException {
		try {
			return filesystem(sourceType, sourceId, cancelled).resolve(path);
		} catch (DockerException e) {
			throw e;
		} catch (IOException e) {
			throw new DockerException(DockerException.Kind.COMMAND_FAILED, e.getMessage(), e);
		}
	}

	private TarDockerFilesystem filesystem(String type, String id, BooleanSupplier cancelled)
			throws DockerException, IOException {

		Source source = new Source(type, id);
		TarDockerFilesystem existing = cached(source);
		if (existing != null) {
			return existing;
		}
		if (!SOURCE_CONTAINER.equals(type) && !SOURCE_IMAGE.equals(type)) {
			throw new IOException("Unsupported Docker filesystem source: " + type);
		}

		// One export at a time per source, so two panels opening the same container share the work
		// instead of exporting it twice — but an export of another source runs alongside it.
		synchronized (exportLocks.computeIfAbsent(source, key -> new Object())) {
			existing = cached(source);
			if (existing != null) {
				return existing;
			}
			return export(source, cancelled);
		}
	}

	private synchronized TarDockerFilesystem cached(Source source) throws IOException {
		if (closed) {
			throw new IOException("The Docker filesystem browser has been closed.");
		}
		return filesystems.get(source);
	}

	private TarDockerFilesystem export(Source source, BooleanSupplier cancelled)
			throws DockerException, IOException {

		Path archive = Files.createTempFile(SNAPSHOT_PREFIX, SNAPSHOT_SUFFIX);
		// A crash between here and close() would otherwise leave a full filesystem image behind.
		archive.toFile().deleteOnExit();
		boolean retained = false;
		try {
			if (SOURCE_CONTAINER.equals(source.type())) {
				service.exportContainer(source.id(), archive, cancelled);
			} else {
				service.exportImage(source.id(), archive, cancelled);
			}
			TarDockerFilesystem created = new TarDockerFilesystem(archive);
			retained = publish(source, created);
			if (!retained) {
				throw new IOException("The Docker filesystem browser has been closed.");
			}
			return created;
		} finally {
			if (!retained) {
				Files.deleteIfExists(archive);
			}
		}
	}

	/** @return {@code false} when the manager closed while the export was running */
	private synchronized boolean publish(Source source, TarDockerFilesystem created) {
		if (closed) {
			return false;
		}
		filesystems.put(source, created);
		evictWhileOverBudget(source);
		return true;
	}

	private void evictWhileOverBudget(Source keep) {
		long bytes = filesystems.values().stream().mapToLong(TarDockerFilesystem::archiveBytes).sum();
		Iterator<Map.Entry<Source, TarDockerFilesystem>> eldestFirst = filesystems.entrySet().iterator();
		while (eldestFirst.hasNext() && (filesystems.size() > MAX_SNAPSHOTS || bytes > MAX_SNAPSHOT_BYTES)) {
			Map.Entry<Source, TarDockerFilesystem> candidate = eldestFirst.next();
			if (candidate.getKey().equals(keep)) {
				continue; // Never evict the snapshot this call just built.
			}
			log.debug("Dropping the least recently used Docker filesystem snapshot for {} {}",
					candidate.getKey().type(), DockerNames.shortId(candidate.getKey().id()));
			bytes -= candidate.getValue().archiveBytes();
			candidate.getValue().discard();
			eldestFirst.remove();
		}
	}

	public void invalidate(String type, String id) {
		TarDockerFilesystem filesystem;
		synchronized (this) {
			filesystem = filesystems.remove(new Source(type, id));
		}
		if (filesystem != null) {
			filesystem.discard();
		}
	}

	/**
	 * Delete snapshots left behind by a run that did not shut down cleanly.
	 *
	 * <p>Only files older than a day are touched: a younger one may belong to another Commander
	 * window that is using it right now.
	 */
	static void sweepOrphanedSnapshots() {
		Path directory = Path.of(System.getProperty("java.io.tmpdir", "."));
		Instant cutoff = Instant.now().minus(ORPHAN_AGE);
		try (Stream<Path> files = Files.list(directory)) {
			for (Path file : files.toList()) {
				String name = file.getFileName().toString();
				if (!name.startsWith(SNAPSHOT_PREFIX) || !name.endsWith(SNAPSHOT_SUFFIX)) {
					continue;
				}
				try {
					if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)
							&& Files.deleteIfExists(file)) {
						log.info("Removed the stale Docker filesystem snapshot {}", name);
					}
				} catch (IOException e) {
					log.debug("Could not remove the stale Docker snapshot {}: {}", name, e.getMessage());
				}
			}
		} catch (IOException e) {
			log.debug("Could not scan {} for stale Docker snapshots: {}", directory, e.getMessage());
		}
	}

	@Override
	public void close() {
		List<TarDockerFilesystem> pending;
		synchronized (this) {
			closed = true;
			pending = new ArrayList<>(filesystems.values());
			filesystems.clear();
		}
		pending.forEach(TarDockerFilesystem::discard);
		exportLocks.clear();
	}
}
