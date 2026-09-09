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
	void aLifecycleOperationRunsOverTheWholeSelection() {
		var service = new FakeDockerService(Set.of());
		var plugin = new DockerFilePanelPlugin(service);

		List<NuclrResource> targets = List.of(
				container("a", "a", "running"),
				container("b", "b", "running"),
				container("c", "c", "running"));

		List<String> failures = plugin.applyToAll("Stop", service::stopContainer, targets, () -> false, null);

		assertEquals(List.of("stop:a", "stop:b", "stop:c"), service.calls);
		assertTrue(failures.isEmpty());
		plugin.unload();
	}

	@Test
	void oneRefusedContainerDoesNotAbandonTheRestOfTheSelection() {
		var service = new FakeDockerService(Set.of("busy"));
		var plugin = new DockerFilePanelPlugin(service);

		List<NuclrResource> targets = List.of(
				container("first", "first", "exited"),
				container("busy", "busy", "exited"),
				container("third", "third", "exited"));

		List<String> failures = plugin.applyToAll("Start", service::startContainer, targets, () -> false, null);

		assertEquals(List.of("start:first", "start:busy", "start:third"), service.calls);
		assertEquals(1, failures.size());
		assertTrue(failures.getFirst().startsWith("busy"));
		plugin.unload();
	}

	@Test
	void aLifecycleOperationStopsWhenTheUserCancels() {
		var service = new FakeDockerService(Set.of());
		var plugin = new DockerFilePanelPlugin(service);

		List<NuclrResource> targets = List.of(container("a", "a", "running"), container("b", "b", "running"));
		boolean[] cancelAfterFirst = { false };

		List<String> failures = plugin.applyToAll("Stop", service::stopContainer, targets, () -> {
			boolean cancelled = cancelAfterFirst[0];
			cancelAfterFirst[0] = true;
			return cancelled;
		}, null);

		assertEquals(List.of("stop:a"), service.calls);
		assertTrue(failures.isEmpty());
		plugin.unload();
	}

	@Test
	void everyTargetIsAnnouncedSoTheProgressDialogNamesWhatItIsDoing() {
		var service = new FakeDockerService(Set.of());
		var plugin = new DockerFilePanelPlugin(service);
		var announced = new ArrayList<String>();

		plugin.applyToAll("Stop", service::stopContainer,
				List.of(container("a", "web", "running"), container("b", "db", "running")),
				() -> false, announced::add);

		assertEquals(List.of("Stop web", "Stop db"), announced);
		plugin.unload();
	}

	@Test
	void aMixedSelectionOnlySendsDockerTheContainersTheOperationIsValidFor() {
		// Pressing Start on a selection that is half running must not produce an error dialog per
		// container that was already up.
		List<NuclrResource> containers = List.of(
				container("a", "a", "exited"),
				container("b", "b", "running"),
				container("c", "c", "created"));

		List<NuclrResource> startable =
				DockerFilePanelPlugin.applicableTargets(ContainerActions::start, containers);
		List<NuclrResource> stoppable =
				DockerFilePanelPlugin.applicableTargets(ContainerActions::stop, containers);

		assertEquals(List.of("a", "c"), startable.stream().map(NuclrResource::getName).toList());
		assertEquals(List.of("b"), stoppable.stream().map(NuclrResource::getName).toList());
	}

	@Test
	void theSelectionWinsOverTheFocusedRowAndNonContainersAreIgnored() {
		NuclrResource focused = container("focused", "focused", "running");
		List<NuclrResource> selection = List.of(
				container("a", "a", "running"), volume("scratch"), container("b", "b", "running"));

		assertEquals(List.of("a", "b"), DockerFilePanelPlugin.containerTargets(selection, focused)
				.stream().map(NuclrResource::getName).toList());
		assertEquals(List.of("focused"), DockerFilePanelPlugin.containerTargets(List.of(), focused)
				.stream().map(NuclrResource::getName).toList());
		assertEquals(List.of(), DockerFilePanelPlugin.containerTargets(null, null));
		assertEquals(List.of(), DockerFilePanelPlugin.containerTargets(List.of(volume("scratch")), null));
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
	void everyPublishedFunctionKeyIsOneTheCommanderCanActuallyDeliver() {
		// A key the host never hands to a plugin fails silently: the bar paints nothing under it and
		// the keystroke falls through, so the command is reachable only from the context menu. F9 is
		// the commander's menu-bar key under every modifier (FunctionKeyBar#resourceFor), and
		// Shift+F10 is the keyboard context-menu key on Windows, GTK and Qt. Neither can be claimed.
		var plugin = new DockerFilePanelPlugin(new FakeDockerService(Set.of()));

		var resources = new ArrayList<NuclrResource>(List.of(
				DockerResource.root(),
				DockerResource.image(new DockerImage("img", "nginx", "latest", "", "", "1MB"), null),
				volume("scratch")));
		for (String state : List.of("running", "exited", "created", "paused", "restarting", "dead")) {
			resources.add(container(state, state, state));
		}

		for (NuclrResource resource : resources) {
			for (var item : plugin.menuItems(resource)) {
				String key = item.getFunctionKey();
				assertNotEquals("F9", key, item.getName() + " is bound to a key the commander reserves");
				assertFalse(key.endsWith("+F9"), item.getName() + " is bound to " + key
						+ ", which never reaches a plugin");
				assertNotEquals("Shift+F10", key, item.getName() + " is bound to the context-menu key");
			}
		}
		plugin.unload();
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
