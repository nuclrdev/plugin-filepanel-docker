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

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import lombok.extern.slf4j.Slf4j;

/** Existing-style modal messages and the intentionally small Docker creation dialog. */
@Slf4j
final class DockerDialogs {

	private DockerDialogs() {
	}

	static void error(String title, String message) {
		SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(activeWindow(), message,
				title, JOptionPane.ERROR_MESSAGE));
	}

	static void error(String title, DockerException error) {
		if (error != null && error.kind() != DockerException.Kind.CANCELLED) {
			error(title, error.userMessage());
		}
	}

	static void info(String title, String message) {
		SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(activeWindow(), message,
				title, JOptionPane.INFORMATION_MESSAGE));
	}

	static boolean confirmDestructive(String title, String message) {
		Object[] options = { "Delete", "Cancel" };
		var result = new boolean[1];
		onEdtAndWait(() -> {
			var pane = new JOptionPane(message, JOptionPane.WARNING_MESSAGE,
					JOptionPane.DEFAULT_OPTION, null, options, options[1]);
			JDialog dialog = pane.createDialog(activeWindow(), title);
			dialog.setVisible(true);
			dialog.dispose();
			result[0] = options[0].equals(pane.getValue());
		});
		return result[0];
	}

	static boolean confirm(String title, String message) {
		var result = new boolean[1];
		onEdtAndWait(() -> result[0] = JOptionPane.showConfirmDialog(activeWindow(), message, title,
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION);
		return result[0];
	}

	static String prompt(String title, String message) {
		var result = new String[1];
		onEdtAndWait(() -> result[0] = JOptionPane.showInputDialog(activeWindow(), message, title,
				JOptionPane.PLAIN_MESSAGE));
		return result[0];
	}

	static RunContainerRequest runImage(String imageId, String imageLabel) {
		var result = new RunContainerRequest[1];
		onEdtAndWait(() -> {
			JTextField name = new JTextField(32);
			JTextField command = new JTextField(32);
			JTextArea environment = area();
			JTextArea ports = area();
			JTextArea volumes = area();

			JPanel fields = new JPanel(new GridLayout(0, 1, 0, 4));
			fields.add(new JLabel("Container name (optional)"));
			fields.add(name);
			fields.add(new JLabel("Command override (optional)"));
			fields.add(command);
			fields.add(new JLabel("Environment variables — one NAME=value per line (optional)"));
			fields.add(new JScrollPane(environment));
			fields.add(new JLabel("Port mappings — one host:container per line (optional)"));
			fields.add(new JScrollPane(ports));
			fields.add(new JLabel("Volume mappings — one source:destination per line (optional)"));
			fields.add(new JScrollPane(volumes));

			JPanel panel = new JPanel(new BorderLayout(0, 8));
			panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
			panel.add(new JLabel("Create and start a container from " + imageLabel), BorderLayout.NORTH);
			panel.add(fields, BorderLayout.CENTER);

			if (JOptionPane.showConfirmDialog(activeWindow(), panel, "Run Image",
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
				try {
					result[0] = new RunContainerRequest(imageId, name.getText().strip(),
							DockerCommandLine.parse(command.getText()), lines(environment), lines(ports), lines(volumes));
				} catch (IllegalArgumentException e) {
					error("Run Image", e.getMessage());
				}
			}
		});
		return result[0];
	}

	private static JTextArea area() {
		JTextArea area = new JTextArea(2, 32);
		area.setLineWrap(false);
		return area;
	}

	private static List<String> lines(JTextArea area) {
		return area.getText().lines().map(String::strip).filter(line -> !line.isBlank()).toList();
	}

	static Window activeWindow() {
		return KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
	}

	static void onEdtAndWait(Runnable runnable) {
		if (SwingUtilities.isEventDispatchThread()) {
			runnable.run();
			return;
		}
		try {
			SwingUtilities.invokeAndWait(runnable);
		} catch (Exception e) {
			log.warn("Could not run Docker dialog on the EDT: {}", e.getMessage(), e);
		}
	}
}
