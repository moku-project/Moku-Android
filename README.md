# Moku Mobile

Manga, novels and anime on Android, with the same library, readers and trackers as Moku on the desktop.
Sources come from Mihon/Tachiyomi- and Aniyomi-format extensions you install yourself, and Tsunagu runs inside
the app, so there's no Moku-operated backend. If you run your own Tsunagu server, the app can use it instead
and download from it for offline reading.

Moku Mobile is in open beta. Expect rough edges, and please report them.

## Install

Download the APK for your phone from [Releases](https://github.com/moku-project/Moku-Android/releases):

- `moku-<version>-arm64-v8a.apk` for almost every phone from the last several years
- `moku-<version>-universal.apk` if you're not sure; it's larger but runs anywhere

Android asks once to allow installs from your browser or file manager. Requires Android 8.0 or newer.

## Updates

Moku checks for a new release twice a day and offers it under Settings › About. Updates install over the
existing app and keep your library, downloads and sign-ins.

## Getting started

1. Settings › Extensions: add a repository URL for the extensions you want to use, then install sources from it.
   Moku ships with none.
2. Discover searches every installed source at once and merges duplicates.
3. Add titles to your library, then read, watch or download them.

To use a Tsunagu server, open Settings › Server and enter its address. Server mode shows the server's library
and sources. Titles you download to the phone from the server stay readable offline, and progress you make
offline reaches the server when it's back.

## Feedback

- [Discord](https://discord.gg/x97hj8zR72) for help and feedback
- [Issues](https://github.com/moku-project/Moku-Android/issues) for bugs

If Moku crashes, the next launch offers to share a crash report (also under Settings). Moku never sends
anything on its own.

## Building

Requires [Nix](https://nixos.org/download) with flakes. The dev shell provides Gradle, JDK 17 and the Android
SDK; there's no Gradle wrapper.

```sh
nix develop

scripts/fetch-tsunagu.sh   # the released Tsunagu core pinned in tsunagu.version
cd android
gradle assembleDebug
gradle installDebug        # with a device or emulator connected over adb
```

Debug builds install next to a release as "Moku Debug" and don't update themselves.

The Tsunagu core comes from a Tsunagu release as `android/app/libs/tsunagu.aar`. To work on Tsunagu itself,
build it from a local checkout (default `../Tsunagu`, or set `TSUNAGU_SRC`) instead:

```sh
scripts/build-tsunagu.sh
```

The x86_64 emulator needs a build with both ABIs (the default); `-Pabis=arm64-v8a` limits it to phones.

```sh
avdmanager create avd -n moku_test -k "system-images;android-36;google_apis_playstore;x86_64"
emulator -avd moku_test
```

## Releasing

Set `versionName` in `android/app/build.gradle.kts`, commit, and push a matching `vX.Y.Z` tag. The release
workflow builds per-ABI and universal APKs, signs them and publishes the release. It needs these repository
secrets: `KEYSTORE_BASE64` (the keystore, base64-encoded), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.
Every release must be signed with the same key, or installed copies can't update.

Don't mark a release as a pre-release: the in-app updater only sees the latest full release.

## Project layout

```
android/app/src/main/kotlin/dev/moku/mobile/
  backend/      Media-source interface with on-device and server implementations
  core/         Embedded Tsunagu (Go core and the extension host it calls), app-wide helpers
  extension/    Extension install and loading
  library/      SQLite library, downloads, content filter rules
  novel/        WebView-hosted runtime for novel plugins
  remote/       Tsunagu server client and session
  repository/   Extension repository bookkeeping
  sync/         Server library, server downloads, offline progress queue
  tracking/     AniList and MyAnimeList
  ui/           Compose screens
  update/       In-app updater
android/app/src/main/kotlin/eu/kanade/tachiyomi/
  Source-API types extensions are compiled against
```

## License

Distributed under the [Apache 2.0 License](./LICENSE).

## Disclaimer

Moku does not host or distribute any content. The developers have no affiliation with any content providers
accessible through connected sources.
