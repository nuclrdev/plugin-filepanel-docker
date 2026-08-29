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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.OpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import dev.nuclr.platform.plugin.NuclrResource;

/** Every virtual management row and filesystem row exposed by the Docker panel. */
public final class DockerResource extends NuclrResource {

	private static final long serialVersionUID = 1L;

	public static final String ROOT_UUID = "docker://";
	public static final String CATEGORY_CONTAINERS = "containers";
	public static final String CATEGORY_IMAGES = "images";
	public static final String CATEGORY_VOLUMES = "volumes";

	public static final List<String> ROOT_COLUMNS = List.of("Name", "Description");
	public static final List<String> CONTAINER_COLUMNS = List.of(
			"Name", "Container ID", "Image", "State", "Status", "Created", "Ports");
	public static final List<String> IMAGE_COLUMNS = List.of(
			"Name", "Repository", "Tag", "Image ID", "Created", "Size");
	public static final List<String> VOLUME_COLUMNS = List.of(
			"Name", "Driver", "Scope", "Mountpoint", "Labels");
	public static final List<String> FILE_COLUMNS = List.of(
			"Name", "Extension", "Size", "Type", "Modified", "Permissions", "Full Path");

	static final String MARKER = "nuclr.docker.panel";
	static final String KIND = "nuclr.docker.kind";
	static final String KIND_ROOT = "root";
	static final String KIND_CATEGORY = "category";
	static final String KIND_CONTAINER = "container";
	static final String KIND_IMAGE = "image";
	static final String KIND_VOLUME = "volume";
	static final String KIND_FILESYSTEM_DIRECTORY = "filesystem-directory";
	static final String KIND_FILESYSTEM_FILE = "filesystem-file";
	static final String KIND_USAGE = "usage";
	static final String KIND_TEXT = "text";

	static final String CATEGORY = "nuclr.docker.category";
	static final String RESOURCE_ID = "nuclr.docker.resource.id";
	static final String IMAGE_REFERENCE = "nuclr.docker.image.reference";
	static final String CONTAINER_STATE = "nuclr.docker.container.state";
	static final String SOURCE_TYPE = "nuclr.docker.filesystem.source.type";
	static final String SOURCE_ID = "nuclr.docker.filesystem.source.id";
	static final String SOURCE_LABEL = "nuclr.docker.filesystem.source.label";
	static final String VIRTUAL_PATH = "nuclr.docker.filesystem.path";
	static final String USAGE_TYPE = "nuclr.docker.usage.type";
	static final String TEXT_CONTENT = "nuclr.docker.text";

	private static final LocalDateTime EPOCH = LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
	private static final DateTimeFormatter DISPLAY_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private transient DockerFilesystemManager filesystems;

	private DockerResource(DockerFilesystemManager filesystems) {
		super(null);
		this.filesystems = filesystems;
		getMetadata().put(MARKER, Boolean.TRUE);
		setReadable(true);
		setCreatedDateTime(EPOCH);
		setLastModifiedDateTime(EPOCH);
		setLastAccessDateTime(EPOCH);
	}

	public static DockerResource root() {
		DockerResource resource = base(null, KIND_ROOT, ROOT_UUID, "Docker", true);
		resource.getMetadata().put("Description", "Local Docker Engine");
		return resource;
	}

	public static DockerResource parentToRoot() {
		DockerResource resource = root();
		resource.rename("..");
		return resource;
	}

	public static DockerResource category(String category) {
		String label = switch (category) {
			case CATEGORY_CONTAINERS -> "Containers";
			case CATEGORY_IMAGES -> "Images";
			case CATEGORY_VOLUMES -> "Volumes";
			default -> category;
		};
		DockerResource resource = base(null, KIND_CATEGORY, ROOT_UUID + category, label, true);
		resource.getMetadata().put(CATEGORY, category);
		resource.getMetadata().put("Description", switch (category) {
			case CATEGORY_CONTAINERS -> "Running and stopped containers";
			case CATEGORY_IMAGES -> "Local images";
			case CATEGORY_VOLUMES -> "Local volumes";
			default -> "";
		});
		return resource;
	}

	public static DockerResource parentToCategory(String category) {
		DockerResource resource = category(category);
		resource.rename("..");
		return resource;
	}

	public static DockerResource container(DockerContainer container, DockerFilesystemManager filesystems) {
		DockerResource resource = base(filesystems, KIND_CONTAINER,
				ROOT_UUID + "containers/" + container.id(), container.name(), true);
		resource.getMetadata().put(CATEGORY, CATEGORY_CONTAINERS);
		resource.getMetadata().put(RESOURCE_ID, container.id());
		resource.getMetadata().put(CONTAINER_STATE, container.state());
		resource.getMetadata().put(SOURCE_TYPE, DockerFilesystemManager.SOURCE_CONTAINER);
		resource.getMetadata().put(SOURCE_ID, container.id());
		resource.getMetadata().put(SOURCE_LABEL, container.name());
		resource.getMetadata().put(VIRTUAL_PATH, "/");
		resource.getMetadata().put("Container ID", container.shortId());
		resource.getMetadata().put("Image", dash(container.image()));
		resource.getMetadata().put("State", dash(container.state()));
		resource.getMetadata().put("Status", dash(container.status()));
		resource.getMetadata().put("Created", dash(container.createdAt()));
		resource.getMetadata().put("Ports", dash(container.ports()));
		setDockerTimestamp(resource, container.createdAt());
		return resource;
	}

	public static DockerResource image(DockerImage image, DockerFilesystemManager filesystems) {
		String displayName = image.displayName();
		DockerResource resource = base(filesystems, KIND_IMAGE,
				ROOT_UUID + "images/" + image.id() + '/' + displayName, displayName, true);
		resource.getMetadata().put(CATEGORY, CATEGORY_IMAGES);
		resource.getMetadata().put(RESOURCE_ID, image.id());
		resource.getMetadata().put(IMAGE_REFERENCE, displayName);
		resource.getMetadata().put(SOURCE_TYPE, DockerFilesystemManager.SOURCE_IMAGE);
		resource.getMetadata().put(SOURCE_ID, image.id());
		resource.getMetadata().put(SOURCE_LABEL, displayName);
		resource.getMetadata().put(VIRTUAL_PATH, "/");
		resource.getMetadata().put("Repository", noneAsDash(image.repository()));
		resource.getMetadata().put("Tag", noneAsDash(image.tag()));
		resource.getMetadata().put("Image ID", image.shortId());
		resource.getMetadata().put("Created", dash(image.createdAt()));
		resource.getMetadata().put("Size", dash(image.size()));
		setDockerTimestamp(resource, image.createdAt());
		return resource;
	}

	public static DockerResource volume(DockerVolume volume) {
		// Volume browsing is intentionally not claimed as a directory until a helper-image policy
		// can be provided without silently pulling images or depending on a shell.
		DockerResource resource = base(null, KIND_VOLUME,
				ROOT_UUID + "volumes/" + volume.name(), volume.name(), false);
		resource.getMetadata().put(CATEGORY, CATEGORY_VOLUMES);
		resource.getMetadata().put(RESOURCE_ID, volume.name());
		resource.getMetadata().put("Driver", dash(volume.driver()));
		resource.getMetadata().put("Scope", dash(volume.scope()));
		resource.getMetadata().put("Mountpoint", dash(volume.mountpoint()));
		resource.getMetadata().put("Labels", dash(volume.labels()));
		return resource;
	}

	public static DockerResource usage(String usageType, String id, String label) {
		DockerResource resource = base(null, KIND_USAGE,
				ROOT_UUID + usageType + "/usage/" + id, label + " — Containers", true);
		resource.getMetadata().put(USAGE_TYPE, usageType);
		resource.getMetadata().put(RESOURCE_ID, id);
		resource.getMetadata().put(SOURCE_LABEL, label);
		return resource;
	}

	public static DockerResource filesystemDirectory(String sourceType, String sourceId, String sourceLabel,
			String path, DockerFilesystemManager filesystems) {
		String normalized = DockerPaths.normalize(path);
		String name = "/".equals(normalized) ? sourceLabel : DockerPaths.name(normalized);
		DockerResource resource = base(filesystems, KIND_FILESYSTEM_DIRECTORY,
				filesystemUuid(sourceType, sourceId, normalized), name, true);
		filesystemMetadata(resource, sourceType, sourceId, sourceLabel, normalized);
		fileColumns(resource, new DockerFileInfo(normalized, name, true, false, 0, null, 0755));
		return resource;
	}

	public static DockerResource filesystemEntry(String sourceType, String sourceId, String sourceLabel,
			DockerFileInfo info, DockerFilesystemManager filesystems) {
		DockerResource resource = base(filesystems,
				info.folder() ? KIND_FILESYSTEM_DIRECTORY : KIND_FILESYSTEM_FILE,
				filesystemUuid(sourceType, sourceId, info.path()), info.name(), info.folder());
		resource.setLink(info.link());
		resource.setLength(info.folder() ? 0 : Math.max(0, info.size()));
		if (info.modified() != null) {
			LocalDateTime modified = LocalDateTime.ofInstant(info.modified(), ZoneId.systemDefault());
			resource.setCreatedDateTime(modified);
			resource.setLastModifiedDateTime(modified);
			resource.setLastAccessDateTime(modified);
		}
		filesystemMetadata(resource, sourceType, sourceId, sourceLabel, info.path());
		fileColumns(resource, info);
		return resource;
	}

	public static DockerResource filesystemParent(String sourceType, String sourceId, String sourceLabel,
			String parentPath, DockerFilesystemManager filesystems) {
		DockerResource resource = filesystemDirectory(sourceType, sourceId, sourceLabel, parentPath, filesystems);
		resource.rename("..");
		resource.getMetadata().put("Size", "Up");
		return resource;
	}

	public static DockerResource text(String title, String fileName, String content) {
		DockerResource resource = base(null, KIND_TEXT,
				ROOT_UUID + "text/" + java.util.UUID.randomUUID(), fileName, false);
		resource.setFullPath(title);
		resource.getMetadata().put(TEXT_CONTENT, content == null ? "" : content);
		return resource;
	}

	@Override
	public InputStream openInputStream(OpenOption... options) throws Exception {
		if (KIND_TEXT.equals(kind(this))) {
			return new ByteArrayInputStream(getMetadata(TEXT_CONTENT, "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		if (!KIND_FILESYSTEM_FILE.equals(kind(this)) || filesystems == null) {
			return super.openInputStream(options);
		}
		// Reading a file may have to export the whole snapshot first; an interrupt is the only
		// cancel signal this fixed SDK signature can carry, so at least honour that one.
		return filesystems.open(sourceType(this), resourceIdForFilesystem(this), virtualPath(this),
				() -> Thread.currentThread().isInterrupted());
	}

	public static boolean isDockerResource(NuclrResource resource) {
		return resource != null && resource.getMetadata(MARKER, Boolean.FALSE);
	}

	static String kind(NuclrResource resource) {
		return resource == null ? null : resource.getMetadata(KIND, (String) null);
	}

	static String category(NuclrResource resource) {
		return resource == null ? null : resource.getMetadata(CATEGORY, (String) null);
	}

	static String resourceId(NuclrResource resource) {
		return resource == null ? null : resource.getMetadata(RESOURCE_ID, (String) null);
	}

	static String containerState(NuclrResource resource) {
		return resource == null ? "" : resource.getMetadata(CONTAINER_STATE, "");
	}

	static String sourceType(NuclrResource resource) {
		return resource == null ? null : resource.getMetadata(SOURCE_TYPE, (String) null);
	}

	static String resourceIdForFilesystem(NuclrResource resource) {
		return resource == null ? null : resource.getMetadata(SOURCE_ID, (String) null);
	}

	static String sourceLabel(NuclrResource resource) {
		return resource == null ? "" : resource.getMetadata(SOURCE_LABEL, "");
	}

	static String virtualPath(NuclrResource resource) {
		return resource == null ? "/" : resource.getMetadata(VIRTUAL_PATH, "/");
	}

	static String usageType(NuclrResource resource) {
		return resource == null ? null : resource.getMetadata(USAGE_TYPE, (String) null);
	}

	private static DockerResource base(DockerFilesystemManager filesystems, String kind,
			String uuid, String name, boolean folder) {
		DockerResource resource = new DockerResource(filesystems);
		resource.setUuid(uuid);
		resource.setFullPath(uuid);
		resource.setFolder(folder);
		resource.getMetadata().put(KIND, kind);
		resource.rename(name);
		return resource;
	}

	private static void filesystemMetadata(DockerResource resource, String sourceType, String sourceId,
			String sourceLabel, String path) {
		resource.getMetadata().put(SOURCE_TYPE, sourceType);
		resource.getMetadata().put(SOURCE_ID, sourceId);
		resource.getMetadata().put(SOURCE_LABEL, sourceLabel);
		resource.getMetadata().put(VIRTUAL_PATH, DockerPaths.normalize(path));
		resource.setFullPath("docker://" + sourceType + '/' + sourceId + DockerPaths.normalize(path));
	}

	private static void fileColumns(DockerResource resource, DockerFileInfo info) {
		resource.getMetadata().put("Extension", info.folder() ? "" : extension(info.name()));
		resource.getMetadata().put("Size", info.folder() ? "" : formatSize(info.size()));
		resource.getMetadata().put("Type", info.folder() ? (info.link() ? "Folder link" : "Folder")
				: (info.link() ? "File link" : "File"));
		resource.getMetadata().put("Modified", info.modified() == null ? "-"
				: DISPLAY_STAMP.format(LocalDateTime.ofInstant(info.modified(), ZoneId.systemDefault())));
		resource.getMetadata().put("Permissions", permissions(info.mode()));
		resource.getMetadata().put("Full Path", info.path());
	}

	private static String filesystemUuid(String sourceType, String sourceId, String path) {
		return ROOT_UUID + sourceType + '/' + sourceId + DockerPaths.normalize(path);
	}

	private static void setDockerTimestamp(DockerResource resource, String raw) {
		if (raw == null || raw.isBlank()) {
			return;
		}
		try {
			Instant instant = ZonedDateTime.parse(raw,
					DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z z", Locale.ENGLISH)).toInstant();
			LocalDateTime local = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
			resource.setCreatedDateTime(local);
			resource.setLastModifiedDateTime(local);
		} catch (RuntimeException ignored) {
			// The raw Docker timestamp remains available in the visible metadata column.
		}
	}

	private void rename(String name) {
		setName(name);
		getMetadata().put("Name", name);
	}

	private static String dash(String value) {
		return value == null || value.isBlank() ? "-" : value;
	}

	private static String noneAsDash(String value) {
		return value == null || value.isBlank() || "<none>".equals(value) ? "-" : value;
	}

	private static String extension(String name) {
		int dot = name == null ? -1 : name.lastIndexOf('.');
		return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	public static String formatSize(long bytes) {
		if (bytes < 1024) {
			return Math.max(0, bytes) + " B";
		}
		String[] units = { "KB", "MB", "GB", "TB", "PB" };
		double value = bytes;
		int unit = -1;
		do {
			value /= 1024.0;
			unit++;
		} while (value >= 1024 && unit < units.length - 1);
		return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
	}

	static String permissions(int mode) {
		char[] output = new char[9];
		String symbols = "rwx";
		for (int index = 0; index < 9; index++) {
			output[index] = (mode & 1 << (8 - index)) != 0 ? symbols.charAt(index % 3) : '-';
		}
		return new String(output);
	}
}
