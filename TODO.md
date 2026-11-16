# To-Do

What Moku Mobile still lacks compared to Moku on the desktop, plus known gaps. Grouped by where it shows up in the app.

## Sync
- [ ] Move to a Tsunagu release with Moku Sync
- [ ] Sync screen: name the device first, pair from the nearby list or by code, show the merge preview, scan the QR code Moku desktop shows, install missing extensions
- [ ] Hold a Wi-Fi multicast lock while open (or browse with NSD through `SetSyncDiscovery`) and call `SyncWake` on resume
- [ ] Record reading sessions with `recordReadingSession`; read Home stats and the activity heatmap from `readingStats` instead of the phone
- [ ] Send the page with `updateReadingProgress` so other devices resume on the same page

## Recent
- [ ] History: recently read and watched, from `readingSessions`
- [ ] Updates: check library titles for new chapters/episodes, with notifications

## Discover
- [ ] Browse a single source (popular, latest, the source's filters) next to the merged page
- [ ] Load more pages while scrolling; popular and search only fetch page 1 today
- [ ] Browse by tag or genre
- [ ] Choose languages; multi-language extensions follow the phone's language today

## Series page
- [ ] Cover picker and metadata matching
- [ ] Per-series automation (auto-download new chapters, auto-delete after reading)
- [ ] Source migration (move a title to another source keeping progress)

## Readers
- [ ] Manga: double-page view and saved reader presets
- [ ] Anime: skip intro/outro markers, audio track choice, ASS subtitles, preferred video source

## Library
- [ ] Filter by tag and genre

## Extensions
- [ ] Source settings (each source's preference screens)

## Downloads
- [ ] Download history, storage warnings, moving the download folder

## Trackers
- [ ] Import an AniList/MAL list into the library

## Local files
- [ ] Read CBZ, EPUB, PDF and DOCX from the phone's storage

## Settings
- [ ] Reader, Player, Library and Automation settings pages
- [ ] Ask for notification permission the first time a download or update needs it

## Onboarding
- [ ] First-launch tour

## Server
- [ ] Tracking sheet for server titles (route through RemoteTrackingRepository)
- [ ] Manage the server's extensions from the phone (install/update/enable)
- [ ] Manage the server's settings, password and automation from the phone
- [ ] Show the server's own local files (titles with no extensionId); server mode skips them today
- [ ] Keep covers with downloads: offline covers of server titles only come from the image cache now
- [ ] Server downloads: download a range to the phone in one server request instead of one per chapter
- [ ] Server discovery on the LAN instead of typing the address (the `_moku._tcp` advert points at the sync port, not the API)

## Releases
- [ ] GitHub release workflow for Moku-Android: fetch the Tsunagu core, build per-ABI APKs with -PsplitAbi, sign
      with the release key from secrets, and attach them to a vX.Y.Z release so the in-app updater finds them

## Performance
- [ ] Cache loaded extensions in ExtensionLoader instead of reloading per backend
- [ ] Keep library covers in their own cache so browsing can't evict them
- [ ] R8/minify for release builds
- [ ] Baseline profile for startup and scrolling
