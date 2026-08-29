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

/** Pure display and path-safe naming helpers. */
public final class DockerNames {

	private DockerNames() {
	}

	public static String shortId(String id) {
		if (id == null || id.isBlank()) {
			return "";
		}
		String value = id.startsWith("sha256:") ? id.substring(7) : id;
		return value.substring(0, Math.min(12, value.length()));
	}

	public static String imageDisplayName(String repository, String tag, String id) {
		String repo = cleanNone(repository);
		String imageTag = cleanNone(tag);
		if (repo.isBlank()) {
			return "<dangling> (" + shortId(id) + ')';
		}
		return imageTag.isBlank() ? repo : repo + ':' + imageTag;
	}

	private static String cleanNone(String value) {
		if (value == null || value.isBlank() || "<none>".equals(value.toLowerCase(Locale.ROOT))) {
			return "";
		}
		return value;
	}
}
