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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Pure construction of Docker CLI argument vectors (never shell strings). */
final class DockerCommands {

	private DockerCommands() {
	}

	static List<String> createContainer(RunContainerRequest request) {
		var args = new ArrayList<String>();
		args.add("container");
		args.add("create");
		if (request.name() != null && !request.name().isBlank()) {
			args.add("--name");
			args.add(request.name().strip());
		}
		for (String value : request.environment()) {
			if (!value.isBlank()) {
				args.add("--env");
				args.add(value.strip());
			}
		}
		for (String value : request.ports()) {
			if (!value.isBlank()) {
				args.add("--publish");
				args.add(value.strip());
			}
		}
		for (String value : request.volumes()) {
			if (!value.isBlank()) {
				args.add("--volume");
				args.add(value.strip());
			}
		}
		args.add(request.imageId());
		args.addAll(request.command());
		return List.copyOf(args);
	}

	static List<String> createImageBrowser(String imageId, String name, String sessionId) {
		return List.of(
				"container", "create",
				"--name", name,
				"--label", DockerService.TEMPORARY_LABEL + "=true",
				"--label", DockerService.PURPOSE_LABEL + "=image-browser",
				"--label", DockerService.SESSION_LABEL + '=' + sessionId,
				"--label", DockerService.CREATED_AT_LABEL + '=' + Instant.now().getEpochSecond(),
				imageId,
				"true");
	}
}
