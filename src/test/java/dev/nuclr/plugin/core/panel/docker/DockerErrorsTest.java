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

class DockerErrorsTest {

	@Test
	void classifiesDaemonAndPermissionFailures() {
		assertEquals(DockerException.Kind.DAEMON_UNAVAILABLE,
				DockerErrors.classify("Cannot connect to the Docker daemon. Is it running?", 1).kind());
		assertEquals(DockerException.Kind.ACCESS_DENIED,
				DockerErrors.classify("open //./pipe/docker_engine: Access is denied.", 1).kind());
	}

	@Test
	void classifiesMissingAndInUseResources() {
		assertEquals(DockerException.Kind.NOT_FOUND,
				DockerErrors.classify("Error response from daemon: No such container: abc", 1).kind());
		assertEquals(DockerException.Kind.IN_USE,
				DockerErrors.classify("conflict: unable to delete image because it is being used", 1).kind());
	}

	@Test
	void userMessageKeepsUsefulDaemonDetail() {
		DockerException error = DockerErrors.classify("Cannot connect to the Docker daemon at unix:///x", 1);
		assertTrue(error.userMessage().startsWith("Cannot connect to the Docker daemon."));
		assertTrue(error.userMessage().contains("unix:///x"));
	}
}
