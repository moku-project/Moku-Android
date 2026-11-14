<div align="center">
  <img src="https://raw.githubusercontent.com/moku-project/Moku/main/docs/banner.svg" width="100%" alt="Moku" />
</div>

<div align="center">

[![Release](https://www.shieldcn.dev/github/release/moku-project/Moku-Android.svg?variant=outline&size=default)](https://github.com/moku-project/Moku-Android/releases/latest)
![GitHub Downloads](https://www.shieldcn.dev/github/downloads/moku-project/Moku-Android.svg?variant=outline&size=default)
[![Stars](https://www.shieldcn.dev/github/stars/moku-project/Moku-Android.svg?variant=outline&size=default)](https://github.com/moku-project/Moku-Android)
[![Discord](https://www.shieldcn.dev/discord/members/x97hj8zR72.svg?variant=outline&size=default)](https://discord.gg/x97hj8zR72)

</div>

<br/>

Moku for Android: manga, novels and anime in one library. [Tsunagu](https://github.com/moku-project/Tsunagu) runs inside the app, so nothing else needs to be installed. It can also connect to your own Tsunagu server.

Moku Mobile is in open beta.

Docs: https://moku-project.github.io/

---

## Features

- **Readers**: pages for manga, text for novels, a video player for anime
- **Extensions**: Mihon/Tachiyomi and Aniyomi extension repositories, and LNReader plugins. None are included
- **Discover**: searches all installed sources at once
- **Home**: continue reading, up next, stats and an activity heatmap
- **Downloads**: chapters and episodes for offline use
- **Tracking**: AniList and MyAnimeList
- **Server mode**: use a Tsunagu server's library, sources and downloads, kept separate from the phone's library. Copies kept on the phone stay readable when the server is out of reach, and offline progress syncs when it's back
- **Updates**: checked twice a day and installed over the existing app
- **Crash reports**: offered on the next launch, never sent automatically

---

## Installation

<div align="center">

![Runs on Android](https://www.shieldcn.dev/badge/Runs%20on-Android-3DDC84.svg?logo=android&logoColor=fff)

</div>

Download an APK from the [releases page](https://github.com/moku-project/Moku-Android/releases/latest):

- `moku-<version>-arm64-v8a.apk` for most phones
- `moku-<version>-universal.apk` if unsure (larger, runs on any device)

---

## Requirements

Android 8.0 or newer.

A server is optional. To use one, enter its address under **Settings → Server**.

---

## Development

**Prerequisites:** [Nix](https://nixos.org/download) with flakes. The dev shell provides Gradle, JDK 17 and the Android SDK.

```bash
git clone https://github.com/moku-project/Moku-Android
cd Moku-Android
nix develop
scripts/fetch-tsunagu.sh   # the Tsunagu release pinned in tsunagu.version
cd android
gradle assembleDebug
gradle installDebug        # with a device or emulator connected over adb
```

Debug builds install next to a release as "Moku Debug" and don't update themselves.

To build the core from a local Tsunagu checkout (default `../Tsunagu`, or set `TSUNAGU_SRC`) instead of fetching a release:

```bash
scripts/build-tsunagu.sh
```

The x86_64 emulator needs both ABIs (the default); `-Pabis=arm64-v8a` builds for phones only.

```bash
avdmanager create avd -n moku_test -k "system-images;android-36;google_apis_playstore;x86_64"
emulator -avd moku_test
```

### Releasing

Set `versionName` in `android/app/build.gradle.kts`, commit, and push a matching `vX.Y.Z` tag. The release workflow builds, signs and publishes the APKs using the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Every release must use the same key, or installed copies can't update.

Don't mark a release as a pre-release: the in-app updater only sees the latest full release.

---

## Stack

| | |
|---|---|
| [Kotlin](https://kotlinlang.org) + [Jetpack Compose](https://developer.android.com/compose) | App and UI |
| [Tsunagu](https://github.com/moku-project/Tsunagu) | Embedded core and extension host |
| [OkHttp](https://square.github.io/okhttp/) + [gRPC](https://grpc.io) | Networking and the core bridge |
| [Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer) | Video player |
| [Coil](https://coil-kt.github.io/coil/) | Image loading |
| [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) | Background downloads |

---

## Community

Help and feedback on [Discord](https://discord.gg/x97hj8zR72), bugs on the [issue tracker](https://github.com/moku-project/Moku-Android/issues).

[![Discord](https://www.shieldcn.dev/discord/members/x97hj8zR72.svg?variant=secondary&size=large)](https://discord.gg/x97hj8zR72)

---

## License

Distributed under the [Apache 2.0 License](./LICENSE).

---

## Disclaimer

Moku does not host or distribute any content. The developers have no affiliation with any content providers accessible through connected sources.
