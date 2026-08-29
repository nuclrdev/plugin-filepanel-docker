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

class DockerCommandsTest {

	@Test
	void constructsCreateRequestAsAnArgumentVector() {
		RunContainerRequest request = new RunContainerRequest("sha256:image", "web",
				List.of("server", "--port", "8080"), List.of("MODE=prod"),
				List.of("8080:8080"), List.of("data:/data"));
		assertEquals(List.of("container", "create", "--name", "web",
				"--env", "MODE=prod", "--publish", "8080:8080", "--volume", "data:/data",
				"sha256:image", "server", "--port", "8080"), DockerCommands.createContainer(request));
	}
}
