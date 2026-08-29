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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * A {@link DockerService} that answers from memory, so the panel's own behaviour can be tested
 * without Docker: what it calls, in what order, and what it does when one call fails.
 */
class FakeDockerService implements DockerService {

	/** Every mutating call, in order, as {@code "verb:id"}. */
	final List<String> calls = new ArrayList<>();

	/** Identifiers whose removal should fail, the way a running container's does. */
	final Set<String> refuseRemoval;

	int endpointResets;

	FakeDockerService(Set<String> refuseRemoval) {
		this.refuseRemoval = refuseRemoval;
	}

	private void record(String verb, String id) throws DockerException {
		calls.add(verb + ':' + id);
		if (refuseRemoval.contains(id)) {
			throw new DockerException(DockerException.Kind.IN_USE, "container " + id + " is in use");
		}
	}

	@Override
	public List<DockerContainer> listContainers(BooleanSupplier cancelled) {
		return List.of();
	}

	@Override
	public List<DockerContainer> containersUsingImage(String imageId, BooleanSupplier cancelled) {
		return List.of();
	}

	@Override
	public List<DockerContainer> containersUsingVolume(String volumeName, BooleanSupplier cancelled) {
		return List.of();
	}

	@Override
	public List<DockerImage> listImages(BooleanSupplier cancelled) {
		return List.of();
	}

	@Override
	public List<DockerVolume> listVolumes(BooleanSupplier cancelled) {
		return List.of();
	}

	@Override
	public void startContainer(String id, BooleanSupplier cancelled) throws DockerException {
		record("start", id);
	}

	@Override
	public void stopContainer(String id, BooleanSupplier cancelled) throws DockerException {
		record("stop", id);
	}

	@Override
	public void restartContainer(String id, BooleanSupplier cancelled) throws DockerException {
		record("restart", id);
	}

	@Override
	public void pauseContainer(String id, BooleanSupplier cancelled) throws DockerException {
		record("pause", id);
	}

	@Override
	public void resumeContainer(String id, BooleanSupplier cancelled) throws DockerException {
		record("resume", id);
	}

	@Override
	public void removeContainer(String id, BooleanSupplier cancelled) throws DockerException {
		record("rm-container", id);
	}

	@Override
	public String inspectContainer(String id, BooleanSupplier cancelled) {
		return "[{\"Id\":\"" + id + "\"}]";
	}

	@Override
	public String containerLogs(String id, BooleanSupplier cancelled) {
		return "log line";
	}

	@Override
	public String runContainer(RunContainerRequest request, BooleanSupplier cancelled) {
		calls.add("run:" + request.imageId());
		return "created-container";
	}

	@Override
	public void removeImage(String imageId, BooleanSupplier cancelled) throws DockerException {
		record("rm-image", imageId);
	}

	@Override
	public String inspectImage(String imageId, BooleanSupplier cancelled) {
		return "[{\"Id\":\"" + imageId + "\"}]";
	}

	@Override
	public void createVolume(String name, BooleanSupplier cancelled) throws DockerException {
		record("create-volume", name);
	}

	@Override
	public void removeVolume(String name, BooleanSupplier cancelled) throws DockerException {
		record("rm-volume", name);
	}

	@Override
	public String inspectVolume(String name, BooleanSupplier cancelled) {
		return "[{\"Name\":\"" + name + "\"}]";
	}

	@Override
	public void exportContainer(String containerId, Path destination, BooleanSupplier cancelled) {
		calls.add("export-container:" + containerId);
	}

	@Override
	public void exportImage(String imageId, Path destination, BooleanSupplier cancelled) {
		calls.add("export-image:" + imageId);
	}

	@Override
	public void cleanupStaleTemporaryContainers() {
		calls.add("cleanup:");
	}

	@Override
	public void resetEndpoint() {
		endpointResets++;
	}

	@Override
	public void close() {
		calls.add("close:");
	}
}
