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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Docker filesystem to local-filesystem extraction used by the normal F5 copy action.
 *
 * <p>The work is split in two on purpose. {@link #plan} runs on the event dispatch thread and does
 * everything that has to ask the user something — what is being copied, where to, and whether
 * existing files may be overwritten. {@link #transfer} then runs on a background thread under a
 * progress dialog, so a multi-gigabyte extraction never freezes the window.
 */
@Slf4j
final class DockerCopyService {

	/** Depth fuse for a snapshot whose links defeat the cycle check below. */
	private static final int MAX_DEPTH = 64;

	private final DockerFilesystemManager filesystems;

	DockerCopyService(DockerFilesystemManager filesystems) {
		this.filesystems = filesystems;
	}

	/** What a confirmed copy is going to do. */
	record Plan(List<NuclrResource> sources, Path destination, String label) {
	}

	/**
	 * Validate the request and get the user's confirmation.
	 *
	 * @return the confirmed plan, or {@code null} when the copy cannot or should not run
	 */
	Plan plan(BaseNuclrPlugin other, List<NuclrResource> selectedResources, NuclrResource focusedResource) {

		List<NuclrResource> sources = selected(selectedResources, focusedResource);
		if (sources.isEmpty()) {
			DockerDialogs.error("Copy", "Select a file or directory inside a Docker filesystem.");
			return null;
		}
		if (other == null || other.getCurrentResource() == null) {
			DockerDialogs.error("Copy", "Open a local destination folder in the other panel.");
			return null;
		}
		Path destination = other.getCurrentResource().getPath();
		if (destination == null || !Files.isDirectory(destination)) {
			DockerDialogs.error("Copy",
					"Docker files can currently be copied only to a local filesystem folder.");
			return null;
		}

		String label = sources.size() == 1 ? sources.get(0).getName() : sources.size() + " items";
		if (!DockerDialogs.confirm("Copy", "Copy " + label + " to" + System.lineSeparator()
				+ destination + '?')) {
			return null;
		}

		// Extraction replaces whatever it lands on, so say so before anything is written rather
		// than quietly overwriting a file the user still wanted.
		List<String> existing = sources.stream()
				.map(source -> destination.resolve(source.getName()))
				.filter(Files::exists)
				.map(path -> path.getFileName().toString())
				.toList();
		if (!existing.isEmpty() && !DockerDialogs.confirmDestructive("Copy",
				overwriteMessage(existing, destination))) {
			return null;
		}

		return new Plan(sources, destination, label);
	}

	private static String overwriteMessage(List<String> existing, Path destination) {
		var message = new StringBuilder();
		message.append(existing.size() == 1
				? "This item already exists in " + destination + ':'
				: existing.size() + " items already exist in " + destination + ':');
		existing.stream().limit(10).forEach(name -> message.append(System.lineSeparator()).append(name));
		if (existing.size() > 10) {
			message.append(System.lineSeparator()).append("… and ").append(existing.size() - 10).append(" more");
		}
		message.append(System.lineSeparator()).append(System.lineSeparator())
				.append("Existing files will be replaced.");
		return message.toString();
	}

	/**
	 * Extract a confirmed plan.
	 *
	 * @param callback drives the progress dialog and carries the cancel signal
	 * @return {@code null} when the copy finished or was cancelled, otherwise the failure to report
	 *         once the progress dialog has closed
	 */
	String transfer(Plan plan, NuclrPluginCallback callback) {
		if (callback != null) {
			callback.onStart("Extracting " + plan.label());
		}
		long[] completed = { 0 };
		try {
			for (NuclrResource source : plan.sources()) {
				if (cancelled(callback)) {
					return null;
				}
				copy(source, plan.destination().resolve(source.getName()), callback, completed,
						0, new HashSet<>());
			}
			if (callback != null) {
				callback.onComplete();
			}
			return null;
		} catch (Exception e) {
			if (callback != null) {
				callback.onError(plan.label(), e);
			}
			log.warn("Could not extract {}: {}", plan.label(), e.getMessage(), e);
			return "Could not extract " + plan.label() + ':' + System.lineSeparator() + e.getMessage();
		}
	}

	/**
	 * @param visited resolved paths of the directories already open on this branch, so a link that
	 *                points back into one of them — {@code /usr/bin/X11 -> .} ships in the Debian
	 *                base image — is copied as an empty directory instead of being walked forever
	 */
	private void copy(NuclrResource source, Path target, NuclrPluginCallback callback, long[] completed,
			int depth, Set<String> visited) throws Exception {

		if (depth > MAX_DEPTH) {
			throw new IOException("Too many linked directory levels below " + source.getFullPath());
		}
		if (cancelled(callback)) {
			return;
		}
		if (source.isFolder()) {
			copyDirectory(source, target, callback, completed, depth, visited);
			return;
		}

		Files.createDirectories(target.getParent());
		Path part = Files.createTempFile(target.getParent(), ".nuclr-docker-", ".part");
		try (InputStream input = source.openInputStream()) {
			Files.copy(input, part, StandardCopyOption.REPLACE_EXISTING);
			applyTimestamp(part, source);
			try {
				Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(part);
		}
		progress(callback, ++completed[0]);
	}

	private void copyDirectory(NuclrResource source, Path target, NuclrPluginCallback callback,
			long[] completed, int depth, Set<String> visited) throws Exception {

		Files.createDirectories(target);
		String type = DockerResource.sourceType(source);
		String id = DockerResource.resourceIdForFilesystem(source);
		String resolved = filesystems.resolve(type, id, DockerResource.virtualPath(source),
				() -> cancelled(callback));

		if (!visited.add(resolved)) {
			log.debug("Not descending into {}: it links back to a directory already being copied",
					source.getFullPath());
			progress(callback, ++completed[0]);
			return;
		}
		try {
			for (DockerFileInfo child : filesystems.list(type, id, DockerResource.virtualPath(source),
					() -> cancelled(callback))) {
				NuclrResource childResource = DockerResource.filesystemEntry(type, id,
						DockerResource.sourceLabel(source), child, filesystems);
				copy(childResource, target.resolve(child.name()), callback, completed, depth + 1, visited);
			}
		} finally {
			visited.remove(resolved);
		}
		applyTimestamp(target, source);
		progress(callback, ++completed[0]);
	}

	/** Carry the snapshot's timestamp across, so an extracted tree still reads like the original. */
	private static void applyTimestamp(Path target, NuclrResource source) {
		var modified = source.getLastModifiedDateTime();
		if (modified == null) {
			return;
		}
		try {
			Files.setLastModifiedTime(target,
					FileTime.from(modified.atZone(java.time.ZoneId.systemDefault()).toInstant()));
		} catch (IOException | RuntimeException e) {
			log.debug("Could not set the modified time on {}: {}", target, e.getMessage());
		}
	}

	private static List<NuclrResource> selected(List<NuclrResource> selectedResources,
			NuclrResource focusedResource) {
		List<NuclrResource> candidates = selectedResources != null && !selectedResources.isEmpty()
				? selectedResources : focusedResource == null ? List.of() : List.of(focusedResource);
		var result = new ArrayList<NuclrResource>();
		for (NuclrResource resource : candidates) {
			String kind = DockerResource.kind(resource);
			if ((DockerResource.KIND_FILESYSTEM_FILE.equals(kind)
					|| DockerResource.KIND_FILESYSTEM_DIRECTORY.equals(kind))
					&& !"..".equals(resource.getName())) {
				result.add(resource);
			}
		}
		return result;
	}

	private static boolean cancelled(NuclrPluginCallback callback) {
		return Thread.currentThread().isInterrupted() || callback != null && callback.isCancelled();
	}

	private static void progress(NuclrPluginCallback callback, long completed) {
		if (callback != null) {
			callback.onProgress(completed, -1);
		}
	}
}
