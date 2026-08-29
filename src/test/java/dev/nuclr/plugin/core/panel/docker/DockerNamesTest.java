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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DockerNamesTest {

	@Test
	void formatsTaggedAndDanglingImages() {
		assertEquals("nginx:latest", DockerNames.imageDisplayName("nginx", "latest", "sha256:1234567890abcdef"));
		assertEquals("<dangling> (1234567890ab)",
				DockerNames.imageDisplayName("<none>", "<none>", "sha256:1234567890abcdef"));
	}

	@Test
	void shortIdRemovesAlgorithmPrefix() {
		assertEquals("abcdef012345", DockerNames.shortId("sha256:abcdef0123456789"));
	}
}
