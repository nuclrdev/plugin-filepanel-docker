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

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Local Docker service implemented with structured Docker CLI output. */
@Slf4j
public final class CliDockerService implements DockerService {

	private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(30);
	private static final Duration ACTION_TIMEOUT = Duration.ofMinutes(2);
	private static final Duration EXPORT_TIMEOUT = Duration.ofMinutes(15);
	private static final long STALE_AFTER_SECONDS = Duration.ofHours(24).toSeconds();
	private static final BooleanSupplier NEVER_CANCELLED = () -> false;

	private final DockerCli cli = new DockerCli();
	private final ObjectMapper mapper = new ObjectMapper();
	private final String sessionId = UUID.randomUUID().toString();
	private final Set<String> activeHelpers = ConcurrentHashMap.newKeySet();
	private volatile Boolean localEndpoint;
	private volatile String activeEndpoint;

	@Override
	public List<DockerContainer> listContainers(BooleanSupplier cancelled) throws DockerException {
		return listContainers(List.of(), cancelled).stream().filter(container -> !container.temporary()).toList();
	}

	@Override
	public List<DockerContainer> containersUsingImage(String imageId, BooleanSupplier cancelled)
			throws DockerException {
		return listContainers(List.of("--filter", "ancestor=" + imageId), cancelled).stream()
				.filter(container -> !container.temporary()).toList();
	}

	@Override
	public List<DockerContainer> containersUsingVolume(String volumeName, BooleanSupplier cancelled)
			throws DockerException {
		return listContainers(List.of("--filter", "volume=" + volumeName), cancelled).stream()
				.filter(container -> !container.temporary()).toList();
	}

	private List<DockerContainer> listContainers(List<String> options, BooleanSupplier cancelled)
			throws DockerException {
		ensureLocalEndpoint(cancelled);
		var args = new ArrayList<>(List.of("container", "ls", "--all", "--no-trunc"));
		args.addAll(options);
		args.add("--format");
		args.add("json");
		String output = cli.run(args, QUERY_TIMEOUT, cancelled).stdout();
		var containers = new ArrayList<DockerContainer>();
		for (JsonNode node : jsonLines(output)) {
			containers.add(new DockerContainer(
					text(node, "ID"), text(node, "Names"), text(node, "Image"),
					text(node, "State"), text(node, "Status"), text(node, "CreatedAt"),
					text(node, "Ports"), text(node, "Labels")));
		}
		return List.copyOf(containers);
	}

	@Override
	public List<DockerImage> listImages(BooleanSupplier cancelled) throws DockerException {
		ensureLocalEndpoint(cancelled);
		String output = cli.run(List.of("image", "ls", "--all", "--no-trunc", "--digests", "--format", "json"),
				QUERY_TIMEOUT, cancelled).stdout();
		var images = new ArrayList<DockerImage>();
		for (JsonNode node : jsonLines(output)) {
			images.add(new DockerImage(
					text(node, "ID"), text(node, "Repository"), text(node, "Tag"),
					text(node, "Digest"), text(node, "CreatedAt"), text(node, "Size")));
		}
		return List.copyOf(images);
	}

	@Override
	public List<DockerVolume> listVolumes(BooleanSupplier cancelled) throws DockerException {
		ensureLocalEndpoint(cancelled);
		String output = cli.run(List.of("volume", "ls", "--format", "json"), QUERY_TIMEOUT, cancelled).stdout();
		var volumes = new ArrayList<DockerVolume>();
		for (JsonNode node : jsonLines(output)) {
			volumes.add(new DockerVolume(
					text(node, "Name"), text(node, "Driver"), text(node, "Scope"),
					text(node, "Mountpoint"), text(node, "Labels")));
		}
		return List.copyOf(volumes);
	}

	@Override
	public void startContainer(String id, BooleanSupplier cancelled) throws DockerException {
		runAction(List.of("container", "start", id), cancelled);
	}

	@Override
	public void stopContainer(String id, BooleanSupplier cancelled) throws DockerException {
		runAction(List.of("container", "stop", id), cancelled);
	}

	@Override
	public void restartContainer(String id, BooleanSupplier cancelled) throws DockerException {
		runAction(List.of("container", "restart", id), cancelled);
	}

	@Override
	public void pauseContainer(String id, BooleanSupplier cancelled) throws DockerException {
		runAction(List.of("container", "pause", id), cancelled);
	}

	@Override
	public void resumeContainer(String id, BooleanSupplier cancelled) throws DockerException {
		runAction(List.of("container", "unpause", id), cancelled);
	}

	@Override
	public void removeContainer(String id, BooleanSupplier cancelled) throws DockerException {
		// Deliberately no --force: Docker must refuse a running container.
		runAction(List.of("container", "rm", id), cancelled);
	}

	@Override
	public String inspectContainer(String id, BooleanSupplier cancelled) throws DockerException {
		ensureLocalEndpoint(cancelled);
		return cli.run(List.of("container", "inspect", id), QUERY_TIMEOUT, cancelled).stdout();
	}

	@Override
	public String containerLogs(String id, BooleanSupplier cancelled) throws DockerException {
		ensureLocalEndpoint(cancelled);
		DockerCli.Result result = cli.run(List.of("container", "logs", "--timestamps", "--tail", "10000", id),
				ACTION_TIMEOUT, cancelled);
		// Docker preserves the container's stdout/stderr split on the CLI streams. A text viewer
		// should show both, as one snapshot, instead of silently losing stderr-only application logs.
		if (result.stderr().isBlank()) {
			return result.stdout();
		}
		if (result.stdout().isBlank()) {
			return result.stderr();
		}
		return result.stdout() + System.lineSeparator() + result.stderr();
	}

	@Override
	public String runContainer(RunContainerRequest request, BooleanSupplier cancelled) throws DockerException {
		ensureLocalEndpoint(cancelled);
		String id = cli.run(DockerCommands.createContainer(request), ACTION_TIMEOUT, cancelled).stdout().strip();
		if (id.isBlank()) {
			throw new DockerException(DockerException.Kind.COMMAND_FAILED,
					"Docker created no container identifier.");
		}
		startContainer(id, cancelled);
		return id;
	}

	@Override
	public void removeImage(String imageId, BooleanSupplier cancelled) throws DockerException {
		// Deliberately no --force and use the stable image ID, not a display tag.
		runAction(List.of("image", "rm", imageId), cancelled);
	}

	@Override
	public String inspectImage(String imageId, BooleanSupplier cancelled) throws DockerException {
		ensureLocalEndpoint(cancelled);
		return cli.run(List.of("image", "inspect", imageId), QUERY_TIMEOUT, cancelled).stdout();
	}

	@Override
	public void createVolume(String name, BooleanSupplier cancelled) throws DockerException {
		runAction(List.of("volume", "create", name), cancelled);
	}

	@Override
	public void removeVolume(String name, BooleanSupplier cancelled) throws DockerException {
		// Deliberately no --force.
		runAction(List.of("volume", "rm", name), cancelled);
	}

	@Override
	public String inspectVolume(String name, BooleanSupplier cancelled) throws DockerException {
		ensureLocalEndpoint(cancelled);
		return cli.run(List.of("volume", "inspect", name), QUERY_TIMEOUT, cancelled).stdout();
	}

	@Override
	public void exportContainer(String containerId, Path destination, BooleanSupplier cancelled)
			throws DockerException {
		ensureLocalEndpoint(cancelled);
		cli.runToFile(List.of("container", "export", containerId), destination, EXPORT_TIMEOUT, cancelled);
	}

	@Override
	public void exportImage(String imageId, Path destination, BooleanSupplier cancelled) throws DockerException {
		ensureLocalEndpoint(cancelled);
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String name = "nuclr-image-browser-" + sessionId.substring(0, 8) + '-' + suffix;
		String helperId = null;
		try {
			helperId = cli.run(DockerCommands.createImageBrowser(imageId, name, sessionId),
					ACTION_TIMEOUT, cancelled).stdout().strip();
			if (helperId.isBlank()) {
				throw new DockerException(DockerException.Kind.COMMAND_FAILED,
						"Docker created no temporary container identifier.");
			}
			activeHelpers.add(helperId);
			exportContainer(helperId, destination, cancelled);
		} finally {
			if (helperId != null && !helperId.isBlank()) {
				removeHelper(helperId);
			}
		}
	}

	@Override
	public void cleanupStaleTemporaryContainers() {
		try {
			List<DockerContainer> helpers = listContainers(
					List.of("--filter", "label=" + TEMPORARY_LABEL + "=true"), NEVER_CANCELLED);
			long now = Instant.now().getEpochSecond();
			for (DockerContainer helper : helpers) {
				long created = labelLong(helper.labels(), CREATED_AT_LABEL);
				if (created > 0 && now - created >= STALE_AFTER_SECONDS) {
					log.info("Removing stale Nuclr Docker helper {}", helper.shortId());
					removeHelper(helper.id());
				}
			}
		} catch (DockerException e) {
			// Initialization cleanup is best-effort; normal navigation reports availability errors.
			log.debug("Docker temporary-resource cleanup skipped: {}", e.getMessage());
		}
	}

	@Override
	public synchronized void resetEndpoint() {
		localEndpoint = null;
		activeEndpoint = null;
	}

	private void runAction(List<String> args, BooleanSupplier cancelled) throws DockerException {
		ensureLocalEndpoint(cancelled);
		cli.run(args, ACTION_TIMEOUT, cancelled);
	}

	private void ensureLocalEndpoint(BooleanSupplier cancelled) throws DockerException {
		Boolean known = localEndpoint;
		if (known != null) {
			if (!known) {
				throw remoteEndpointError(activeEndpoint);
			}
			return;
		}
		synchronized (this) {
			if (localEndpoint != null) {
				if (!localEndpoint) throw remoteEndpointError(activeEndpoint);
				return;
			}
			String endpoint = effectiveEndpoint(cancelled);
			activeEndpoint = endpoint;
			localEndpoint = DockerEndpoints.isLocal(endpoint);
			if (!localEndpoint) throw remoteEndpointError(endpoint);
		}
	}

	private String effectiveEndpoint(BooleanSupplier cancelled) throws DockerException {
		String environmentHost = System.getenv("DOCKER_HOST");
		if (environmentHost != null && !environmentHost.isBlank()) {
			return environmentHost;
		}
		String output = cli.run(List.of("context", "inspect", "--format", "json"),
				QUERY_TIMEOUT, cancelled).stdout();
		try {
			JsonNode root = mapper.readTree(output);
			if (root.isArray() && !root.isEmpty()) {
				return root.get(0).path("Endpoints").path("docker").path("Host").asText("");
			}
			return "";
		} catch (Exception e) {
			throw new DockerException(DockerException.Kind.COMMAND_FAILED,
					"Docker returned invalid context information.", e);
		}
	}

	private static DockerException remoteEndpointError(String endpoint) {
		return new DockerException(DockerException.Kind.COMMAND_FAILED,
				"Remote Docker endpoints are not supported by this local Docker plugin."
						+ System.lineSeparator() + "Active endpoint: " + DockerEndpoints.display(endpoint));
	}

	private List<JsonNode> jsonLines(String output) throws DockerException {
		if (output == null || output.isBlank()) {
			return List.of();
		}
		var nodes = new ArrayList<JsonNode>();
		try {
			for (String line : output.lines().toList()) {
				if (!line.isBlank()) {
					nodes.add(mapper.readTree(line));
				}
			}
			return nodes;
		} catch (Exception e) {
			throw new DockerException(DockerException.Kind.COMMAND_FAILED,
					"Docker returned invalid JSON output.", e);
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isMissingNode() || value.isNull() ? "" : value.asText("");
	}

	private static long labelLong(String labels, String key) {
		if (labels == null || labels.isBlank()) {
			return -1;
		}
		String prefix = key + '=';
		for (String label : labels.split(",")) {
			if (label.startsWith(prefix)) {
				try {
					return Long.parseLong(label.substring(prefix.length()));
				} catch (NumberFormatException ignored) {
					return -1;
				}
			}
		}
		return -1;
	}

	private void removeHelper(String id) {
		boolean interrupted = Thread.interrupted();
		try {
			cli.run(List.of("container", "rm", id), ACTION_TIMEOUT, NEVER_CANCELLED);
		} catch (DockerException e) {
			log.warn("Could not remove Nuclr temporary Docker container {}: {}", DockerNames.shortId(id),
					e.getMessage());
		} finally {
			activeHelpers.remove(id);
			if (interrupted) Thread.currentThread().interrupt();
		}
	}

	@Override
	public void close() {
		for (String helper : List.copyOf(activeHelpers)) {
			removeHelper(helper);
		}
		cli.close();
	}
}
