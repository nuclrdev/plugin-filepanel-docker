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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.plugin.NuclrResource;

class DockerCopyServiceTest {

	@TempDir
	Path temp;

	/**
	 * A snapshot shaped like a real base image: a directory that links to itself. Debian ships
	 * {@code /usr/bin/X11 -> .}, and following the alias would repeat {@code /usr/bin} forever.
	 */
	private static final class SelfLinkingService extends FakeDockerService {

		SelfLinkingService() {
			super(Set.of());
		}

		@Override
		public void exportImage(String imageId, Path destination, BooleanSupplier cancelled) {
			try (TarArchiveOutputStream tar = new TarArchiveOutputStream(
					Files.newOutputStream(destination))) {
				directory(tar, "usr/");
				directory(tar, "usr/bin/");
				file(tar, "usr/bin/tool", "binary");
				file(tar, "usr/bin/other", "second");
				symlink(tar, "usr/bin/X11", ".");
				directory(tar, "usr/share/");
				file(tar, "usr/share/notes.txt", "hello");
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}

		private static void directory(TarArchiveOutputStream tar, String name) throws IOException {
			TarArchiveEntry entry = new TarArchiveEntry(name);
			entry.setMode(0755);
			tar.putArchiveEntry(entry);
			tar.closeArchiveEntry();
		}

		private static void file(TarArchiveOutputStream tar, String name, String value) throws IOException {
			byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
			TarArchiveEntry entry = new TarArchiveEntry(name);
			entry.setSize(bytes.length);
			entry.setMode(0644);
			tar.putArchiveEntry(entry);
			tar.write(bytes);
			tar.closeArchiveEntry();
		}

		private static void symlink(TarArchiveOutputStream tar, String name, String target)
				throws IOException {
			TarArchiveEntry entry = new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK);
			entry.setLinkName(target);
			entry.setMode(0777);
			tar.putArchiveEntry(entry);
			tar.closeArchiveEntry();
		}
	}

	private static NuclrResource directory(DockerFilesystemManager filesystems, String path) {
		return DockerResource.filesystemDirectory(DockerFilesystemManager.SOURCE_IMAGE, "img",
				"debian:latest", path, filesystems);
	}

	@Test
	void aDirectoryThatLinksToItselfIsCopiedOnceInsteadOfSixtyFourTimes() throws Exception {
		try (var filesystems = new DockerFilesystemManager(new SelfLinkingService())) {
			var copyService = new DockerCopyService(filesystems);
			Path destination = Files.createDirectory(temp.resolve("out"));

			String failure = copyService.transfer(
					new DockerCopyService.Plan(List.of(directory(filesystems, "/usr")), destination, "usr"),
					null);

			assertNull(failure);
			assertEquals("binary", Files.readString(destination.resolve("usr/bin/tool")));
			assertEquals("hello", Files.readString(destination.resolve("usr/share/notes.txt")));

			// The alias itself is created, but nothing is copied through it a second time.
			Path x11 = destination.resolve("usr/bin/X11");
			assertTrue(Files.isDirectory(x11));
			assertEquals(List.of(), children(x11));

			// usr, usr/bin, bin/tool, bin/other, bin/X11, usr/share, share/notes.txt — and no more.
			assertEquals(7, count(destination), "the tree should be copied exactly once");
		}
	}

	@Test
	void copiedFilesKeepTheirContentAndTheirTimestamps() throws Exception {
		try (var filesystems = new DockerFilesystemManager(new SelfLinkingService())) {
			var copyService = new DockerCopyService(filesystems);
			Path destination = Files.createDirectory(temp.resolve("stamped"));

			assertNull(copyService.transfer(new DockerCopyService.Plan(
					List.of(directory(filesystems, "/usr/share")), destination, "share"), null));

			Path copied = destination.resolve("share/notes.txt");
			assertEquals("hello", Files.readString(copied));
			DockerFileInfo source = filesystems
					.list(DockerFilesystemManager.SOURCE_IMAGE, "img", "/usr/share", () -> false)
					.getFirst();
			assertEquals(source.modified().getEpochSecond(),
					Files.getLastModifiedTime(copied).toInstant().getEpochSecond());
		}
	}

	@Test
	void aWalkOverTheSameSnapshotAlsoTerminates() throws Exception {
		var service = new SelfLinkingService();
		var plugin = new DockerFilePanelPlugin(service);
		try (var filesystems = new DockerFilesystemManager(service)) {
			var visited = new ArrayList<String>();
			plugin.walkDescendants(directory(filesystems, "/usr"), entry -> visited.add(entry.getName()),
					new AtomicBoolean(false), true);
			// bin, X11, other, tool, share, notes.txt — each seen once.
			assertEquals(6, visited.size(), "visited " + visited);
		} finally {
			plugin.unload();
		}
	}

	private static List<String> children(Path directory) throws IOException {
		try (Stream<Path> files = Files.list(directory)) {
			return files.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	private static long count(Path root) throws IOException {
		try (Stream<Path> files = Files.walk(root)) {
			return files.filter(path -> !path.equals(root)).count();
		}
	}
}
