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

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TarDockerFilesystemTest {

	@TempDir
	Path temp;

	@Test
	void listsReadsAndFollowsDirectorySymlinks() throws Exception {
		Path archive = temp.resolve("filesystem.tar");
		try (TarArchiveOutputStream tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
			directory(tar, "etc/", 0755);
			file(tar, "etc/app.conf", "enabled=true", 0644);
			directory(tar, "usr/", 0755);
			directory(tar, "usr/bin/", 0755);
			file(tar, "usr/bin/tool", "binary", 0755);
			TarArchiveEntry link = new TarArchiveEntry("bin", TarArchiveEntry.LF_SYMLINK);
			link.setLinkName("usr/bin");
			link.setMode(0777);
			tar.putArchiveEntry(link);
			tar.closeArchiveEntry();
		}

		TarDockerFilesystem filesystem = new TarDockerFilesystem(archive);
		DockerFileInfo bin = filesystem.list("/").stream().filter(entry -> entry.name().equals("bin")).findFirst().orElseThrow();
		assertTrue(bin.folder());
		assertTrue(bin.link());
		DockerFileInfo tool = filesystem.list("/bin").getFirst();
		assertEquals("/bin/tool", tool.path());
		try (var input = filesystem.open(tool.path())) {
			assertEquals("binary", new String(input.readAllBytes(), StandardCharsets.UTF_8));
		}
		filesystem.close();
		assertFalse(Files.exists(archive));
	}

	@Test
	void aDirectoryLinkingToItselfResolvesToItsOwnParentInsteadOfLooping() throws Exception {
		// /usr/bin/X11 -> . ships in the Debian base image. A tree walk that trusted the alias
		// path would descend into X11/X11/X11/... until it ran out of depth.
		Path archive = temp.resolve("selflink.tar");
		try (TarArchiveOutputStream tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
			directory(tar, "usr/", 0755);
			directory(tar, "usr/bin/", 0755);
			file(tar, "usr/bin/tool", "binary", 0755);
			symlink(tar, "usr/bin/X11", ".");
		}

		try (TarDockerFilesystem filesystem = new TarDockerFilesystem(archive)) {
			assertEquals("/usr/bin", filesystem.resolve("/usr/bin/X11"));
			assertEquals("/usr/bin", filesystem.resolve("/usr/bin/X11/X11/X11"));

			DockerFileInfo x11 = filesystem.list("/usr/bin").stream()
					.filter(entry -> entry.name().equals("X11")).findFirst().orElseThrow();
			assertTrue(x11.folder());
			assertTrue(x11.link());
			// The alias still lists, so the panel can show it; only recursion has to stop.
			assertEquals(List.of("X11", "tool"),
					filesystem.list("/usr/bin/X11").stream().map(DockerFileInfo::name).toList());
		}
	}

	@Test
	void mutuallyLinkedDirectoriesFailInsteadOfSpinning() throws Exception {
		Path archive = temp.resolve("cycle.tar");
		try (TarArchiveOutputStream tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
			symlink(tar, "a", "/b");
			symlink(tar, "b", "/a");
		}

		try (TarDockerFilesystem filesystem = new TarDockerFilesystem(archive)) {
			assertThrows(IOException.class, () -> filesystem.resolve("/a"));
		}
	}

	@Test
	void filesAreReadFromTheirRecordedOffsetInAnyOrder() throws Exception {
		// The read path seeks rather than rescanning, so reading backwards must work too.
		Path archive = temp.resolve("many.tar");
		try (TarArchiveOutputStream tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
			directory(tar, "data/", 0755);
			for (int index = 0; index < 25; index++) {
				file(tar, "data/file-" + index, "content-" + index, 0644);
			}
		}

		try (TarDockerFilesystem filesystem = new TarDockerFilesystem(archive)) {
			assertEquals(25, filesystem.list("/data").size());
			for (int index = 24; index >= 0; index--) {
				try (var input = filesystem.open("/data/file-" + index)) {
					assertEquals("content-" + index,
							new String(input.readAllBytes(), StandardCharsets.UTF_8));
				}
			}
		}
	}

	@Test
	void readsStopAtTheEndOfTheirOwnEntry() throws Exception {
		Path archive = temp.resolve("bounds.tar");
		try (TarArchiveOutputStream tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
			file(tar, "first", "AAA", 0644);
			file(tar, "second", "BBBBBBBB", 0644);
		}

		try (TarDockerFilesystem filesystem = new TarDockerFilesystem(archive)) {
			try (var input = filesystem.open("/first")) {
				assertArrayEquals("AAA".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
				assertEquals(-1, input.read());
			}
		}
	}

	@Test
	void archiveBytesReportsWhatTheSnapshotCostsOnDisk() throws Exception {
		Path archive = temp.resolve("sized.tar");
		try (TarArchiveOutputStream tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
			file(tar, "payload", "x".repeat(4096), 0644);
		}
		try (TarDockerFilesystem filesystem = new TarDockerFilesystem(archive)) {
			assertEquals(Files.size(archive), filesystem.archiveBytes());
		}
	}

	private static void symlink(TarArchiveOutputStream tar, String name, String target) throws Exception {
		TarArchiveEntry entry = new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK);
		entry.setLinkName(target);
		entry.setMode(0777);
		tar.putArchiveEntry(entry);
		tar.closeArchiveEntry();
	}

	private static void directory(TarArchiveOutputStream tar, String name, int mode) throws Exception {
		TarArchiveEntry entry = new TarArchiveEntry(name);
		entry.setMode(mode);
		tar.putArchiveEntry(entry);
		tar.closeArchiveEntry();
	}

	private static void file(TarArchiveOutputStream tar, String name, String value, int mode) throws Exception {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		TarArchiveEntry entry = new TarArchiveEntry(name);
		entry.setSize(bytes.length);
		entry.setMode(mode);
		tar.putArchiveEntry(entry);
		tar.write(bytes);
		tar.closeArchiveEntry();
	}
}
