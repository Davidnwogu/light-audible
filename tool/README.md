# Light Audible tool

Kotlin/Compose tool for the Light Phone III, built against Light SDK **v0.2.3**.

## How the build is wired

The tool is built inside a git worktree of the SDK pinned to `v0.2.3`
(`/Users/davidnwogu/workspace/light-sdk-v0.2.3`). `tool/scripts/sdk-worktree.sh` creates it
(idempotent) and appends this line to the worktree's `settings.gradle.kts`:

    project(":tool").projectDir = file("<this repo>/tool")

Mechanism that worked: the **projectDir override** (the plugin accepts it; no symlink
fallback needed). The signing key is resolved with `rootProject.file("sdk/keys/lightsdk-dev.jks")`.
The owner's SDK checkout is never modified; only `git fetch --tags` and `git worktree add` touch it.

## One-time setup

    tool/scripts/sdk-worktree.sh

`tool/gradlew-sdk` runs this automatically, so it is optional.

## Commands

Always go through `tool/gradlew-sdk` (it passes `-DlightSdk.toolOnly=true`, uses JDK 17 and
`~/Library/Android/sdk`; override with `LIGHT_JAVA_HOME` / `ANDROID_HOME`).

    tool/gradlew-sdk :tool:testDebugUnitTest -DlightSdk.allowAltServerPackage=true
    tool/gradlew-sdk :tool:assembleDebug -DlightSdk.allowAltServerPackage=true

The APK lands in `tool/build/outputs/apk/debug/`. While `serverPackage` is the emulator package,
every invocation (tests included) needs `-DlightSdk.allowAltServerPackage=true`, because the SDK
plugin validates it at configuration time. For a real phone, set `serverPackage = "com.lightos"`
in `lighttool.toml` and drop the flag.

## Install on the emulator

    tool/gradlew-sdk :tool:installDebug -DlightSdk.allowAltServerPackage=true

See the SDK's `docs/system_app` section 9 for emulator setup.

## End-to-end checklist (emulator)

Start the emulator with `-dns-server 8.8.8.8,1.1.1.1` (without it the Library and streaming fail),
pair the tool once, then check each screen against `docs/superpowers/specs/2026-11-09-tool-layout-redesign-design.md` §2:

- **Connect** (only after signing out): small "Audiobooks" title bar, address and code fields, Connect.
- **Library**: top bar with search, "Audiobooks" and refresh; rows are title over authors, left-aligned, never drawn over the top bar.
  - Search: the editor opens; the bar then shows the quoted query, ✕ on the left, search on the right; matches filter the list.
  - Refresh: a "Connecting…" line shows under the top bar, then clears.
  - Now-playing bar: absent with nothing loaded; once a book is loaded it shows "title · chapter" on up to two lines (never cut to a few letters) and a play/pause icon that works without leaving the Library; ♪ marks the row.
- **Player** (tap a book): back, bold title, chapter line, "authors · length", progress bar, ↺15 / ▶ / 15↻ row centred, position, bottom bar with chapters, download and speed.
  - Opened for a book that is not loaded: shows the first or resume chapter and ▶; whatever was playing keeps playing until ▶ is pressed.
  - ▶ on a never-played book: "Preparing…" (can take minutes for a long book), then the chapter line and ⏸.
  - Speed: tap cycles 1x, 1.25x, 1.5x, 2x; the rate applies at once and survives leaving and reopening the Player.
  - Download: tap shows "n%", tap again cancels; a finished download shows ⤓ and a tap asks "Remove download?" (Cancel keeps it).
  - Offline or failed: "Reconnecting…" (⏸ still pauses; skips are disabled), "Couldn't play this book" with "Try again".
  - Disabled controls are drawn lighter and do not respond (for example ↺15 and 15↻ before ▶; refresh while the Library is fetching).
- **Chapters**: back and "Chapters" bar; one-line rows with durations right-aligned in a column; opens scrolled to the current chapter (▸, bold); tapping a row seeks and returns to the Player.
- **Resume after a long pause** (not covered by the quick pass): pause, leave it over 15 minutes, press play on the Library's bar; it should resume.

## Test dependency note

The SDK plugin allow-list applies to test configurations too, so `okhttp3:mockwebserver` is not
usable. Use `com.sun.net.httpserver.HttpServer` (JDK) or fake `OkHttpClient` interceptors in tests.

## Upgrading the SDK tag

1. Change `TAG` in `tool/scripts/sdk-worktree.sh` and the default `WORKTREE` name in it and in
   `tool/gradlew-sdk`.
2. Run `tool/scripts/sdk-worktree.sh`, diff `tool/build.gradle.kts` against
   `git -C <worktree> show <tag>:tool/build.gradle.kts`, and re-run the build commands above.
