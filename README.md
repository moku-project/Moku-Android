# Moku Mobile

Native Android manga/anime/novel reader built on the Keiyoushi/Aniyomi extension
model — sources are installed on-device as small extension packages (manga/anime
APKs, JS-based novel plugins) that talk directly to their own sites. There is no
Moku-operated backend; the app also supports connecting to a self-hosted Tsunagu
instance for remote library/tracking sync.

## Stack

- Kotlin + Jetpack Compose (Material3)
- Room (local library/download/tracking storage)
- Keiyoushi/Tachiyomi source-API classes, vendored under `eu.kanade.tachiyomi.*`
- Coil for image loading, Media3 for playback
- Novel sources run as CommonJS plugins inside a headless WebView bridge (`NovelJsRuntime`)

## Requirements

- [Nix](https://nixos.org/download) with flakes enabled
- An Android device or emulator (API 26+)

There's no checked-in Gradle wrapper — the dev shell provides `gradle`, JDK 17,
and the full Android SDK/emulator directly, so nothing needs to be installed
outside of Nix.

## Getting started

```sh
nix develop

scripts/fetch-tsunagu.sh   # the released Tsunagu core pinned in tsunagu.version
cd android
gradle assembleDebug
gradle installDebug   # with a device/emulator connected via adb
```

The app runs Tsunagu in-process: its Go core comes from a Tsunagu release as
`android/app/libs/tsunagu.aar`. To work on Tsunagu itself, build the core from a
local checkout (default `../Tsunagu`, or set `TSUNAGU_SRC`) instead:

```sh
scripts/build-tsunagu.sh
```

The x86_64 emulator needs a build with both ABIs (the default); `gradle
assembleDebug -Pabis=arm64-v8a` limits the APK to phones.

To boot a local emulator instead of using a physical device:

```sh
avdmanager create avd -n moku_test -k "system-images;android-34;google_apis_playstore;x86_64"
emulator -avd moku_test
```

## Project layout

```
android/app/src/main/kotlin/dev/moku/mobile/
  backend/      Unified media-source interface + local/remote implementations
  core/         Embedded Tsunagu: the Go core and the gRPC extension host it calls
  extension/    Keiyoushi-style extension loading/installation (DexClassLoader)
  library/      Room database — library, downloads, content filter rules
  novel/        WebView-hosted JS runtime for CommonJS novel plugins
  remote/       Tsunagu client (optional self-hosted sync backend)
  repository/   On-device extension repository bookkeeping
  tracking/     AniList / MyAnimeList tracker integrations
  ui/           Compose screens (Home, Discover, Library, Downloads, Settings, Reader)
android/app/src/main/kotlin/eu/kanade/tachiyomi/
  Vendored Tachiyomi/Keiyoushi source-API types extensions are compiled against
flake.nix       Nix devShell: Android SDK, JDK 17, emulator, gradle
```

## Status

Early/actively in development. Core reading flow (install a manga extension,
search, add to library, download, read) and the novel JS-plugin bridge work
end-to-end; UI is being iterated on toward a clean, minimal design.
