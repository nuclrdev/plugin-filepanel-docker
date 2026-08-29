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

import java.util.ArrayDeque;

/** POSIX-style paths used inside Docker filesystem tar streams. */
final class DockerPaths {

	private DockerPaths() {
	}

	static String normalize(String path) {
		if (path == null || path.isBlank() || "/".equals(path)) {
			return "/";
		}
		var segments = new ArrayDeque<String>();
		for (String segment : path.replace('\\', '/').split("/+")) {
			if (segment.isBlank() || ".".equals(segment)) {
				continue;
			}
			if ("..".equals(segment)) {
				if (!segments.isEmpty()) {
					segments.removeLast();
				}
				continue;
			}
			segments.addLast(segment);
		}
		return segments.isEmpty() ? "/" : "/" + String.join("/", segments);
	}

	static String parent(String path) {
		String value = normalize(path);
		if ("/".equals(value)) {
			return "/";
		}
		int slash = value.lastIndexOf('/');
		return slash <= 0 ? "/" : value.substring(0, slash);
	}

	static String name(String path) {
		String value = normalize(path);
		if ("/".equals(value)) {
			return "/";
		}
		return value.substring(value.lastIndexOf('/') + 1);
	}

	static String join(String parent, String child) {
		String base = normalize(parent);
		return normalize(("/".equals(base) ? "" : base) + '/' + child);
	}
}
