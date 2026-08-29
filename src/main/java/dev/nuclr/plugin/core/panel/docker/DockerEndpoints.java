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

import java.net.URI;
import java.util.Locale;

/** Ensures the initial plugin does not accidentally operate on an SSH/remote Docker context. */
final class DockerEndpoints {

	private DockerEndpoints() {
	}

	static boolean isLocal(String endpoint) {
		if (endpoint == null || endpoint.isBlank()) {
			return true;
		}
		String lower = endpoint.toLowerCase(Locale.ROOT);
		if (lower.startsWith("unix://") || lower.startsWith("npipe://")) {
			return true;
		}
		if (lower.startsWith("ssh://")) {
			return false;
		}
		try {
			URI uri = URI.create(endpoint);
			String host = uri.getHost();
			return host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
					|| host.equals("::1") || host.startsWith("127."));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	static String display(String endpoint) {
		if (endpoint == null || endpoint.isBlank()) {
			return "default";
		}
		try {
			URI uri = URI.create(endpoint);
			if (uri.getUserInfo() == null) {
				return endpoint;
			}
			return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(),
					uri.getQuery(), uri.getFragment()).toString();
		} catch (Exception e) {
			return endpoint.replaceFirst("//[^/@]+@", "//");
		}
	}
}
