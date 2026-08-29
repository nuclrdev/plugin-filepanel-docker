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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import lombok.extern.slf4j.Slf4j;

/**
 * The modal, cancellable progress dialog behind every Docker operation that can block.
 *
 * <p>Commander dispatches {@code act()} on the event dispatch thread: a context-menu item, a
 * function key and an Enter press all arrive there synchronously. Docker work does not belong on
 * that thread — starting a container may take a minute and exporting an image may take fifteen —
 * so the work runs on a virtual thread and reports here instead.
 *
 * <p>Because the dialog is modal, the event thread keeps pumping while the caller waits, which is
 * what lets the work put its own prompts on screen and keeps the window repainting. The pattern
 * mirrors the progress dialog in the S3 file panel.
 */
@Slf4j
final class DockerProgress {

	private DockerProgress() {
	}

	/**
	 * Run work under a progress dialog, returning once it has finished or been cancelled.
	 *
	 * @param title the dialog title, such as {@code Copy} or {@code Start}
	 * @param work  the work to run; it receives a callback wired to this dialog
	 */
	static void run(String title, Consumer<NuclrPluginCallback> work) {
		DockerDialogs.onEdtAndWait(() -> show(title, work));
	}

	/** The cancellation signal a {@link NuclrPluginCallback} carries, as the service layer wants it. */
	static BooleanSupplier cancellation(NuclrPluginCallback callback) {
		return () -> Thread.currentThread().isInterrupted() || callback != null && callback.isCancelled();
	}

	private static void show(String title, Consumer<NuclrPluginCallback> work) {

		Window owner = DockerDialogs.activeWindow();

		var itemLabel = new JLabel("Working…");
		var bar = new JProgressBar(0, 100);
		bar.setIndeterminate(true);
		bar.setStringPainted(false);
		var cancelButton = new JButton("Cancel");

		var dialog = new JDialog(owner, title, JDialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

		var north = new JPanel(new BorderLayout(0, 6));
		north.add(itemLabel, BorderLayout.NORTH);
		north.add(bar, BorderLayout.CENTER);

		var south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		south.add(cancelButton);

		var content = new JPanel(new BorderLayout(0, 10));
		content.setBorder(BorderFactory.createEmptyBorder(14, 18, 12, 18));
		content.add(north, BorderLayout.CENTER);
		content.add(south, BorderLayout.SOUTH);

		dialog.setContentPane(content);
		dialog.pack();
		dialog.setMinimumSize(new Dimension(440, dialog.getHeight()));
		dialog.setLocationRelativeTo(owner);

		var cancelled = new AtomicBoolean(false);
		var finished = new AtomicBoolean(false);

		cancelButton.addActionListener(event -> {
			cancelled.set(true);
			cancelButton.setEnabled(false);
			itemLabel.setText("Cancelling…");
		});

		var callback = new NuclrPluginCallback() {

			@Override
			public void onStart(String description) {
				SwingUtilities.invokeLater(() -> itemLabel.setText(description == null ? "" : description));
			}

			@Override
			public void onProgress(long current, long total) {
				SwingUtilities.invokeLater(() -> {
					if (total > 0) {
						bar.setIndeterminate(false);
						bar.setStringPainted(true);
						bar.setValue((int) Math.min(100, current * 100 / total));
					} else {
						bar.setIndeterminate(true);
					}
				});
			}

			@Override
			public void onComplete() {
				// The dialog closes when the work thread finishes; nothing extra to do here.
			}

			@Override
			public void onError(String description, Exception e) {
				log.warn("Docker {} failed for [{}]: {}", title, description, e == null ? "?" : e.getMessage());
			}

			@Override
			public boolean isCancelled() {
				return cancelled.get();
			}
		};

		Thread.ofVirtual().name("nuclr-docker-" + title.toLowerCase(Locale.ROOT)).start(() -> {
			try {
				work.accept(callback);
			} catch (RuntimeException e) {
				log.error("Docker {} failed: {}", title, e.getMessage(), e);
			} finally {
				SwingUtilities.invokeLater(() -> {
					finished.set(true);
					dialog.dispose();
				});
			}
		});

		// Modal: blocks here, pumping the event dispatch thread, until the work disposes the dialog.
		dialog.setVisible(true);

		if (!finished.get()) {
			// Defensive: if the dialog closed some other way, make sure the work sees a cancel.
			cancelled.set(true);
		}
	}
}
