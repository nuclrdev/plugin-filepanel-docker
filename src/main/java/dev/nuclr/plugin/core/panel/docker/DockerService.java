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
import java.util.List;
import java.util.function.BooleanSupplier;

/** All communication with the local Docker installation is centralised here. */
public interface DockerService extends AutoCloseable {

	String TEMPORARY_LABEL = "dev.nuclr.temporary";
	String PURPOSE_LABEL = "dev.nuclr.purpose";
	String SESSION_LABEL = "dev.nuclr.session";
	String CREATED_AT_LABEL = "dev.nuclr.created-at";

	List<DockerContainer> listContainers(BooleanSupplier cancelled) throws DockerException;

	List<DockerContainer> containersUsingImage(String imageId, BooleanSupplier cancelled) throws DockerException;

	List<DockerContainer> containersUsingVolume(String volumeName, BooleanSupplier cancelled) throws DockerException;

	List<DockerImage> listImages(BooleanSupplier cancelled) throws DockerException;

	List<DockerVolume> listVolumes(BooleanSupplier cancelled) throws DockerException;

	void startContainer(String id, BooleanSupplier cancelled) throws DockerException;

	void stopContainer(String id, BooleanSupplier cancelled) throws DockerException;

	void restartContainer(String id, BooleanSupplier cancelled) throws DockerException;

	void pauseContainer(String id, BooleanSupplier cancelled) throws DockerException;

	void resumeContainer(String id, BooleanSupplier cancelled) throws DockerException;

	void removeContainer(String id, BooleanSupplier cancelled) throws DockerException;

	String inspectContainer(String id, BooleanSupplier cancelled) throws DockerException;

	String containerLogs(String id, BooleanSupplier cancelled) throws DockerException;

	String runContainer(RunContainerRequest request, BooleanSupplier cancelled) throws DockerException;

	void removeImage(String imageId, BooleanSupplier cancelled) throws DockerException;

	String inspectImage(String imageId, BooleanSupplier cancelled) throws DockerException;

	void createVolume(String name, BooleanSupplier cancelled) throws DockerException;

	void removeVolume(String name, BooleanSupplier cancelled) throws DockerException;

	String inspectVolume(String name, BooleanSupplier cancelled) throws DockerException;

	/** Export an existing container's merged filesystem to an uncompressed tar file. */
	void exportContainer(String containerId, Path destination, BooleanSupplier cancelled) throws DockerException;

	/** Export an image through a labelled, never-started temporary container. */
	void exportImage(String imageId, Path destination, BooleanSupplier cancelled) throws DockerException;

	/** Best-effort cleanup of old Nuclr-labelled helper containers. */
	void cleanupStaleTemporaryContainers();

	/**
	 * Forget which endpoint Docker is pointed at, so the next call re-reads it.
	 *
	 * <p>A user may run {@code docker context use} while Commander is open. Without this the
	 * plugin would keep answering from the context it saw at startup — and a context that was
	 * remote once would keep the panel dead even after switching back to a local one.
	 */
	void resetEndpoint();

	@Override
	void close();
}
