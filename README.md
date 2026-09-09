# Nuclr FilePanel — Local Docker

This plugin presents the local Docker Engine as a normal Nuclr Commander source:

```text
Docker
├── Containers
├── Images
└── Volumes
```

Containers and images are navigable, read-only filesystems. Files use the standard Nuclr viewer
and F5 copies them into a local filesystem panel. Docker resource management is exposed through
the normal panel function-key and context-menu action infrastructure.

## Container lifecycle

Start, Stop, Restart, Pause and Resume act on the panel selection, not only on the row under the
cursor: select several containers and one press applies the operation to all of them, exactly as
Delete already behaves. Each operation is offered when at least one selected container is in a
state that allows it, and is then applied only to the containers it is valid for — pressing Start
on a half-running selection starts the stopped ones instead of raising an error for the rest. A
container Docker refuses does not abandon the rest of the selection; the failures are collected
and reported together once the progress dialog closes. Applying an operation to more than one
container asks for confirmation first.

### Function keys

| Key | Command |
|-----|---------|
| F3 | Inspect |
| Shift+F3 | Logs |
| Shift+F5 | Start |
| Shift+F6 | Stop |
| Shift+F7 | Restart |
| Shift+F8 | Pause |
| Shift+F2 | Resume |
| F8 | Delete |

The lifecycle commands sit on the Shift row because it is the only modifier row no desktop claims:
macOS reserves Ctrl+F2–F8 system-wide and Linux window managers take Alt+F7/F8/F10, which is why
Commander carries a stand-in chord for each of those rows and none for this one. Two Shift slots
are still unavailable, so Resume does not continue the run past Pause: F9 is Commander's menu-bar
key under every modifier and is never handed to a plugin, and Shift+F10 is the keyboard
context-menu key on Windows, GTK and Qt alike. On macOS the function row needs *Use F1, F2, etc.
keys as standard function keys* enabled, or fn held, as it does for every Commander plugin.

## Integration design

- `DockerService` is the single Docker boundary. The first version uses Docker CLI commands with
  JSON output and argument vectors; it never parses human table output or builds shell strings.
  The executable can be overridden with the `nuclr.docker.executable` system property or the
  `NUCLR_DOCKER_EXECUTABLE` environment variable, for installations that are not on `PATH`.
- `DockerFilesystemManager` and `TarDockerFilesystem` provide one shared archive-like filesystem
  implementation for running/stopped containers and images. They do not use `docker exec` or
  require a shell or utilities inside an image.
- Image browsing creates a labelled, never-started temporary container, exports its merged
  filesystem, then removes the helper immediately. Helpers carry `dev.nuclr.temporary=true` and
  stale helpers older than 24 hours are cleaned during plugin initialization.
- Commander dispatches plugin actions on the event dispatch thread, so nothing that talks to
  Docker runs there: each action collects its input, then hands the work to `DockerProgress`,
  which runs it on a virtual thread behind a modal, cancellable dialog. Refresh is explicit; the
  plugin does not poll Docker.
- Remote and SSH Docker endpoints are rejected by this local-only implementation. The endpoint is
  re-read on refresh, so `docker context use` takes effect without restarting Commander.

### Snapshot cache

A browsed container or image is exported once to a temporary tar and indexed in a single pass that
records where each entry's bytes begin, so reading a file is a seek rather than another walk of the
archive. Snapshots are bounded — four of them, or 8 GB, whichever comes first — and the least
recently used one is dropped past that. Each export holds only a lock private to its own source, so
a long image export never blocks browsing something else or unloading the plugin. Files left behind
by a run that did not shut down cleanly are swept at startup.

Directory links are followed for display but not for descent: a copy or a recursive walk that
reaches a directory linking back into itself — `/usr/bin/X11 -> .` ships in the Debian base image —
copies it as an empty directory instead of repeating the tree.

## Intentional first-version limits

- Docker filesystems are read-only. Only Docker-to-local copy is supported, and it replaces
  existing files after asking once, rather than prompting per file.
- Volume content browsing is deferred. Docker has no clean archive operation for a volume, and
  helper-container export excludes mounted volume data; the plugin does not pull a utility image
  or depend on shell tools merely to simulate browsing.
- Filesystem views are snapshots produced by `docker container export`. Refresh the panel to
  rebuild a snapshot. Docker export does not include mounted volume contents and some special
  pseudo-filesystems.
- Logs are a snapshot of the latest 10,000 lines rather than a live tail.

## Tests

Run the normal Docker-independent suite with:

```text
mvn test
```

It covers the pure helpers, the tar index and its link handling, the bounded snapshot cache, the
copy and walk cycle guards, and the panel's own action behaviour through an in-memory
`DockerService`. Opt into tests against a running local Docker daemon with
`NUCLR_DOCKER_TESTS=true`.
