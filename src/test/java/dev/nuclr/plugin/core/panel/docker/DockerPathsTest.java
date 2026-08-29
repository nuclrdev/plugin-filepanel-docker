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

class DockerPathsTest {

	@Test
	void normalizesAndNavigatesPosixPaths() {
		assertEquals("/etc/nginx", DockerPaths.normalize("./etc//nginx/"));
		assertEquals("/etc", DockerPaths.parent("/etc/nginx"));
		assertEquals("nginx", DockerPaths.name("/etc/nginx"));
		assertEquals("/etc/nginx/conf.d", DockerPaths.join("/etc/nginx", "./conf.d"));
		assertEquals("/etc", DockerPaths.normalize("/etc/nginx/.."));
	}
}
