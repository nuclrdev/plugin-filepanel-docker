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

import java.util.List;

import org.junit.jupiter.api.Test;

class DockerCommandLineTest {

	@Test
	void blankInputProducesNoArguments() {
		assertEquals(List.of(), DockerCommandLine.parse(null));
		assertEquals(List.of(), DockerCommandLine.parse("   "));
	}

	@Test
	void splitsOnWhitespaceAndHonoursBothQuoteStyles() {
		assertEquals(List.of("sh", "-c", "echo hello world"),
				DockerCommandLine.parse("sh -c \"echo hello world\""));
		assertEquals(List.of("sh", "-c", "echo \"quoted\""),
				DockerCommandLine.parse("sh -c 'echo \"quoted\"'"));
		assertEquals(List.of("a", "b"), DockerCommandLine.parse("  a \t b  "));
	}

	@Test
	void keepsBackslashesThatAreNotEscapingAnything() {
		// Container and Windows paths are full of them and must survive verbatim.
		assertEquals(List.of("C:\\data\\input.txt"), DockerCommandLine.parse("C:\\data\\input.txt"));
		assertEquals(List.of("a b"), DockerCommandLine.parse("a\\ b"));
		assertEquals(List.of("a\"b"), DockerCommandLine.parse("a\\\"b"));
	}

	@Test
	void singleQuotesSuppressEscapesTheWayAShellWould() {
		assertEquals(List.of("a\\b"), DockerCommandLine.parse("'a\\b'"));
	}

	@Test
	void quotedArgumentsSurviveWithTheirSpacesAndSeparators() {
		assertEquals(List.of("java", "-Dmessage=hello world", "-jar", "app.jar"),
				DockerCommandLine.parse("java \"-Dmessage=hello world\" -jar app.jar"));
		assertEquals(List.of("C:\\Program Files\\App\\server.exe", "--serve"),
				DockerCommandLine.parse("\"C:\\Program Files\\App\\server.exe\" --serve"));
	}

	@Test
	void unfinishedQuoteIsRejectedRatherThanGuessed() {
		var error = assertThrows(IllegalArgumentException.class,
				() -> DockerCommandLine.parse("sh -c \"echo unterminated"));
		assertTrue(error.getMessage().contains("unfinished"));
	}
}
