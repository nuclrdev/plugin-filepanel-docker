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
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.nuclr.platform.plugin.NuclrResource;

class DockerFilePanelPluginTest {

	private static DockerResource container(String id, String name, String state) {
		return DockerResource.container(
				new DockerContainer(id, name, "nginx:latest", state, "", "", "", ""), null);
	}

	private static DockerResource volume(String name) {
		return DockerResource.volume(new DockerVolume(name, "local", "local", "/var/lib/x", ""));
	}

	@Test
	void oneRefusedDeletionDoesNotAbandonTheRestOfTheSelection() {
		var service = new FakeDockerService(Set.of("busy"));
		var plugin = new DockerFilePanelPlugin(service);

		List<NuclrResource> targets = List.of(
				container("first", "first", "exited"),
				container("busy", "busy", "running"),
				container("third", "third", "exited"),
				volume("scratch"));

		List<String> failures = plugin.deleteAll(targets, () -> false, null);

		assertEquals(List.of("rm-container:first", "rm-container:busy", "rm-container:third",
				"rm-volume:scratch"), service.calls);
		assertEquals(1, failures.size());
		assertTrue(failures.getFirst().startsWith("busy"));
		assertTrue(failures.getFirst().contains("in use"));
		plugin.unload();
	}

	@Test
	void deletionStopsWhenTheUserCancels() {
		var service = new FakeDockerService(Set.of());
		var plugin = new DockerFilePanelPlugin(service);

		List<NuclrResource> targets = List.of(container("a", "a", "exited"), container("b", "b", "exited"));
		boolean[] cancelAfterFirst = { false };

		List<String> failures = plugin.deleteAll(targets, () -> {
			boolean cancelled = cancelAfterFirst[0];
			cancelAfterFirst[0] = true;
			return cancelled;
		}, null);

		assertEquals(List.of("rm-container:a"), service.calls);
		assertTrue(failures.isEmpty());
		plugin.unload();
	}

	@Test
	void refreshRereadsWhichEndpointDockerIsPointedAt() {
		// Otherwise a "docker context use" mid-session, or a context that was remote once, would
		// keep the panel answering from what it saw at startup until Commander restarted.
		var service = new FakeDockerService(Set.of());
		var plugin = new DockerFilePanelPlugin(service);

		plugin.refresh();

		assertEquals(1, service.endpointResets);
		plugin.unload();
	}

	@Test
	void unloadClosesTheServiceExactlyOnce() {
		var service = new FakeDockerService(Set.of());
		var plugin = new DockerFilePanelPlugin(service);
		plugin.unload();
		assertEquals(List.of("close:"), service.calls);
	}

	@Test
	void onlyDockerResourcesAreClaimedByThePanel() {
		var plugin = new DockerFilePanelPlugin(new FakeDockerService(Set.of()));
		assertTrue(plugin.supports(DockerResource.root()));
		assertTrue(plugin.supports(container("a", "a", "running")));
		assertFalse(plugin.supports(null));
		plugin.unload();
	}
}
