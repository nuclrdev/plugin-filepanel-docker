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

import java.util.List;
import java.util.Locale;

/** State-only action applicability; deliberately performs no Docker calls. */
public record ContainerActions(
		boolean start,
		boolean stop,
		boolean restart,
		boolean pause,
		boolean resume,
		boolean delete) {

	/**
	 * What a whole selection offers: an action at least one of the containers allows.
	 *
	 * <p>A selection is rarely of one mind — four containers running and two exited is the normal
	 * case — so gating on every member would leave a mixed selection with nothing to offer. The
	 * caller applies each operation only to the containers it is actually valid for.
	 */
	public static ContainerActions forStates(List<String> rawStates) {
		boolean start = false;
		boolean stop = false;
		boolean restart = false;
		boolean pause = false;
		boolean resume = false;
		boolean delete = false;
		for (String rawState : rawStates) {
			ContainerActions actions = forState(rawState);
			start |= actions.start();
			stop |= actions.stop();
			restart |= actions.restart();
			pause |= actions.pause();
			resume |= actions.resume();
			delete |= actions.delete();
		}
		return new ContainerActions(start, stop, restart, pause, resume, delete);
	}

	public static ContainerActions forState(String rawState) {
		String state = rawState == null ? "" : rawState.toLowerCase(Locale.ROOT);
		return switch (state) {
			case "running" -> new ContainerActions(false, true, true, true, false, false);
			case "paused" -> new ContainerActions(false, true, true, false, true, false);
			case "restarting" -> new ContainerActions(false, true, false, false, false, false);
			case "created", "exited", "dead" -> new ContainerActions(true, false, false, false, false, true);
			case "removing" -> new ContainerActions(false, false, false, false, false, false);
			default -> new ContainerActions(true, false, false, false, false, true);
		};
	}
}
