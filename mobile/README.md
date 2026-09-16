# Pebrel Android connection preview

This is the first native connection implementation, under development. It shares
Pebrel's desktop palettes and uses the maintained Termux grid, Canvas renderer,
IME, selection and gesture code with an injected byte transport.

## Build

Use JDK 17, Gradle 8.13 and Android SDK 35. Gradle installs the pinned NDK
27.2.12479018 when Android SDK license acceptance permits it. From `mobile/android`:

```sh
gradle :terminal-emulator:testDebugUnitTest :terminal-view:testDebugUnitTest \
  :app:testPreviewUnitTest :app:assemblePreview :app:lintPreview
```

The `Mobile connection preview` GitHub Actions workflow runs these commands and
uploads an APK, SHA256SUMS and test reports. Preview uses a separate application
ID and CI's debug signing key; it is not a stable signed update channel.
The workflow's exact commit is the corresponding source revision. Source is
available at https://github.com/Kuddev/pebrel alongside these build instructions.

## Connections in this implementation

- Local: Android `/system/bin/sh` and available system tools. This does not ship
  the Termux package environment, Git/Python/Node or its bootstrap.
- SSH: password authentication, explicit first host-key approval, pinned host
  identity, encrypted PTY, native direct input and a locally edited command box.
  Saved host metadata contains no password. Candidate taps only edit the draft.
- Computer: an SSH server under the same account as the running Pebrel desktop,
  with a build containing `pebrel mobile-bridge` on PATH. Lists existing panes,
  subscribes to semantic task state, reads a bounded output tail and optionally
  sends validated prompts. The CLI bridge defaults to read-only. This is shared
  input, not exclusive takeover; coloured desktop grid streaming is not present.
- Notifications: Android local notifications for observed live desktop task
  transitions. No proprietary server, push service or durable replay is claimed.
- The foreground connection service is opt-in. Android can still terminate it;
  remote process survival requires the server's own session host, such as tmux.

The complete HTML design remains a separate local prototype. QR pairing,
user-hosted WSS relay, durable notification recovery, file/media transfer,
WebDAV and the complete visual design are subsequent implementation work.

## Ownership and performance decisions

- Compose owns navigation and metadata. It never creates a composable per cell.
  The application owns terminal sessions; an Activity only attaches its view.
- Termux owns the grid and escape parser. A `SessionTransport` supplies local PTY
  or SSH bytes. No second parser or predicted terminal echo is introduced.
- Socket/process reads, writes, resizing and closing run away from the UI thread.
  Incoming and outgoing queues are bounded at 64 KiB and 128 KiB. UI writes use
  all-or-nothing offer: congestion preserves the draft and exposes failure.
- VT append stays with the existing main-thread grid owner. Draining is limited
  to 16 KiB per task with an 8 ms continuation delay; these are initial budgets,
  not measured frame-time guarantees. Only the attached session invalidates a
  view. Scrollback starts at 2,000 rows. There is no continuous rendering loop.
- Local command editing is immediate on the device. Direct terminal interaction
  still includes SSH round-trip time; shell passwords and full-screen programs
  do not receive speculative local echo.
- SSHJ is the Android transport dependency. The desktop retains its Rust runtime
  and command authority. The first bridge reuses authenticated SSH exec and the
  loopback API instead of adding a listener or distributing the runtime token.
- The bridge binds to one runtime endpoint per channel, accepts only an allowlist,
  requires explicit window and pane IDs, and bounds requests/responses at 40 KiB /
  2 MiB. It never replays prompts after disconnect. `mobile.ready` declares absent
  features explicitly. A future paired transport must preserve those boundaries.
- Desktop output requests carry target identity and generation. Host persistence
  is serialized. Neither late output nor an older save may overwrite newer state.
- Native visible text uses Android resources (English and Simplified Chinese);
  terminal output is not translated. Theme assets derive from `nebula_settings`,
  retaining the desktop as the single palette authority.

See `android/third_party/termux/UPSTREAM.md` for pinned source, changed files,
licensing exceptions and the adapter's upstream replacement condition.

## Verification boundary

Compilation, unit tests and lint run in CI. Device acceptance still needs Chinese
IME, external keyboard, repeated connection/close, idle/burst output, background
transitions, packet loss and multi-host checks. CI success alone does not establish
real-device latency, battery life, reliable offline delivery or visual acceptance.
