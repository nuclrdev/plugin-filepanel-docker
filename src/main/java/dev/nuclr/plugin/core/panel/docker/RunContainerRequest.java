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

/** Values collected by the small Run Image dialog. */
public record RunContainerRequest(
		String imageId,
		String name,
		List<String> command,
		List<String> environment,
		List<String> ports,
		List<String> volumes) {

	public RunContainerRequest {
		command = command == null ? List.of() : List.copyOf(command);
		environment = environment == null ? List.of() : List.copyOf(environment);
		ports = ports == null ? List.of() : List.copyOf(ports);
		volumes = volumes == null ? List.of() : List.copyOf(volumes);
	}
}
