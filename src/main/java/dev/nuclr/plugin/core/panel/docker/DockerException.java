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

import java.io.IOException;

/** A user-meaningful failure reported by the local Docker transport. */
public final class DockerException extends IOException {

	private static final long serialVersionUID = 1L;

	public enum Kind {
		NOT_INSTALLED,
		DAEMON_UNAVAILABLE,
		ACCESS_DENIED,
		TIMEOUT,
		NOT_FOUND,
		IN_USE,
		CANCELLED,
		COMMAND_FAILED
	}

	private final Kind kind;

	public DockerException(Kind kind, String message) {
		super(message);
		this.kind = kind;
	}

	public DockerException(Kind kind, String message, Throwable cause) {
		super(message, cause);
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}

	/** Text suitable for a normal Commander error dialog. */
	public String userMessage() {
		return switch (kind) {
			case NOT_INSTALLED -> "Docker is not installed or the docker command is not on PATH.";
			case DAEMON_UNAVAILABLE -> withDetail("Cannot connect to the Docker daemon.");
			case ACCESS_DENIED -> withDetail("Docker denied access. Check your Docker permissions.");
			case TIMEOUT -> withDetail("The Docker operation timed out.");
			case NOT_FOUND -> withDetail("The Docker resource no longer exists. Refresh the panel.");
			case IN_USE -> withDetail("Docker cannot remove this resource because it is currently in use.");
			case CANCELLED -> "The Docker operation was cancelled.";
			case COMMAND_FAILED -> withDetail("The Docker operation failed.");
		};
	}

	private String withDetail(String summary) {
		String detail = getMessage();
		if (detail == null || detail.isBlank() || summary.equals(detail)) {
			return summary;
		}
		return summary + System.lineSeparator() + System.lineSeparator() + detail;
	}
}
