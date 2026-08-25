# TVRelay

[![Latest release](https://img.shields.io/github/v/release/hmartinez94/TVRelay?sort=semver)](../../releases)
[![License: PolyForm Noncommercial](https://img.shields.io/badge/license-PolyForm%20Noncommercial-blue)](LICENSE)

Google TV's home screen recommends a movie, you click it, and it opens whatever app the recommendation happened to come from - usually not the one you actually wanted to watch it in. TVRelay intercepts that click and opens the title in **Nuvio**, **Stremio**, **WuPlay**, or **Jellyfin** instead, with a one-tap confirmation so a stray click never redirects you by accident.

<img src=".github/screenshots/watch-now-overlay.jpg" alt="A Google TV recommendation page for the movie Obsession, with a 'Watch now in Nuvio' button from TVRelay floating over it" width="720">

*A real recommendation page, with TVRelay's confirm button floating over it.*

Free, no account, no subscription, no license check. It doesn't host or provide any content itself; it only reads the title of the thing you clicked, looks that title up, and hands you off to an app you already chose in Settings.

## Contents

- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Installation](#installation)
- [Setup](#setup)
- [Metadata provider](#metadata-provider-tmdb--thetvdb)
- [Limitations](#limitations)
- [Troubleshooting](#troubleshooting)
- [Building from source](#building-from-source)
- [Legal notice](#legal-notice)
- [Credits](#credits)

## How it works

Android's Accessibility API lets an app observe what's on screen for assistive purposes - TVRelay uses it narrowly, watching only for clicks on the Google TV launcher. When a click's payload includes a movie/show title, TVRelay looks it up (via [TMDB](https://www.themoviedb.org/) by default, or optionally TheTVDB) and offers to open it in your chosen player. Every other click - app icons, menus, anything that isn't a recommendation - is left completely alone, and nothing is read, stored, or sent anywhere beyond that one lookup.

Not every recommendation card exposes a title this way; see [Limitations](#limitations).

## Requirements

- A device with the **Google TV** launcher (Chromecast with Google TV, or Google TV editions from Sony, TCL, Hisense, etc.). Fire TV doesn't run this launcher out of the box - see [Limitations](#limitations) for a workaround.
- **Nuvio**, **Stremio**, **WuPlay**, and/or **Jellyfin** installed on the device - whichever one you plan to pick in Settings. See [Limitations](#limitations) for how Jellyfin differs from the other three.

## Installation

TVRelay isn't on Google Play yet - install the APK from this repository's [Releases](../../releases) page.

### Option A: from your phone, using Send Files to TV

1. On the TV, install **[Send Files to TV](https://play.google.com/store/apps/details?id=com.jstenpal.sendfilestotv)** from Google Play.
2. Open the app on the TV. It will show an address or a QR code to connect from your phone.
3. From your phone's browser, go to that address and select the APK downloaded from [Releases](../../releases).
4. Once the file has transferred, the TV will let you start the installation.
5. If Android TV shows a warning about installing from an unknown source, temporarily allow installation from that source.

### Option B: via ADB

1. Download the APK from [Releases](../../releases).
2. Enable developer options on the TV: **Settings → Device Preferences → About → tap "Build" 7 times**.
3. Enable **USB debugging** or **Network debugging**, depending on the device.
4. From your computer, install the app and enable its accessibility service in one go:
   ```
   adb connect <tv-ip>:5555
   adb install app-release.apk
   adb shell appops set com.hmartinez94.tvrelay ACCESS_RESTRICTED_SETTINGS allow
   adb shell settings put secure enabled_accessibility_services com.hmartinez94.tvrelay/com.hmartinez94.tvrelay.TvRelayAccessibilityService
   adb shell settings put secure accessibility_enabled 1
   ```
   The last three lines turn on the permission that lets TVRelay see launcher clicks at all, bypassing a restriction some Android versions place on sideloaded apps (see [Setup](#setup) below) - safe to run on any device, needed or not. The `settings put secure enabled_accessibility_services` line **replaces** the whole list of enabled accessibility services - if you already use another one (like TalkBack), separate them with a colon (`:`) instead of overwriting it.

   With this done, the Accessibility toggle should already show as enabled once you open the app - skip straight to picking a player in [Setup](#setup).

## Setup

<img src=".github/screenshots/settings.png" alt="TVRelay's Settings screen on Google TV, showing the player choice and accessibility/overlay toggles" width="720">

Open **TVRelay** from the TV's launcher, accept the first-run disclosure, and pick your player.

### Enable in Accessibility settings

If you installed via ADB (Option B above) and ran the extra commands there, this is already done. Otherwise, select **"Enable in Accessibility settings"** and turn the service on there - without it, nothing else in this app does anything.

#### The toggle turns itself off immediately

On Android 13+, the system blocks sideloaded apps from turning Accessibility on through the normal toggle, as a security measure - if it switches back off right after you enable it, this is why, not a bug. When this happens, TVRelay adds an **"Accessibility not turning on? Get help"** row to Settings, which walks you through the fix (look for "Allow restricted setting" on the app's system info screen). If your device doesn't have that option, use the ADB commands from [Installation](#installation) instead - **[atvTools](https://play.google.com/store/apps/details?id=dev.vodik7.atvtools)** (free, on Google Play) can run them from your phone if setting up ADB on a computer isn't an option.

On some devices (especially Chinese-manufacturer Android TV boxes with aggressive battery managers), you may also need to exclude TVRelay from any manufacturer "optimizer"/RAM cleaner, or the system will kill the service's process after a few seconds.

### Enable the "Watch now" confirmation

By default, TVRelay shows a **"Watch now in {App}"** button before opening anything - a loading indicator appears the moment you click a recommendation, then swaps to the confirm button once the title's been identified. Dismissing it with Back or a D-pad press only hides it; it reappears a few seconds later and keeps doing so until you return to the launcher's home screen, so a moment's distraction doesn't lose your result (turn this off from Settings → **"Reappear after dismissing"** if you'd rather a dismissal be final).

This needs the **"Display over other apps"** permission, since the button is a small overlay drawn on top of whatever the launcher navigates to. Grant it from Settings → **"Enable 'Watch now' confirmation"**, which takes you straight to the right system screen.

**Without that permission**, TVRelay falls back to opening your chosen app immediately, with no confirmation step - it still works, just without the accidental-click protection.

## Metadata provider (TMDB / TheTVDB)

TVRelay uses **[TMDB](https://www.themoviedb.org/)** by default to identify a clicked title, using a key bundled with the app - no setup needed. If you'd rather use your own personal TMDB key instead (for example, if the shared default key is ever rate-limited), enter one from Settings → **Metadata provider** (get one free at [themoviedb.org](https://www.themoviedb.org/), under Settings → API) - it'll take priority over the bundled one automatically. **TheTVDB** is also available as an alternative provider if TMDB ever gives you a wrong match for a title.

<img src=".github/screenshots/phone-pairing.png" alt="TVRelay's phone-pairing screen, showing a QR code to send a TMDB API key from a phone" width="720">

Typing a 32-character key with a TV remote is painful, so that screen has a **"Send key from phone…"** option: scan the QR code, type or paste the key on the page that opens, and it fills in on the TV automatically within a few seconds - no remote typing required.

## Limitations

- **YouTube video recommendations can optionally redirect to SmartTube or TizenTube Cobalt** (Settings → "Redirect YouTube recommendations", on by default, with a "Target app" dropdown to pick which of the two) instead of being left alone - independent of whichever movie/show player you've picked above. Confirmed working against real clicks. **TizenTube Cobalt is only officially supported if you've also installed [TizenTube Bridge](https://github.com/TobiPeterG/tizentube-bridge):** on its own, TizenTube Cobalt only accepts a search from TVRelay the first time it's opened after being fully closed - once it's already running in the background, the same search silently lands on its home feed instead, and there's no way for TVRelay to force another app to fully restart. TizenTube Bridge requires uninstalling the device's official YouTube app first (often not possible on certified Google TV devices), and isn't confirmed to fix this specific issue either - **SmartTube doesn't have this limitation** and is the safer default if you want the redirect to work reliably every time.
- **Jellyfin opens a search, not the title itself.** It has no way for another app to open a specific title directly, so picking it just opens Jellyfin's own search screen, pre-filled with the title - it'll only find something if it's already on your personal server. Nuvio, Stremio, and WuPlay don't have this limitation: they resolve against their own online catalogs, so they can open a title you don't already have.
- **Fire TV: work in progress.** **The recommended fix is to replace Fire OS's launcher with a custom launcher or the real Google TV launcher instead of looking for any other way to find movies on Fire OS's own home screen** - once that's installed, TVRelay works exactly as it does on a real Google TV device, since it's no longer Amazon's launcher TVRelay has to deal with. This needs temporary root access on the Fire TV Stick, which isn't something TVRelay does or is involved in - it's a device-level change you make yourself, at your own risk, using these guides:
  - [Temp root - Fire TV Stick 4K 2nd Gen (Karat/Mantra series)](https://xdaforums.com/t/temp-root-fire-tv-stick-4k-2nd-gen-series-karat-mantra.4798627/) - grants temporary root access and lets you install a custom launcher.
  - [Install the Google TV launcher on Fire OS 8 (Karat 2nd Gen 4K)](https://xdaforums.com/t/guide-how-to-install-android-tv-google-play-store-on-fire-os-8-karat-2nd-gen-4k.4798990/#post-90707579) - uses that root access to install Google's own launcher in place of Fire OS's.
- **Some recommendation cards, and voice search results, don't expose a title directly** - a real limitation of what the launcher hands third-party apps, not a TVRelay bug, and clicking one does nothing by default. Two ways to still get there:
  - **Search for a title manually** (Settings) - type it yourself; everything after that works exactly like an automatically-detected click.
  - **Screen-reading fallback** (Settings → "Screen-reading fallback for undetectable cards", off by default): reads the title straight off the screen using on-device text recognition when a card or voice-search result has no title in its click event, then continues automatically from there. Nothing captured ever leaves the device, but turning this on means Android shows its own persistent screen-recording indicator the whole time it's active, since it uses the same system permission a screen recorder would - that's a system-level notice, not something TVRelay can hide.
- **The metadata provider can occasionally match the wrong title** if a same-named but different movie or show also exists. When a search returns two or more titles that match *exactly*, TVRelay now asks which one you meant instead of guessing (turn this off from Settings → **"Ask when a match is ambiguous"** if you'd rather it always pick automatically). This can't fully close the gap on its own, though - a genuine data-coverage issue where the provider's catalog simply doesn't have a better candidate at all can still happen. Try [switching provider](#metadata-provider-tmdb--thetvdb) or the manual search with a more specific query (e.g. add the year) if it does.

## Troubleshooting

**Service is enabled, but selecting a recommendation doesn't open anything.** Check that your chosen player is up to date - outdated or unofficial builds (common since none of these are on Google Play) sometimes don't register their deep link scheme correctly. Test the deep link directly, bypassing TVRelay entirely:

```
adb shell am start -a android.intent.action.VIEW -d "nuvio://movie/tt0371746"                                                          # Nuvio, by IMDb id
adb shell am start -a android.intent.action.VIEW -d "nuvio://tmdb/movie/1726"                                                          # Nuvio, by TMDB id (TVRelay's default provider)
adb shell am start -a android.intent.action.VIEW -d "wuplay://movie/tt0371746"                                                         # WuPlay
adb shell am start -a android.intent.action.SEARCH -e query "iron man" -p org.jellyfin.androidtv                                       # Jellyfin (search hand-off)
adb shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/results?search_query=iron+man" -p org.smarttube.stable    # SmartTube
adb shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/results?search_query=iron+man" -p io.gh.reisxd.tizentube.cobalt   # TizenTube Cobalt
```

Each should land on Iron Man's page (or a search for "iron man"). If none of them do, the issue is with the target app's own build, not TVRelay. If they work, the click likely isn't being detected - check for that with:

```
adb logcat -s TvRelayService:D WatchNowOverlay:D
```

## Building from source

Requires Android Studio / JDK 17+ and the Android SDK. Get a free API key from [TheTVDB](https://www.thetvdb.com/dashboard/account/apikeys) and add it to `local.properties` (not committed):

```
TVDB_API_KEY=your_key_here
```

Also get a free key from [TMDB](https://www.themoviedb.org/settings/api) (TMDB is the default provider) and add it the same way:

```
TMDB_API_KEY=your_key_here
```

Leaving `TMDB_API_KEY` blank still produces a working build - TMDB lookups just won't resolve anything until either that's set or a user enters their own key in Settings.

Then:
```
.\gradlew.bat assembleDebug
```
The APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

## Legal notice

TVRelay's function is limited to detecting certain recommendations shown by the device's launcher, identifying the selected content, and opening its page in a third-party app you've already installed and configured yourself - it does not host, store, distribute, or provide any movies, series, streams, torrents, or other audiovisual content, and has no visibility into or control over what those third-party apps and their add-ons actually serve. You're responsible for your own use of them, including making sure that use complies with applicable law and their respective terms of service.

TVRelay is not affiliated with, sponsored by, authorized by, or endorsed by Google, Google TV, Amazon, Fire TV, Nuvio, Stremio, WuPlay, Plex, or Jellyfin. Google, Google TV, Android TV, Amazon, Fire TV, Nuvio, Stremio, WuPlay, Plex, and Jellyfin are trademarks or products of their respective owners.

## Credits

TVRelay uses the **[TMDB](https://www.themoviedb.org/)** API by default to identify movies and shows. This product uses the TMDB API but is not endorsed or certified by TMDB.

If you switch to **[TheTVDB](https://www.thetvdb.com/)** as an alternative provider (see [Metadata provider](#metadata-provider-tmdb--thetvdb) above): this product uses the TheTVDB API but is not endorsed or certified by TheTVDB.

## License

[PolyForm Noncommercial 1.0.0](LICENSE) - free to use, study, modify, and share for noncommercial purposes. Running a fork commercially (including soliciting donations on one) requires the licensor's permission.
