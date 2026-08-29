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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;

class DockerFilesystemManagerTest {

	/** Writes a one-file tar wherever the manager asks for a snapshot. */
	private static class ExportingService extends FakeDockerService {

		final AtomicInteger exports = new AtomicInteger();

		ExportingService() {
			super(Set.of());
		}

		@Override
		public void exportContainer(String containerId, Path destination, BooleanSupplier cancelled) {
			exports.incrementAndGet();
			write(destination, containerId);
		}

		@Override
		public void exportImage(String imageId, Path destination, BooleanSupplier cancelled) {
			exports.incrementAndGet();
			write(destination, imageId);
		}

		static void write(Path destination, String marker) {
			try (TarArchiveOutputStream tar = new TarArchiveOutputStream(
					Files.newOutputStream(destination))) {
				byte[] bytes = marker.getBytes(StandardCharsets.UTF_8);
				TarArchiveEntry entry = new TarArchiveEntry("marker");
				entry.setSize(bytes.length);
				tar.putArchiveEntry(entry);
				tar.write(bytes);
				tar.closeArchiveEntry();
			} catch (IOException e) {
				throw new UncheckedIOExceptionWrapper(e);
			}
		}
	}

	private static final class UncheckedIOExceptionWrapper extends RuntimeException {
		private static final long serialVersionUID = 1L;

		UncheckedIOExceptionWrapper(IOException cause) {
			super(cause);
		}
	}

	@Test
	void aSourceIsExportedOnceAndThenServedFromTheSnapshot() throws Exception {
		var service = new ExportingService();
		try (var manager = new DockerFilesystemManager(service)) {
			for (int i = 0; i < 5; i++) {
				assertEquals(List.of("marker"), manager
						.list(DockerFilesystemManager.SOURCE_CONTAINER, "abc", "/", () -> false)
						.stream().map(DockerFileInfo::name).toList());
			}
			assertEquals(1, service.exports.get());
		}
	}

	@Test
	void exportingOneSourceDoesNotBlockAnother() throws Exception {
		// A fifteen-minute image export must not hold up a listing of an unrelated container,
		// nor the plugin's own unload.
		var started = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var service = new ExportingService() {
			@Override
			public void exportImage(String imageId, Path destination, BooleanSupplier cancelled) {
				started.countDown();
				try {
					assertTrue(release.await(10, TimeUnit.SECONDS));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				super.exportImage(imageId, destination, cancelled);
			}
		};

		try (var manager = new DockerFilesystemManager(service)) {
			Thread slow = Thread.ofVirtual().start(() -> {
				try {
					manager.list(DockerFilesystemManager.SOURCE_IMAGE, "big", "/", () -> false);
				} catch (DockerException e) {
					throw new IllegalStateException(e);
				}
			});
			assertTrue(started.await(10, TimeUnit.SECONDS));

			// The slow export is in flight; this one must not wait behind it.
			assertEquals(1, manager
					.list(DockerFilesystemManager.SOURCE_CONTAINER, "small", "/", () -> false).size());

			release.countDown();
			slow.join();
		}
	}

	@Test
	void snapshotsAreBoundedAndTheEvictedOnesLeaveNoFilesBehind() throws Exception {
		var service = new ExportingService();
		Path temporary = Path.of(System.getProperty("java.io.tmpdir"));
		long before = snapshotFiles(temporary);

		try (var manager = new DockerFilesystemManager(service)) {
			for (int i = 0; i < 10; i++) {
				manager.list(DockerFilesystemManager.SOURCE_CONTAINER, "container-" + i, "/", () -> false);
			}
			assertEquals(10, service.exports.get());
			// Four are kept, so the six evicted ones are gone from disk already.
			assertTrue(snapshotFiles(temporary) - before <= 4,
					"evicted snapshots should not be left in the temporary directory");

			// An evicted source is exported again on the next visit rather than failing.
			manager.list(DockerFilesystemManager.SOURCE_CONTAINER, "container-0", "/", () -> false);
			assertEquals(11, service.exports.get());
		}

		assertEquals(before, snapshotFiles(temporary), "close() should remove every snapshot");
	}

	@Test
	void invalidateForcesTheNextVisitToExportAgain() throws Exception {
		var service = new ExportingService();
		try (var manager = new DockerFilesystemManager(service)) {
			manager.list(DockerFilesystemManager.SOURCE_CONTAINER, "abc", "/", () -> false);
			manager.invalidate(DockerFilesystemManager.SOURCE_CONTAINER, "abc");
			manager.list(DockerFilesystemManager.SOURCE_CONTAINER, "abc", "/", () -> false);
			assertEquals(2, service.exports.get());
		}
	}

	@Test
	void anUnknownSourceKindIsRejectedWithoutTouchingDocker() {
		var service = new ExportingService();
		try (var manager = new DockerFilesystemManager(service)) {
			assertThrows(DockerException.class,
					() -> manager.list("network", "abc", "/", () -> false));
			assertEquals(0, service.exports.get());
		}
	}

	@Test
	void useAfterCloseFailsInsteadOfExportingIntoNothing() {
		var manager = new DockerFilesystemManager(new ExportingService());
		manager.close();
		assertThrows(DockerException.class,
				() -> manager.list(DockerFilesystemManager.SOURCE_CONTAINER, "abc", "/", () -> false));
	}

	private static long snapshotFiles(Path directory) throws IOException {
		try (var files = Files.list(directory)) {
			return files.filter(file -> file.getFileName().toString()
					.startsWith(DockerFilesystemManager.SNAPSHOT_PREFIX)).count();
		}
	}
}
