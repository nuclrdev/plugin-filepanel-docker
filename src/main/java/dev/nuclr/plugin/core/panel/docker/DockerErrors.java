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

/** Converts Docker diagnostics into stable, user-facing error categories. */
final class DockerErrors {

	private DockerErrors() {
	}

	static DockerException classify(String stderr, int exitCode) {
		String detail = clean(stderr, exitCode);
		String lower = detail.toLowerCase(Locale.ROOT);
		if (lower.contains("access is denied") || lower.contains("permission denied")
				|| lower.contains("operation not permitted") || lower.contains("authorization denied")) {
			return new DockerException(DockerException.Kind.ACCESS_DENIED, detail);
		}
		if (lower.contains("cannot connect to the docker daemon") || lower.contains("is the docker daemon running")
				|| lower.contains("error during connect") || lower.contains("daemon is not running")
				|| lower.contains("docker desktop is not running")) {
			return new DockerException(DockerException.Kind.DAEMON_UNAVAILABLE, detail);
		}
		// Deliberately anchored on Docker's own "no such ..." wording. A bare "not found" also
		// appears in messages that have nothing to do with a missing resource — a container whose
		// entrypoint is missing reports "executable file not found in $PATH" — and telling the
		// user to refresh the panel would send them the wrong way.
		if (lower.contains("no such container") || lower.contains("no such image")
				|| lower.contains("no such volume") || lower.contains("no such object")
				|| lower.contains("no such network") || lower.contains("no such file or directory")) {
			return new DockerException(DockerException.Kind.NOT_FOUND, detail);
		}
		if (lower.contains("is in use") || lower.contains("is being used")
				|| lower.contains("volume is used") || lower.contains("image has dependent child images")
				|| lower.contains("conflict") && lower.contains("used")) {
			return new DockerException(DockerException.Kind.IN_USE, detail);
		}
		return new DockerException(DockerException.Kind.COMMAND_FAILED, detail);
	}

	private static String clean(String stderr, int exitCode) {
		String value = stderr == null ? "" : stderr.strip();
		value = value.replaceFirst("(?i)^error response from daemon:\\s*", "");
		if (value.isBlank()) {
			return "docker exited with code " + exitCode + '.';
		}
		return value.length() > 2000 ? value.substring(0, 2000) + "…" : value;
	}
}
