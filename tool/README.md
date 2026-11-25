# Light Audible tool

Kotlin/Compose tool for the Light Phone III, built against Light SDK **v0.2.7**.

## How the build is wired

The tool is built inside a git worktree of the SDK pinned to `v0.2.7`
(`/Users/davidnwogu/workspace/light-sdk-v0.2.7`). `tool/scripts/sdk-worktree.sh` creates it
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

    tool/gradlew-sdk :tool:testDebugUnitTest
    tool/gradlew-sdk :tool:assembleDebug

The APK lands in `tool/build/outputs/apk/debug/`. `lighttool.toml` targets a real phone
(`serverPackage = "com.lightos"`).

## Install on the emulator

The emulator runs a different server package. Set
`serverPackage = "com.thelightphone.sdk.emulator"` in `lighttool.toml`, then add
`-DlightSdk.allowAltServerPackage=true` to every command (tests included), because the SDK plugin
validates the package at configuration time:

    tool/gradlew-sdk :tool:installDebug -DlightSdk.allowAltServerPackage=true

Switch it back before building for the phone. See the SDK's `docs/system_app` section 9 for
emulator setup.

## Install on a Light Phone (sideload)

Follows the SDK's `docs/sideloading` ("Installing from your dev environment"). The `uploadTool`
task in `tool/build.gradle.kts` is copied from the SDK's own tool template at v0.2.3 (unchanged through v0.2.7).

One-time setup:

1. Check that `lighttool.toml` has `serverPackage = "com.lightos"` (the default).
2. Turn on developer mode at dashboard.thelightphone.com (Settings → Account → Developer Account).
3. Put the phone on the same Wi-Fi as the computer, start the Tool Manager on the phone, open its
   URL (including the code at the end) in a browser, and choose **Developer**.
4. Under **Developer Keys**, upload `<SDK worktree>/docs/sideloading/light_debug_signing_key.txt`
   (the fingerprint of the SDK dev key that signs this build).
5. Save the browser's Tool Manager key for the command line. In the Tool Manager tab, open DevTools
   (Cmd+Option+J) and run `copy(sessionStorage.getItem("apiKey"))`, then:

       tool/scripts/save-device-key.sh

   and paste when asked (input is hidden). It saves the key to
   `~/.config/light-audible/device-token.txt` (mode 600). The key changes whenever the Tool Manager
   restarts; save it again when uploads fail with HTTP 401. (The SDK docs instead suggest uploading
   an `openssl rand -hex 32` file under **Authentication**; on a production LP3 in November 2026
   that key was rejected with 401.)

Then, for each build (keep the Tool Manager open and the screen on; it stops listening otherwise):

    tool/gradlew-sdk :tool:uploadTool -Pdevice.ip=<phone IP> -Pdevice.token="$(cat ~/.config/light-audible/device-token.txt)"

It builds the debug APK, uploads it and waits until the phone reports the tool installed. Optional:
`-Pdevice.port=` (default 54449), `-Pdevice.timeoutSeconds=` (default 60). Raise `versionCode` if a
newer build is already on the phone; it refuses a lower one.

For the emulator (with the emulator package and flag set as above), run
`adb forward tcp:54449 tcp:54449` first and leave out `device.ip`.

## End-to-end checklist (emulator)

Start the emulator with `-dns-server 8.8.8.8,1.1.1.1` (without it the Library and streaming fail),
pair the tool once, then check each screen against `docs/superpowers/specs/2026-11-09-tool-layout-redesign-design.md` §2:

- **Connect** (only after signing out): small "Audiobooks" title bar, address and code fields, Connect.
- **Library**: top bar with search, "Audiobooks" and refresh; rows are title over authors, left-aligned, never drawn over the top bar.
  - Search: the editor opens; the bar then shows the quoted query, ✕ on the left, search on the right; matches filter the list.
  - Refresh: a "Connecting…" line shows under the top bar, then clears; offline it becomes "Can't reach the server." and the list stays usable.
  - Now-playing bar: absent with nothing loaded; once a book is loaded it shows "title · chapter" on up to two lines (never cut to a few letters) and a play/pause icon that works without leaving the Library; ♪ marks the row.
- **Player** (tap a book): back, bold title, chapter line, "authors · length", progress bar, ↺15 / ▶ / 15↻ row centred, position, bottom bar with chapters, download and speed.
  - Opened for a book that is not loaded: shows the first or resume chapter and ▶; whatever was playing keeps playing until ▶ is pressed.
  - ▶ on a never-played book: "Preparing…" (can take minutes for a long book), then the chapter line and ⏸.
  - Speed: tap cycles 1x, 1.25x, 1.5x, 2x; the rate applies at once and survives leaving and reopening the Player.
  - Download: tap shows "n%", tap again cancels; a finished download shows ⤓ and a tap asks "Remove download?" (Cancel keeps it).
  - Offline or failed: "Reconnecting…" (⏸ still pauses; skips are disabled), "Couldn't play this book" with "Try again".
  - Disabled controls are drawn lighter and do not respond (for example ↺15 and 15↻ before ▶; refresh while the Library is fetching).
- **Chapters**: back and "Chapters" bar; one-line rows with durations right-aligned in a column; opens scrolled to the current chapter (▸, bold); tapping a row seeks and returns to the Player; "Loading…" shows while chapters are fetched, "No chapters" if none came back.
- **Resume after release**: with a book playing, pause it in the Player, press Back to the Library and wait a few seconds (the tool lets go of the player about a second after a pause with no Player open). Press play on the Library's bar: it resumes where it paused. Then pause, go Back, wait again, open the Player, open Chapters and tap a chapter: the position moves there without playing, and ▶ plays from that chapter.

## Test dependency note

The SDK plugin allow-list applies to test configurations too, so `okhttp3:mockwebserver` is not
usable. Use `com.sun.net.httpserver.HttpServer` (JDK) or fake `OkHttpClient` interceptors in tests.

## Upgrading the SDK tag

1. Change `TAG` in `tool/scripts/sdk-worktree.sh` and the default `WORKTREE` name in it and in
   `tool/gradlew-sdk`.
2. Run `tool/scripts/sdk-worktree.sh`, diff `tool/build.gradle.kts` against
   `git -C <worktree> show <tag>:tool/build.gradle.kts`, and re-run the build commands above.
