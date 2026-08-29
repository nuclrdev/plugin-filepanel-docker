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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in because the normal unit suite must not require Docker or a running daemon. */
@EnabledIfEnvironmentVariable(named = "NUCLR_DOCKER_TESTS", matches = "(?i)true")
class CliDockerServiceIntegrationTest {

	@Test
	void listsCoreLocalResources() throws Exception {
		try (var service = new CliDockerService()) {
			var containers = service.listContainers(() -> false);
			var images = service.listImages(() -> false);
			var volumes = service.listVolumes(() -> false);
			assertNotNull(containers);
			assertNotNull(images);
			assertNotNull(volumes);
			if (!containers.isEmpty()) {
				assertTrue(service.inspectContainer(containers.getFirst().id(), () -> false).startsWith("["));
			}
			if (!images.isEmpty()) {
				assertTrue(service.inspectImage(images.getFirst().id(), () -> false).startsWith("["));
			}
			if (!volumes.isEmpty()) {
				assertTrue(service.inspectVolume(volumes.getFirst().name(), () -> false).startsWith("["));
			}
		}
	}
}
