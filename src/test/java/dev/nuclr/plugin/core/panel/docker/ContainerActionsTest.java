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

class ContainerActionsTest {

	@Test
	void runningContainerHasOnlyApplicableLifecycleActions() {
		ContainerActions actions = ContainerActions.forState("running");
		assertFalse(actions.start());
		assertTrue(actions.stop());
		assertTrue(actions.restart());
		assertTrue(actions.pause());
		assertFalse(actions.resume());
		assertFalse(actions.delete());
	}

	@Test
	void pausedContainerCanResumeOrStop() {
		ContainerActions actions = ContainerActions.forState("paused");
		assertTrue(actions.resume());
		assertTrue(actions.stop());
		assertFalse(actions.pause());
		assertFalse(actions.delete());
	}

	@Test
	void mixedSelectionOffersEveryActionAtLeastOneContainerAllows() {
		// Otherwise a selection of running and stopped containers would offer neither Start nor
		// Stop, and there would be no way to act on it at all.
		ContainerActions actions = ContainerActions.forStates(List.of("running", "exited"));
		assertTrue(actions.start());
		assertTrue(actions.stop());
		assertTrue(actions.delete());
		assertFalse(actions.resume());
	}

	@Test
	void emptySelectionOffersNothing() {
		ContainerActions actions = ContainerActions.forStates(List.of());
		assertFalse(actions.start());
		assertFalse(actions.stop());
		assertFalse(actions.restart());
		assertFalse(actions.pause());
		assertFalse(actions.resume());
		assertFalse(actions.delete());
	}

	@Test
	void stoppedContainerCanStartOrDelete() {
		ContainerActions actions = ContainerActions.forState("exited");
		assertTrue(actions.start());
		assertTrue(actions.delete());
		assertFalse(actions.stop());
		assertFalse(actions.restart());
	}
}
