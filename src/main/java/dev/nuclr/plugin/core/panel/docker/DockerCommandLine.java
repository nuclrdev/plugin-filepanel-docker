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

import java.util.ArrayList;
import java.util.List;

/**
 * Splits the command override typed in the Run Image dialog into an argument vector.
 *
 * <p>Nothing here ever reaches a shell — the result is handed to {@code ProcessBuilder} as separate
 * arguments — but users type command lines the way a shell would accept them, so quoting and
 * escaping have to be understood well enough not to surprise them. A backslash that is not escaping
 * anything is kept, because container and Windows paths are full of them.
 */
final class DockerCommandLine {

	private DockerCommandLine() {
	}

	/**
	 * Parse one command line into its arguments.
	 *
	 * @param value the raw text; blank yields no arguments
	 * @return the arguments, in order
	 * @throws IllegalArgumentException if a quote is left open
	 */
	static List<String> parse(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		var result = new ArrayList<String>();
		var token = new StringBuilder();
		char quote = 0;
		for (int index = 0; index < value.length(); index++) {
			char c = value.charAt(index);
			if (c == '\\' && quote != '\'' && index + 1 < value.length()) {
				char next = value.charAt(index + 1);
				boolean escapes = quote == '"' ? next == '"' || next == '\\'
						: Character.isWhitespace(next) || next == '\'' || next == '"' || next == '\\';
				if (escapes) {
					token.append(next);
					index++;
				} else {
					token.append(c); // Preserve Windows/container path separators.
				}
			} else if (quote != 0) {
				if (c == quote) {
					quote = 0;
				} else {
					token.append(c);
				}
			} else if (c == '\'' || c == '"') {
				quote = c;
			} else if (Character.isWhitespace(c)) {
				if (!token.isEmpty()) {
					result.add(token.toString());
					token.setLength(0);
				}
			} else {
				token.append(c);
			}
		}
		if (quote != 0) {
			throw new IllegalArgumentException("The command contains an unfinished quote or escape.");
		}
		if (!token.isEmpty()) {
			result.add(token.toString());
		}
		return List.copyOf(result);
	}
}
