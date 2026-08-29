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
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import lombok.extern.slf4j.Slf4j;

/**
 * Read-only indexed view of a merged Docker filesystem tar snapshot.
 *
 * <p>The archive is scanned exactly once. That pass records, for every entry, where its data begins
 * in the file, so opening a file later is a seek rather than another walk from byte zero: extracting
 * a directory of a thousand files used to re-read the whole snapshot a thousand times. The same pass
 * builds the parent-to-children index, so listing a directory costs its own size instead of the size
 * of the entire image.
 */
@Slf4j
final class TarDockerFilesystem implements AutoCloseable {

	/** Data offset used for entries that must still be read by scanning, such as sparse files. */
	private static final long NO_OFFSET = -1;

	/** Bound on link hops, so a snapshot with a link cycle fails instead of spinning. */
	private static final int MAX_LINK_HOPS = 40;

	private record Indexed(String path, boolean folder, boolean symbolicLink, boolean hardLink,
			long size, Instant modified, int mode, String linkTarget, long dataOffset) {

		boolean link() {
			return symbolicLink || hardLink;
		}
	}

	private final Path archive;
	private final Map<String, Indexed> entries = new HashMap<>();
	private final Map<String, List<Indexed>> children = new HashMap<>();
	private final long archiveBytes;

	TarDockerFilesystem(Path archive) throws IOException {
		this.archive = archive;
		index();
		this.archiveBytes = Files.exists(archive) ? Files.size(archive) : 0;
	}

	/** Bytes this snapshot occupies in the temporary directory, for the manager's cache budget. */
	long archiveBytes() {
		return archiveBytes;
	}

	private void index() throws IOException {
		entries.put("/", directoryAt("/"));
		try (TarArchiveInputStream tar = tarInput()) {
			TarArchiveEntry entry;
			while ((entry = tar.getNextEntry()) != null) {
				String path = DockerPaths.normalize(entry.getName());
				if ("/".equals(path) && !entry.isDirectory()) {
					continue;
				}
				Instant modified = entry.getModTime() == null ? null : entry.getModTime().toInstant();
				// A sparse entry's bytes are not a contiguous run in the archive, so it keeps the
				// scanning path; everything else can be read straight from its recorded offset.
				long offset = entry.isSparse() ? NO_OFFSET : entry.getDataOffset();
				entries.put(path, new Indexed(path, entry.isDirectory(), entry.isSymbolicLink(), entry.isLink(),
						Math.max(0, entry.getSize()), modified, entry.getMode(), entry.getLinkName(), offset));
				addImpliedParents(path);
			}
		}
		indexChildren();
	}

	private void addImpliedParents(String path) {
		String parent = DockerPaths.parent(path);
		while (!entries.containsKey(parent)) {
			entries.put(parent, directoryAt(parent));
			if ("/".equals(parent)) {
				break;
			}
			parent = DockerPaths.parent(parent);
		}
	}

	private void indexChildren() {
		for (Indexed indexed : entries.values()) {
			if ("/".equals(indexed.path())) {
				continue;
			}
			children.computeIfAbsent(DockerPaths.parent(indexed.path()), key -> new ArrayList<>()).add(indexed);
		}
	}

	private static Indexed directoryAt(String path) {
		return new Indexed(path, true, false, false, 0, null, 0755, null, NO_OFFSET);
	}

	List<DockerFileInfo> list(String requestedPath) throws IOException {
		String requested = DockerPaths.normalize(requestedPath);
		String resolved = resolve(requested);
		Indexed directory = entries.get(resolved);
		if (directory == null) {
			throw new IOException("Path no longer exists in the Docker filesystem snapshot: " + requested);
		}
		if (!directory.folder()) {
			throw new IOException("Not a directory in the Docker filesystem: " + requested);
		}

		var result = new ArrayList<DockerFileInfo>();
		for (Indexed indexed : children.getOrDefault(resolved, List.of())) {
			String name = DockerPaths.name(indexed.path());
			String aliasPath = DockerPaths.join(requested, name);
			Indexed effective = effective(indexed);
			result.add(new DockerFileInfo(aliasPath, name, effective.folder(), indexed.link(),
					effective.size(), effective.modified(), effective.mode()));
		}
		result.sort(Comparator.comparing(DockerFileInfo::folder).reversed()
				.thenComparing(DockerFileInfo::name, String.CASE_INSENSITIVE_ORDER));
		return List.copyOf(result);
	}

	InputStream open(String requestedPath) throws IOException {
		String resolved = resolve(DockerPaths.normalize(requestedPath));
		Indexed indexed = entries.get(resolved);
		if (indexed == null || indexed.folder()) {
			throw new IOException("Not a readable Docker file: " + requestedPath);
		}
		if (indexed.dataOffset() >= 0) {
			return openAt(indexed);
		}
		return scanFor(resolved, requestedPath);
	}

	/**
	 * The real location a path names, following every link component.
	 *
	 * <p>Callers that descend a tree use this to recognise a directory that leads back to a place
	 * they are already inside. Images really do ship such links — {@code /usr/bin/X11 -> .} is in
	 * the Debian base — and a walk that trusted the alias path would copy the same directory over
	 * and over until it hit a depth limit.
	 *
	 * @param path the path as the panel shows it
	 * @return the resolved path, or the input when nothing links
	 * @throws IOException if the snapshot's links form a cycle
	 */
	String resolve(String path) throws IOException {
		String current = DockerPaths.normalize(path);
		for (int hop = 0; hop < MAX_LINK_HOPS; hop++) {
			String linkPath = firstLinkComponent(current);
			if (linkPath == null) {
				return current;
			}
			Indexed link = entries.get(linkPath);
			String target = link.linkTarget();
			if (target == null || target.isBlank()) {
				return current;
			}
			String resolvedTarget = target.startsWith("/")
					? DockerPaths.normalize(target)
					: DockerPaths.join(link.hardLink() ? "/" : DockerPaths.parent(linkPath), target);
			String remainder = current.substring(linkPath.length());
			String next = DockerPaths.normalize(resolvedTarget + remainder);
			if (next.equals(current)) {
				return current; // A link onto itself; treat it as its own destination.
			}
			current = next;
		}
		throw new IOException("Too many symbolic links while resolving " + path);
	}

	private InputStream openAt(Indexed indexed) throws IOException {
		SeekableByteChannel channel = Files.newByteChannel(archive, StandardOpenOption.READ);
		try {
			channel.position(indexed.dataOffset());
			return new BoundedInputStream(Channels.newInputStream(channel), indexed.size());
		} catch (IOException | RuntimeException e) {
			channel.close();
			throw e;
		}
	}

	private InputStream scanFor(String resolved, String requestedPath) throws IOException {
		TarArchiveInputStream tar = tarInput();
		try {
			TarArchiveEntry entry;
			while ((entry = tar.getNextEntry()) != null) {
				if (DockerPaths.normalize(entry.getName()).equals(resolved)) {
					return tar;
				}
			}
		} catch (Exception e) {
			tar.close();
			throw e;
		}
		tar.close();
		throw new IOException("Docker file was not found in its snapshot: " + requestedPath);
	}

	private Indexed effective(Indexed indexed) throws IOException {
		if (!indexed.link()) {
			return indexed;
		}
		Indexed target = entries.get(resolve(indexed.path()));
		return target == null ? indexed : target;
	}

	private String firstLinkComponent(String path) {
		if ("/".equals(path)) {
			return null;
		}
		String[] segments = path.substring(1).split("/");
		var candidate = new StringBuilder();
		for (String segment : segments) {
			candidate.append('/').append(segment);
			Indexed indexed = entries.get(candidate.toString());
			if (indexed != null && indexed.link()) {
				return candidate.toString();
			}
		}
		return null;
	}

	private TarArchiveInputStream tarInput() throws IOException {
		return new TarArchiveInputStream(Files.newInputStream(archive));
	}

	@Override
	public void close() throws IOException {
		if (Files.deleteIfExists(archive)) {
			return;
		}
		log.debug("Docker filesystem snapshot {} was already gone", archive);
	}

	/**
	 * Deletes the snapshot, falling back to a shutdown-time delete when the file is still open.
	 *
	 * <p>Windows refuses to unlink a file a reader still holds, and eviction must not fail merely
	 * because someone is viewing a file from the snapshot being retired.
	 */
	void discard() {
		try {
			close();
		} catch (IOException e) {
			log.debug("Docker filesystem snapshot {} is still in use; deleting it at exit", archive);
			archive.toFile().deleteOnExit();
		}
	}

	/** Caps a shared channel at one entry's length, and closes the channel with the stream. */
	private static final class BoundedInputStream extends InputStream {

		private final InputStream delegate;
		private long remaining;

		BoundedInputStream(InputStream delegate, long limit) {
			this.delegate = delegate;
			this.remaining = limit;
		}

		@Override
		public int read() throws IOException {
			if (remaining <= 0) {
				return -1;
			}
			int value = delegate.read();
			if (value >= 0) {
				remaining--;
			}
			return value;
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			if (remaining <= 0) {
				return -1;
			}
			int read = delegate.read(bytes, offset, (int) Math.min(length, remaining));
			if (read > 0) {
				remaining -= read;
			}
			return read;
		}

		@Override
		public int available() throws IOException {
			return (int) Math.min(delegate.available(), remaining);
		}

		@Override
		public void close() throws IOException {
			delegate.close();
		}
	}
}
