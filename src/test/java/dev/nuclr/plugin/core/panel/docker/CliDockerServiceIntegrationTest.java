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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import dev.nuclr.platform.plugin.NuclrResource;

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

	/**
	 * Stop then start a real pair of containers through the panel's bulk path.
	 *
	 * <p>The in-memory suite proves the panel calls Docker once per selected container; only this
	 * proves the calls the panel makes are ones the real Docker CLI accepts.
	 */
	@Test
	void stopsAndStartsEverySelectedContainer() throws Exception {
		String image = "busybox:latest";
		try (var service = new CliDockerService()) {
			pull(service, image);
			var plugin = new DockerFilePanelPlugin(service);
			var ids = new ArrayList<String>();
			try {
				for (int index = 0; index < 2; index++) {
					ids.add(service.runContainer(new RunContainerRequest(image,
							"nuclr-itest-" + UUID.randomUUID(), List.of("sleep", "120"),
							null, null, null), () -> false));
				}
				List<NuclrResource> targets = ids.stream().map(id -> (NuclrResource) DockerResource.container(
						new DockerContainer(id, id, image, "running", "", "", "", ""), null)).toList();

				assertEquals(List.of(), plugin.applyToAll("Stop", service::stopContainer,
						targets, () -> false, null));
				for (String id : ids) {
					assertEquals("exited", state(service, id));
				}

				assertEquals(List.of(), plugin.applyToAll("Start", service::startContainer,
						targets, () -> false, null));
				for (String id : ids) {
					assertEquals("running", state(service, id));
				}
			} finally {
				for (String id : ids) {
					try {
						service.stopContainer(id, () -> false);
					} catch (DockerException ignored) {
						// Best effort: the removal below is what actually matters.
					}
					service.removeContainer(id, () -> false);
				}
				plugin.unload();
			}
		}
	}

	private static void pull(CliDockerService service, String image) throws Exception {
		boolean present = service.listImages(() -> false).stream()
				.anyMatch(candidate -> image.equals(candidate.repository() + ':' + candidate.tag()));
		if (!present) {
			new ProcessBuilder("docker", "pull", image).inheritIO().start().waitFor();
		}
	}

	private static String state(CliDockerService service, String id) throws DockerException {
		return service.listContainers(() -> false).stream()
				.filter(container -> container.id().startsWith(id) || id.startsWith(container.id()))
				.map(container -> container.state().toLowerCase(Locale.ROOT))
				.findFirst().orElse("missing");
	}
}
