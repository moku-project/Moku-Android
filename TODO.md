# To-Do

What Moku Mobile still lacks compared to Moku on the desktop, plus known gaps. Grouped by where it shows up in the app.

## Recent
- [ ] History: recently read and watched, across sources
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
- [ ] Server discovery on the LAN instead of typing the address

## Releases
- [ ] GitHub release workflow for Moku-Android: fetch the Tsunagu core, build per-ABI APKs with -PsplitAbi, sign
      with the release key from secrets, and attach them to a vX.Y.Z release so the in-app updater finds them

## Performance
- [ ] Cache loaded extensions in ExtensionLoader instead of reloading per backend
- [ ] Keep library covers in their own cache so browsing can't evict them
- [ ] R8/minify for release builds
- [ ] Baseline profile for startup and scrolling
