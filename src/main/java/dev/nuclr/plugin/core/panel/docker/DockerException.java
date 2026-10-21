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

import static dev.nuclr.plugin.core.panel.docker.Messages.tr;

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
			case NOT_INSTALLED -> tr("error.not.installed");
			case DAEMON_UNAVAILABLE -> withDetail(tr("error.daemon"));
			case ACCESS_DENIED -> withDetail(tr("error.access"));
			case TIMEOUT -> withDetail(tr("error.timeout"));
			case NOT_FOUND -> withDetail(tr("error.not.found"));
			case IN_USE -> withDetail(tr("error.in.use"));
			case CANCELLED -> tr("error.cancelled");
			case COMMAND_FAILED -> withDetail(tr("error.failed"));
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
