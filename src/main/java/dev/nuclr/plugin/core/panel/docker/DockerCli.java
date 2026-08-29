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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Cancellable, timeout-bound process transport for the Docker CLI. */
final class DockerCli implements AutoCloseable {

	/** System property and environment variable naming the docker executable to run. */
	static final String EXECUTABLE_PROPERTY = "nuclr.docker.executable";
	static final String EXECUTABLE_ENVIRONMENT = "NUCLR_DOCKER_EXECUTABLE";

	private static final long POLL_MILLIS = 100;
	private static final int MAX_TEXT_BYTES = 32 * 1024 * 1024;
	private static final int MAX_ERROR_BYTES = 256 * 1024;
	private final Set<Process> active = ConcurrentHashMap.newKeySet();
	private volatile boolean closed;

	record Result(String stdout, String stderr) {
	}

	Result run(List<String> args, Duration timeout, BooleanSupplier cancelled) throws DockerException {
		var stdout = new CappedOutputStream(MAX_TEXT_BYTES);
		var stderr = new CappedOutputStream(MAX_ERROR_BYTES);
		execute(args, timeout, cancelled, stdout, stderr);
		if (stdout.truncated()) {
			throw new DockerException(DockerException.Kind.COMMAND_FAILED,
					"Docker returned more than " + MAX_TEXT_BYTES / (1024 * 1024) + " MB of text output.");
		}
		return new Result(stdout.text(), stderr.text());
	}

	void runToFile(List<String> args, Path destination, Duration timeout, BooleanSupplier cancelled)
			throws DockerException {
		try (OutputStream stdout = new java.io.BufferedOutputStream(
				Files.newOutputStream(destination), 1 << 16)) {
			var stderr = new CappedOutputStream(MAX_ERROR_BYTES);
			execute(args, timeout, cancelled, stdout, stderr);
		} catch (DockerException e) {
			throw e;
		} catch (IOException e) {
			throw new DockerException(DockerException.Kind.COMMAND_FAILED,
					"Could not write the Docker filesystem snapshot: " + e.getMessage(), e);
		}
	}

	private void execute(List<String> args, Duration timeout, BooleanSupplier cancelled,
			OutputStream stdout, CappedOutputStream stderr) throws DockerException {

		if (closed || isCancelled(cancelled)) {
			throw new DockerException(DockerException.Kind.CANCELLED, "Docker operation cancelled.");
		}

		List<String> command = new ArrayList<>(args.size() + 1);
		command.add(executable());
		command.addAll(args);

		Process process;
		try {
			process = new ProcessBuilder(command).start();
			process.getOutputStream().close();
		} catch (IOException e) {
			throw new DockerException(startFailureKind(e), startFailureMessage(e), e);
		}

		active.add(process);
		var stdoutFailure = new AtomicReference<IOException>();
		var stderrFailure = new AtomicReference<IOException>();
		Thread stdoutPump = pump(process.getInputStream(), stdout, stdoutFailure, "docker-stdout");
		Thread stderrPump = pump(process.getErrorStream(), stderr, stderrFailure, "docker-stderr");
		long deadline = System.nanoTime() + timeout.toNanos();
		boolean complete = false;

		try {
			while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
				if (closed || isCancelled(cancelled)) {
					throw new DockerException(DockerException.Kind.CANCELLED, "Docker operation cancelled.");
				}
				if (System.nanoTime() >= deadline) {
					throw new DockerException(DockerException.Kind.TIMEOUT,
							"Command timed out after " + timeout.toSeconds() + " seconds.");
				}
			}
			complete = true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new DockerException(DockerException.Kind.CANCELLED, "Docker operation interrupted.", e);
		} finally {
			if (!complete) {
				terminate(process);
			}
			joinQuietly(stdoutPump);
			joinQuietly(stderrPump);
			active.remove(process);
		}

		if (stdoutFailure.get() != null) {
			throw new DockerException(DockerException.Kind.COMMAND_FAILED,
					"Could not read Docker output: " + stdoutFailure.get().getMessage(), stdoutFailure.get());
		}
		if (stderrFailure.get() != null) {
			throw new DockerException(DockerException.Kind.COMMAND_FAILED,
					"Could not read Docker diagnostics: " + stderrFailure.get().getMessage(), stderrFailure.get());
		}
		if (process.exitValue() != 0) {
			throw DockerErrors.classify(stderr.text(), process.exitValue());
		}
	}

	private static Thread pump(InputStream input, OutputStream output,
			AtomicReference<IOException> failure, String name) {
		Thread thread = Thread.ofVirtual().name(name).start(() -> {
			try (input) {
				input.transferTo(output);
			} catch (IOException e) {
				failure.set(e);
			}
		});
		return thread;
	}

	private static boolean isCancelled(BooleanSupplier cancelled) {
		return Thread.currentThread().isInterrupted() || cancelled != null && cancelled.getAsBoolean();
	}

	/**
	 * The docker executable to run.
	 *
	 * <p>Overridable because a working Docker installation is not always on {@code PATH} — Docker
	 * Desktop's CLI lives under the user's profile, and a Snap or Colima install may not be linked
	 * where the launcher's environment can see it. Without this the panel would simply report that
	 * Docker is not installed, with nothing the user could do about it from inside Commander.
	 */
	private static String executable() {
		String configured = System.getProperty(EXECUTABLE_PROPERTY);
		if (configured == null || configured.isBlank()) {
			configured = System.getenv(EXECUTABLE_ENVIRONMENT);
		}
		if (configured != null && !configured.isBlank()) {
			return configured.strip();
		}
		return isWindows() ? "docker.exe" : "docker";
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
	}

	/**
	 * Tell a missing docker apart from one that is present but refused us.
	 *
	 * <p>Reporting a permission problem as "Docker is not installed" sends the user looking for the
	 * wrong thing entirely.
	 */
	private static DockerException.Kind startFailureKind(IOException failure) {
		String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase(Locale.ROOT);
		if (message.contains("permission denied") || message.contains("access is denied")
				|| message.contains("not permitted")) {
			return DockerException.Kind.ACCESS_DENIED;
		}
		return DockerException.Kind.NOT_INSTALLED;
	}

	private static String startFailureMessage(IOException failure) {
		String detail = failure.getMessage() == null || failure.getMessage().isBlank()
				? "" : System.lineSeparator() + failure.getMessage();
		return "Could not start '" + executable() + "'." + detail;
	}

	private static void terminate(Process process) {
		process.destroy();
		try {
			if (!process.waitFor(1, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	private static void joinQuietly(Thread thread) {
		boolean interrupted = false;
		while (thread.isAlive()) {
			try {
				thread.join();
			} catch (InterruptedException e) {
				interrupted = true;
			}
		}
		if (interrupted) Thread.currentThread().interrupt();
	}

	@Override
	public void close() {
		closed = true;
		for (Process process : List.copyOf(active)) {
			terminate(process);
		}
		active.clear();
	}

	private static final class CappedOutputStream extends OutputStream {
		private final int limit;
		private final ByteArrayOutputStream captured;
		private long total;

		CappedOutputStream(int limit) {
			this.limit = limit;
			this.captured = new ByteArrayOutputStream(Math.min(limit, 8192));
		}

		@Override
		public synchronized void write(int value) {
			if (total++ < limit) {
				captured.write(value);
			}
		}

		@Override
		public synchronized void write(byte[] bytes, int offset, int length) {
			int accepted = (int) Math.min(length, Math.max(0L, limit - total));
			if (accepted > 0) {
				captured.write(bytes, offset, accepted);
			}
			total += length;
		}

		synchronized boolean truncated() {
			return total > limit;
		}

		synchronized String text() {
			return captured.toString(StandardCharsets.UTF_8).strip();
		}
	}
}
