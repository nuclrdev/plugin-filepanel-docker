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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrContextMenuItem;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/** Local Docker Engine presented through Commander's normal FilePanel model. */
@Slf4j
public final class DockerFilePanelPlugin implements FilePanelNuclrPlugin {

	public static final String PLUGIN_ID = "dev.nuclr.plugin.core.panel.docker";

	private static final String PATH_OPENED = "filepanel.path.opened";
	private static final String VIEW = "filepanel.view";
	private static final String COPY = "filepanel.copy";
	private static final String ACCEPT_COPY = "accept.copy";
	private static final String DELETE = "filepanel.delete";
	private static final String REFRESH = "refresh.panel";

	// The panel's Delete key is bound by the commander itself and sends its own action names,
	// so a plugin that only listens for its function-key action leaves that key doing nothing.
	private static final String HOST_DELETE = "delete";
	private static final String HOST_DELETE_PERMANENT = "delete.permanent";

	private static final String START = "docker.container.start";
	private static final String STOP = "docker.container.stop";
	private static final String RESTART = "docker.container.restart";
	private static final String PAUSE = "docker.container.pause";
	private static final String RESUME = "docker.container.resume";
	private static final String INSPECT = "docker.inspect";
	private static final String LOGS = "docker.container.logs";
	private static final String RUN_IMAGE = "docker.image.run";
	private static final String USAGE = "docker.container.usage";
	private static final String CREATE_VOLUME = "docker.volume.create";

	/** Depth fuse for a snapshot whose links defeat the cycle check in {@link #walk}. */
	private static final int MAX_WALK_DEPTH = 64;

	private final String uuid = UUID.randomUUID().toString();
	private final DockerService service;
	private final DockerFilesystemManager filesystems;
	private NuclrPluginContext context;
	private volatile NuclrResource currentResource = DockerResource.root();
	private volatile boolean focused;
	private volatile Thread cleanupThread;

	public DockerFilePanelPlugin() {
		this(new CliDockerService());
	}

	DockerFilePanelPlugin(DockerService service) {
		this.service = service;
		this.filesystems = new DockerFilesystemManager(service);
	}

	@Override
	public String uuid() {
		return uuid;
	}

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
		this.currentResource = DockerResource.root();
		log.info("Docker file panel plugin loaded");
	}

	@Override
	public void init() {
		// Plugin initialization can run on the EDT. Cleanup therefore gets its own virtual thread,
		// and never shows UI if Docker is absent or the daemon is stopped.
		cleanupThread = Thread.ofVirtual().name("nuclr-docker-cleanup").start(() -> {
			DockerFilesystemManager.sweepOrphanedSnapshots();
			service.cleanupStaleTemporaryContainers();
		});
	}

	@Override
	public NuclrPluginContext getContext() {
		return context;
	}

	@Override
	public void unload() {
		Thread cleanup = cleanupThread;
		if (cleanup != null) {
			cleanup.interrupt();
		}
		filesystems.close();
		service.close();
		context = null;
		log.info("Docker file panel plugin unloaded");
	}

	@Override
	public void closeResource() {
		// Filesystem snapshots are reused until F5 refresh or unload; Docker helper containers
		// have already been removed immediately after each image export.
	}

	@Override
	public boolean onFocusGained() {
		focused = true;
		return true;
	}

	@Override
	public void onFocusLost() {
		focused = false;
	}

	@Override
	public boolean isFocused() {
		return focused;
	}

	@Override
	public NuclrResource getCurrentResource() {
		return currentResource;
	}

	@Override
	public boolean supports(NuclrResource resource) {
		return DockerResource.isDockerResource(resource);
	}

	@Override
	public MenuItemsHolder getPluginMenuItems() {
		var item = new MenuItem();
		item.setText("Docker");
		item.setUuid(DockerResource.ROOT_UUID);
		item.setPath(DockerResource.root());
		var holder = new MenuItemsHolder();
		holder.setTitle("Local Docker Engine");
		holder.setMenuItems(List.of(item));
		return holder;
	}

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen, AtomicBoolean cancelled) {
		return openResource(resourceToOpen, cancelled, null);
	}

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen, AtomicBoolean cancelled, EntrySink sink) {
		if (resourceToOpen == null || !supports(resourceToOpen) || cancelled(cancelled)) {
			return null;
		}
		try {
			return switch (DockerResource.kind(resourceToOpen)) {
				case DockerResource.KIND_ROOT -> listRoot(sink);
				case DockerResource.KIND_CATEGORY -> listCategory(
						DockerResource.category(resourceToOpen), cancelled, sink);
				case DockerResource.KIND_CONTAINER, DockerResource.KIND_IMAGE ->
						listFilesystemRoot(resourceToOpen, cancelled, sink);
				case DockerResource.KIND_FILESYSTEM_DIRECTORY -> listFilesystem(resourceToOpen, cancelled, sink);
				case DockerResource.KIND_USAGE -> listUsage(resourceToOpen, cancelled, sink);
				default -> null;
			};
		} catch (DockerException e) {
			if (e.kind() != DockerException.Kind.CANCELLED) {
				log.warn("Docker listing failed: {}", e.getMessage(), e);
				DockerDialogs.error("Docker", e);
			}
			return emptyForCurrent(sink);
		}
	}

	private NuclrResourceData listRoot(EntrySink sink) {
		currentResource = DockerResource.root();
		NuclrResourceData data = data(DockerResource.ROOT_COLUMNS, sink);
		add(data, sink, DockerResource.category(DockerResource.CATEGORY_CONTAINERS));
		add(data, sink, DockerResource.category(DockerResource.CATEGORY_IMAGES));
		add(data, sink, DockerResource.category(DockerResource.CATEGORY_VOLUMES));
		return data;
	}

	private NuclrResourceData listCategory(String category, AtomicBoolean cancelled, EntrySink sink)
			throws DockerException {
		currentResource = DockerResource.category(category);
		BooleanSupplier cancellation = cancellation(cancelled);
		return switch (category) {
			case DockerResource.CATEGORY_CONTAINERS -> {
				List<DockerContainer> containers = service.listContainers(cancellation);
				NuclrResourceData data = data(DockerResource.CONTAINER_COLUMNS, sink);
				add(data, sink, DockerResource.parentToRoot());
				for (DockerContainer container : containers) {
					if (cancelled(cancelled)) break;
					add(data, sink, DockerResource.container(container, filesystems));
				}
				yield data;
			}
			case DockerResource.CATEGORY_IMAGES -> {
				List<DockerImage> images = service.listImages(cancellation);
				NuclrResourceData data = data(DockerResource.IMAGE_COLUMNS, sink);
				add(data, sink, DockerResource.parentToRoot());
				for (DockerImage image : images) {
					if (cancelled(cancelled)) break;
					add(data, sink, DockerResource.image(image, filesystems));
				}
				yield data;
			}
			case DockerResource.CATEGORY_VOLUMES -> {
				List<DockerVolume> volumes = service.listVolumes(cancellation);
				NuclrResourceData data = data(DockerResource.VOLUME_COLUMNS, sink);
				add(data, sink, DockerResource.parentToRoot());
				for (DockerVolume volume : volumes) {
					if (cancelled(cancelled)) break;
					add(data, sink, DockerResource.volume(volume));
				}
				yield data;
			}
			default -> listRoot(sink);
		};
	}

	private NuclrResourceData listFilesystemRoot(NuclrResource source, AtomicBoolean cancelled, EntrySink sink)
			throws DockerException {
		DockerResource root = DockerResource.filesystemDirectory(DockerResource.sourceType(source),
				DockerResource.resourceIdForFilesystem(source), DockerResource.sourceLabel(source), "/", filesystems);
		return listFilesystem(root, cancelled, sink);
	}

	private NuclrResourceData listFilesystem(NuclrResource directory, AtomicBoolean cancelled, EntrySink sink)
			throws DockerException {
		String type = DockerResource.sourceType(directory);
		String id = DockerResource.resourceIdForFilesystem(directory);
		String label = DockerResource.sourceLabel(directory);
		String path = DockerResource.virtualPath(directory);
		currentResource = DockerResource.filesystemDirectory(type, id, label, path, filesystems);
		List<DockerFileInfo> children = filesystems.list(type, id, path, cancellation(cancelled));
		NuclrResourceData data = data(DockerResource.FILE_COLUMNS, sink);
		if ("/".equals(DockerPaths.normalize(path))) {
			String category = DockerFilesystemManager.SOURCE_IMAGE.equals(type)
					? DockerResource.CATEGORY_IMAGES : DockerResource.CATEGORY_CONTAINERS;
			add(data, sink, DockerResource.parentToCategory(category));
		} else {
			add(data, sink, DockerResource.filesystemParent(type, id, label,
					DockerPaths.parent(path), filesystems));
		}
		for (DockerFileInfo child : children) {
			if (cancelled(cancelled)) break;
			add(data, sink, DockerResource.filesystemEntry(type, id, label, child, filesystems));
		}
		return data;
	}

	private NuclrResourceData listUsage(NuclrResource usage, AtomicBoolean cancelled, EntrySink sink)
			throws DockerException {
		String type = DockerResource.usageType(usage);
		String id = DockerResource.resourceId(usage);
		currentResource = DockerResource.usage(type, id, DockerResource.sourceLabel(usage));
		List<DockerContainer> containers = DockerResource.CATEGORY_IMAGES.equals(type)
				? service.containersUsingImage(id, cancellation(cancelled))
				: service.containersUsingVolume(id, cancellation(cancelled));
		NuclrResourceData data = data(DockerResource.CONTAINER_COLUMNS, sink);
		add(data, sink, DockerResource.parentToCategory(type));
		for (DockerContainer container : containers) {
			if (cancelled(cancelled)) break;
			add(data, sink, DockerResource.container(container, filesystems));
		}
		return data;
	}

	private NuclrResourceData emptyForCurrent(EntrySink sink) {
		String kind = DockerResource.kind(currentResource);
		if (DockerResource.KIND_CATEGORY.equals(kind)) {
			String category = DockerResource.category(currentResource);
			List<String> columns = switch (category) {
				case DockerResource.CATEGORY_IMAGES -> DockerResource.IMAGE_COLUMNS;
				case DockerResource.CATEGORY_VOLUMES -> DockerResource.VOLUME_COLUMNS;
				default -> DockerResource.CONTAINER_COLUMNS;
			};
			NuclrResourceData data = data(columns, sink);
			add(data, sink, DockerResource.parentToRoot());
			return data;
		}
		if (DockerResource.KIND_FILESYSTEM_DIRECTORY.equals(kind)) {
			NuclrResourceData data = data(DockerResource.FILE_COLUMNS, sink);
			String type = DockerResource.sourceType(currentResource);
			String category = DockerFilesystemManager.SOURCE_IMAGE.equals(type)
					? DockerResource.CATEGORY_IMAGES : DockerResource.CATEGORY_CONTAINERS;
			add(data, sink, DockerResource.parentToCategory(category));
			return data;
		}
		return data(List.of("Name"), sink);
	}

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResource resource) {
		var items = new ArrayList<NuclrMenuResource>();
		String currentKind = DockerResource.kind(currentResource);
		String resourceKind = DockerResource.kind(resource);
		if (DockerResource.KIND_FILESYSTEM_DIRECTORY.equals(currentKind)) {
			items.add(menu("View", "F3", VIEW));
			items.add(menu("Copy", "F5", COPY));
			addFileSort(items);
			return items;
		}
		if (DockerResource.KIND_CONTAINER.equals(resourceKind)) {
			items.add(menu("Inspect", "F3", INSPECT));
			items.add(menu("Logs", "Shift+F3", LOGS));
			ContainerActions actions = ContainerActions.forState(DockerResource.containerState(resource));
			if (actions.start()) items.add(menu("Start", "Shift+F5", START));
			if (actions.stop()) items.add(menu("Stop", "Shift+F6", STOP));
			if (actions.restart()) items.add(menu("Restart", "Shift+F7", RESTART));
			if (actions.pause()) items.add(menu("Pause", "Shift+F8", PAUSE));
			if (actions.resume()) items.add(menu("Resume", "Shift+F9", RESUME));
			if (actions.delete()) items.add(menu("Delete", "F8", DELETE));
			return items;
		}
		if (DockerResource.KIND_IMAGE.equals(resourceKind)) {
			return List.of(menu("Inspect", "F3", INSPECT), menu("Run", "Shift+F4", RUN_IMAGE),
					menu("Delete", "F8", DELETE));
		}
		if (DockerResource.KIND_VOLUME.equals(resourceKind)) {
			return List.of(menu("Inspect", "F3", INSPECT), menu("Create", "F7", CREATE_VOLUME),
					menu("Delete", "F8", DELETE));
		}
		if (DockerResource.KIND_CATEGORY.equals(currentKind)
				&& DockerResource.CATEGORY_VOLUMES.equals(DockerResource.category(currentResource))) {
			items.add(menu("Create", "F7", CREATE_VOLUME));
		}
		items.add(menu("Name", "Ctrl+F3", "filepanel.sort:name:Name"));
		items.add(menu("Sort", "Ctrl+F12", "filepanel.sort:dialog"));
		return items;
	}

	@Override
	public List<NuclrContextMenuItem> contextMenuItems(NuclrResource focusedResource,
			List<NuclrResource> selectedResources) {
		String kind = DockerResource.kind(focusedResource);
		if (DockerResource.KIND_CONTAINER.equals(kind)) {
			ContainerActions actions = ContainerActions.forState(DockerResource.containerState(focusedResource));
			var items = new ArrayList<NuclrContextMenuItem>();
			items.add(action("Inspect", INSPECT, "info", true, false));
			items.add(action("View Logs", LOGS, "view", true, false));
			items.add(NuclrContextMenuItem.separator());
			items.add(action("Start", START, "play", actions.start(), false));
			items.add(action("Stop", STOP, "stop", actions.stop(), false));
			items.add(action("Restart", RESTART, "refresh", actions.restart(), false));
			items.add(action("Pause", PAUSE, "pause", actions.pause(), false));
			items.add(action("Resume", RESUME, "play", actions.resume(), false));
			items.add(NuclrContextMenuItem.separator());
			items.add(action("Delete", DELETE, "delete", actions.delete(), true));
			return items;
		}
		if (DockerResource.KIND_IMAGE.equals(kind)) {
			return List.of(action("Run / Create Container", RUN_IMAGE, "play", true, false),
					action("Inspect", INSPECT, "info", true, false),
					action("View Container Usage", USAGE, "view", true, false),
					NuclrContextMenuItem.separator(), action("Delete", DELETE, "delete", true, true));
		}
		if (DockerResource.KIND_VOLUME.equals(kind)) {
			return List.of(action("Inspect", INSPECT, "info", true, false),
					action("View Container Usage", USAGE, "view", true, false),
					NuclrContextMenuItem.separator(), action("Delete", DELETE, "delete", true, true));
		}
		if (DockerResource.KIND_FILESYSTEM_FILE.equals(kind)) {
			return List.of(action("View", VIEW, "view", true, false),
					action("Copy", COPY, "copy", true, false));
		}
		if (DockerResource.KIND_FILESYSTEM_DIRECTORY.equals(kind) && !"..".equals(focusedResource.getName())) {
			return List.of(action("Copy", COPY, "copy", true, false));
		}
		if (DockerResource.KIND_CATEGORY.equals(DockerResource.kind(currentResource))
				&& DockerResource.CATEGORY_VOLUMES.equals(DockerResource.category(currentResource))) {
			return List.of(action("Create Volume", CREATE_VOLUME, "folder-add", true, false));
		}
		return List.of();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Commander dispatches every one of these on the event dispatch thread — a context-menu
	 * item, a function key and the Delete key all arrive there synchronously. Docker calls take
	 * anywhere from a moment to a quarter of an hour, so nothing that talks to Docker runs here
	 * directly: each action collects whatever it must ask the user, then hands the work to
	 * {@link DockerProgress}, which runs it on a background thread behind a cancellable dialog.
	 */
	@Override
	public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {

		switch (actionType) {
			case PATH_OPENED, VIEW -> viewIfFile(focusedResource);
			case COPY -> copyOut(other, selectedResources, focusedResource);
			case ACCEPT_COPY -> DockerDialogs.error("Copy",
					"Docker filesystems are read-only. Copying into a container is not supported.");
			case START -> containerOperation("Start", focusedResource, service::startContainer, data);
			case STOP -> containerOperation("Stop", focusedResource, service::stopContainer, data);
			case RESTART -> containerOperation("Restart", focusedResource, service::restartContainer, data);
			case PAUSE -> containerOperation("Pause", focusedResource, service::pauseContainer, data);
			case RESUME -> containerOperation("Resume", focusedResource, service::resumeContainer, data);
			case INSPECT -> inspect(focusedResource);
			case LOGS -> logs(focusedResource);
			case RUN_IMAGE -> runImage(focusedResource, data);
			case USAGE -> openUsage(focusedResource);
			case CREATE_VOLUME -> createVolume(data);
			case DELETE, HOST_DELETE, HOST_DELETE_PERMANENT ->
					delete(selectedResources, focusedResource, data);
			case REFRESH -> refresh();
			default -> log.debug("Docker panel ignoring action [{}]", actionType);
		}
	}

	@FunctionalInterface
	private interface DockerWork {
		void run(BooleanSupplier cancelled) throws DockerException;
	}

	/**
	 * Run Docker work on a background thread behind a progress dialog, reporting any failure once
	 * that dialog has closed so the error is not stacked on top of it.
	 *
	 * @return {@code true} when the work completed without an error
	 */
	private boolean background(String title, DockerWork work) {
		var failure = new AtomicReference<DockerException>();
		DockerProgress.run(title, progress -> {
			try {
				work.run(DockerProgress.cancellation(progress));
			} catch (DockerException e) {
				failure.set(e);
			}
		});
		DockerException error = failure.get();
		if (error == null) {
			return true;
		}
		if (error.kind() != DockerException.Kind.CANCELLED) {
			log.warn("Docker {} failed: {}", title, error.getMessage(), error);
			DockerDialogs.error(title, error);
		}
		return false;
	}

	@FunctionalInterface
	private interface ContainerOperation {
		void apply(String id, BooleanSupplier cancelled) throws DockerException;
	}

	private void containerOperation(String title, NuclrResource resource, ContainerOperation operation,
			Map<String, Object> data) {

		String id = DockerResource.resourceId(resource);
		if (id == null) {
			return;
		}
		if (background(title, cancelled -> operation.apply(id, cancelled))) {
			log.info("Docker container {} completed for {}", title.toLowerCase(Locale.ROOT),
					DockerNames.shortId(id));
		}
		// The container's filesystem has moved on whether or not the command reported success.
		filesystems.invalidate(DockerFilesystemManager.SOURCE_CONTAINER, id);
		requestRefresh(data);
	}

	private void copyOut(BaseNuclrPlugin other, List<NuclrResource> selectedResources,
			NuclrResource focusedResource) {

		var copyService = new DockerCopyService(filesystems);
		DockerCopyService.Plan plan = copyService.plan(other, selectedResources, focusedResource);
		if (plan == null) {
			return;
		}
		var failure = new AtomicReference<String>();
		DockerProgress.run("Copy", progress -> failure.set(copyService.transfer(plan, progress)));
		if (failure.get() != null) {
			DockerDialogs.error("Copy", failure.get());
		}
	}

	private void inspect(NuclrResource resource) {
		String id = DockerResource.resourceId(resource);
		String kind = DockerResource.kind(resource);
		if (id == null) {
			return;
		}
		var json = new AtomicReference<String>();
		boolean completed = background("Inspect", cancelled -> json.set(switch (kind) {
			case DockerResource.KIND_CONTAINER -> service.inspectContainer(id, cancelled);
			case DockerResource.KIND_IMAGE -> service.inspectImage(id, cancelled);
			case DockerResource.KIND_VOLUME -> service.inspectVolume(id, cancelled);
			default -> null;
		}));
		if (completed && json.get() != null) {
			viewText("Inspect " + resource.getName(), safeFileName(resource.getName()) + ".json", json.get());
		}
	}

	private void logs(NuclrResource resource) {
		if (!DockerResource.KIND_CONTAINER.equals(DockerResource.kind(resource))) {
			return;
		}
		String id = DockerResource.resourceId(resource);
		var logs = new AtomicReference<String>();
		if (!background("Logs", cancelled -> logs.set(service.containerLogs(id, cancelled)))) {
			return;
		}
		viewText("Logs — " + resource.getName(), safeFileName(resource.getName()) + ".log",
				logs.get() == null || logs.get().isEmpty()
						? "No logs are available for this container." : logs.get());
	}

	private void runImage(NuclrResource resource, Map<String, Object> data) {
		if (!DockerResource.KIND_IMAGE.equals(DockerResource.kind(resource))) {
			return;
		}
		RunContainerRequest request = DockerDialogs.runImage(DockerResource.resourceId(resource),
				resource.getName());
		if (request == null) {
			return;
		}
		var containerId = new AtomicReference<String>();
		if (!background("Run Image", cancelled -> containerId.set(service.runContainer(request, cancelled)))) {
			// A create that succeeded before a failed start still leaves a container to show.
			requestRefresh(data);
			return;
		}
		requestRefresh(data);
		DockerDialogs.info("Run Image", "Container started: " + DockerNames.shortId(containerId.get()));
	}

	private void openUsage(NuclrResource resource) {
		String kind = DockerResource.kind(resource);
		String type = DockerResource.KIND_IMAGE.equals(kind) ? DockerResource.CATEGORY_IMAGES
				: DockerResource.KIND_VOLUME.equals(kind) ? DockerResource.CATEGORY_VOLUMES : null;
		if (type == null) {
			return;
		}
		navigate(DockerResource.usage(type, DockerResource.resourceId(resource), resource.getName()));
	}

	private void createVolume(Map<String, Object> data) {
		String prompted = DockerDialogs.prompt("Create Volume", "Volume name:");
		if (prompted == null) {
			return;
		}
		String name = prompted.strip();
		if (name.isBlank()) {
			DockerDialogs.error("Create Volume", "Enter a volume name.");
			return;
		}
		if (background("Create Volume", cancelled -> service.createVolume(name, cancelled))) {
			requestRefresh(data);
		}
	}

	private void delete(List<NuclrResource> selectedResources, NuclrResource focusedResource,
			Map<String, Object> data) {

		List<NuclrResource> candidates = selectedResources != null && !selectedResources.isEmpty()
				? selectedResources : focusedResource == null ? List.of() : List.of(focusedResource);
		List<NuclrResource> targets = candidates.stream().filter(resource -> {
			String kind = DockerResource.kind(resource);
			return DockerResource.KIND_CONTAINER.equals(kind) || DockerResource.KIND_IMAGE.equals(kind)
					|| DockerResource.KIND_VOLUME.equals(kind);
		}).toList();
		if (targets.isEmpty()) {
			return;
		}

		StringBuilder message = new StringBuilder("Delete the following Docker resource");
		message.append(targets.size() == 1 ? "?" : "s?");
		for (NuclrResource target : targets) {
			message.append(System.lineSeparator()).append(target.getName());
		}
		message.append(System.lineSeparator()).append(System.lineSeparator())
				.append("Running containers are never force-deleted.");
		if (!DockerDialogs.confirmDestructive("Delete Docker Resource", message.toString())) {
			return;
		}

		var failures = new ArrayList<String>();
		DockerProgress.run("Delete", progress -> failures.addAll(
				deleteAll(targets, DockerProgress.cancellation(progress), progress::onStart)));

		requestRefresh(data);
		if (!failures.isEmpty()) {
			DockerDialogs.error("Delete Docker Resource",
					String.join(System.lineSeparator() + System.lineSeparator(), failures));
		}
	}

	/**
	 * Delete each target, carrying on past the ones Docker refuses.
	 *
	 * <p>One resource refusing to go — a running container, an image another image is built on —
	 * must not silently abandon the rest of the selection, and the panel must still be refreshed
	 * afterwards so the deletions that did happen stop being shown.
	 *
	 * @param announce receives a line of progress per target; may be {@code null}
	 * @return one message per target that could not be deleted, in the order they were tried
	 */
	List<String> deleteAll(List<NuclrResource> targets, BooleanSupplier cancelled, Consumer<String> announce) {
		var failures = new ArrayList<String>();
		for (NuclrResource target : targets) {
			if (cancelled != null && cancelled.getAsBoolean()) {
				break;
			}
			if (announce != null) {
				announce.accept("Deleting " + target.getName());
			}
			try {
				removeOne(target, cancelled);
			} catch (DockerException e) {
				if (e.kind() == DockerException.Kind.CANCELLED) {
					break;
				}
				log.warn("Could not delete {}: {}", target.getName(), e.getMessage(), e);
				failures.add(target.getName() + " — " + e.userMessage());
			}
		}
		return List.copyOf(failures);
	}

	private void removeOne(NuclrResource target, BooleanSupplier cancelled) throws DockerException {
		String kind = DockerResource.kind(target);
		String id = DockerResource.resourceId(target);
		if (DockerResource.KIND_CONTAINER.equals(kind)) {
			service.removeContainer(id, cancelled);
			filesystems.invalidate(DockerFilesystemManager.SOURCE_CONTAINER, id);
		} else if (DockerResource.KIND_IMAGE.equals(kind)) {
			service.removeImage(id, cancelled);
			filesystems.invalidate(DockerFilesystemManager.SOURCE_IMAGE, id);
		} else {
			service.removeVolume(id, cancelled);
		}
	}

	/**
	 * F5 rebuilds what the panel is showing from Docker again.
	 *
	 * <p>That includes which endpoint Docker is pointed at: a user who ran {@code docker context
	 * use} should not have to restart Commander, and a context that was remote when the plugin
	 * first looked would otherwise keep the panel dead for the rest of the session.
	 */
	void refresh() {
		service.resetEndpoint();
		invalidateCurrentFilesystem();
	}

	private void viewIfFile(NuclrResource resource) {
		if (resource != null && !resource.isFolder()
				&& DockerResource.KIND_FILESYSTEM_FILE.equals(DockerResource.kind(resource))) {
			view(resource);
		}
	}

	private void viewText(String title, String fileName, String content) {
		view(DockerResource.text(title, fileName, content));
	}

	private void view(NuclrResource resource) {
		// An action can still finish after unload() has dropped the context.
		NuclrPluginContext current = context;
		if (current != null) {
			current.getEventBus().emit("mainpanel.view", Map.of("resource", resource), null);
		}
	}

	private void navigate(NuclrResource resource) {
		NuclrPluginContext current = context;
		if (current != null) {
			current.getEventBus().emit(this, PATH_OPENED, Map.of("resource", resource));
		}
	}

	private void requestRefresh(Map<String, Object> data) {
		if (data != null) data.put("result.refresh", true);
		if (context != null) {
			context.getEventBus().emit("refresh.plugin.file.panel", Map.of("plugin.uuid", uuid), null);
		}
	}

	private void invalidateCurrentFilesystem() {
		String type = DockerResource.sourceType(currentResource);
		String id = DockerResource.resourceIdForFilesystem(currentResource);
		if (type != null && id != null) filesystems.invalidate(type, id);
	}

	@Override
	public void walkDescendants(NuclrResource resource, Consumer<NuclrResource> visitor,
			AtomicBoolean cancelled, boolean recursive) throws IOException {
		if (resource == null || !resource.isFolder() || DockerResource.sourceType(resource) == null) {
			throw new IOException("Not a Docker filesystem directory.");
		}
		try {
			walk(resource, visitor, cancelled, recursive, 0, new HashSet<>());
		} catch (DockerException e) {
			throw new IOException(e.userMessage(), e);
		}
	}

	/**
	 * @param visited resolved paths of the directories already open on this branch; a link that
	 *                points back into one of them is reported but not descended into, because a
	 *                snapshot really can contain {@code /usr/bin/X11 -> .} and walking that alias
	 *                would repeat the same directory until the depth fuse blew
	 */
	private void walk(NuclrResource directory, Consumer<NuclrResource> visitor, AtomicBoolean cancelled,
			boolean recursive, int depth, Set<String> visited) throws DockerException, IOException {

		if (depth > MAX_WALK_DEPTH) {
			throw new IOException("Too many linked directory levels below " + directory.getFullPath());
		}
		String type = DockerResource.sourceType(directory);
		String id = DockerResource.resourceIdForFilesystem(directory);
		String label = DockerResource.sourceLabel(directory);
		BooleanSupplier cancellation = cancellation(cancelled);
		String resolved = filesystems.resolve(type, id, DockerResource.virtualPath(directory), cancellation);
		if (!visited.add(resolved)) {
			return;
		}
		try {
			for (DockerFileInfo child : filesystems.list(type, id,
					DockerResource.virtualPath(directory), cancellation)) {
				if (cancelled(cancelled)) {
					return;
				}
				NuclrResource entry = DockerResource.filesystemEntry(type, id, label, child, filesystems);
				visitor.accept(entry);
				if (recursive && entry.isFolder()) {
					walk(entry, visitor, cancelled, true, depth + 1, visited);
				}
			}
		} finally {
			visited.remove(resolved);
		}
	}

	@Override
	public String getCurrentLocationDisplayText() {
		String kind = DockerResource.kind(currentResource);
		if (DockerResource.KIND_FILESYSTEM_DIRECTORY.equals(kind)) {
			return "Docker: " + DockerResource.sourceLabel(currentResource)
					+ DockerResource.virtualPath(currentResource);
		}
		if (DockerResource.KIND_CATEGORY.equals(kind)) {
			return "Docker: " + currentResource.getName();
		}
		if (DockerResource.KIND_USAGE.equals(kind)) {
			return "Docker: " + currentResource.getName();
		}
		return "Docker";
	}

	@Override
	public String getWindowTitle() {
		return getCurrentLocationDisplayText();
	}

	@Override
	public String getSelectionSummaryText(List<NuclrResource> selectedResources) {
		if (selectedResources == null || selectedResources.isEmpty()) return getCurrentLocationDisplayText();
		if (selectedResources.size() == 1) {
			NuclrResource only = selectedResources.get(0);
			return only.isFolder() ? only.getName()
					: only.getName() + "   " + DockerResource.formatSize(only.getLength());
		}
		long bytes = selectedResources.stream().mapToLong(resource -> Math.max(0, resource.getLength())).sum();
		return selectedResources.size() + " items selected, " + DockerResource.formatSize(bytes);
	}

	private static NuclrResourceData data(List<String> columns, EntrySink sink) {
		var data = new NuclrResourceData();
		data.setColumnNames(columns);
		if (sink != null) sink.columns(columns);
		return data;
	}

	private static void add(NuclrResourceData data, EntrySink sink, NuclrResource resource) {
		data.getEntries().add(resource);
		if (sink != null) sink.add(resource);
	}

	private static NuclrMenuResource menu(String name, String key, String action) {
		return new NuclrMenuResource(name, key, action);
	}

	private static NuclrContextMenuItem action(String label, String action, String icon,
			boolean enabled, boolean destructive) {
		return NuclrContextMenuItem.builder().label(label).actionType(action).iconKey(icon)
				.enabled(enabled).destructive(destructive).build();
	}

	private static void addFileSort(List<NuclrMenuResource> items) {
		items.add(menu("Name", "Ctrl+F3", "filepanel.sort:name:Name"));
		items.add(menu("Extension", "Ctrl+F4", "filepanel.sort:ext:Extension"));
		items.add(menu("Modified", "Ctrl+F5", "filepanel.sort:modified:Modified"));
		items.add(menu("Size", "Ctrl+F6", "filepanel.sort:size:Size"));
		items.add(menu("Sort", "Ctrl+F12", "filepanel.sort:dialog"));
	}

	private static boolean cancelled(AtomicBoolean cancelled) {
		return Thread.currentThread().isInterrupted() || cancelled != null && cancelled.get();
	}

	private static BooleanSupplier cancellation(AtomicBoolean cancelled) {
		return () -> cancelled(cancelled);
	}

	private static String safeFileName(String name) {
		return name == null ? "docker" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
	}
}
