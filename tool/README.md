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

## Test dependency note

The SDK plugin allow-list applies to test configurations too, so `okhttp3:mockwebserver` is not
usable. Use `com.sun.net.httpserver.HttpServer` (JDK) or fake `OkHttpClient` interceptors in tests.

## Upgrading the SDK tag

1. Change `TAG` in `tool/scripts/sdk-worktree.sh` and the default `WORKTREE` name in it and in
   `tool/gradlew-sdk`.
2. Run `tool/scripts/sdk-worktree.sh`, diff `tool/build.gradle.kts` against
   `git -C <worktree> show <tag>:tool/build.gradle.kts`, and re-run the build commands above.
