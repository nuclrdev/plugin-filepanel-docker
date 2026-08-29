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

import java.util.Locale;

/** State-only action applicability; deliberately performs no Docker calls. */
public record ContainerActions(
		boolean start,
		boolean stop,
		boolean restart,
		boolean pause,
		boolean resume,
		boolean delete) {

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
