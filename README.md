# TVRelay

[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-support%20this%20project-ffdd00?logo=buy-me-a-coffee&logoColor=black)](https://buymeacoffee.com/hectormtzm6)

Free app for Android TV / Google TV that changes how recommendations behave on the Google TV launcher.

When you select a compatible movie or show card, TVRelay identifies the content and shows a **"Watch now in {App}"** button for whichever of **Nuvio**, **Stremio**, or **Kodi** you've set as your player - tapping it (and only tapping it) opens that title there, so an accidental click never jumps you into another app on its own.

TVRelay is an independent automation and redirection tool. **It does not host, store, distribute, or provide movies, series, streams, torrents, or any other audiovisual content.** It has no relationship with Nuvio, Stremio, Kodi, or the origin or legality of any content you access through those apps - that depends entirely on which apps and add-ons you have installed and configured, under your own responsibility.

TVRelay is completely free, with no subscription, no purchase, and no license checks of any kind. If it's useful to you, there's a Buy Me a Coffee link in the app's Settings and above - entirely optional, and the app works exactly the same either way.

## How it works

The Google TV launcher exposes the title of each recommendation when you select it. TVRelay picks up on that click, looks up the title (via [TheTVDB](https://www.thetvdb.com/) by default, or optionally TMDB - see [Metadata provider](#metadata-provider-thetvdb--tmdb) below), and shows a confirm button for opening it in Nuvio, Stremio, or Kodi.

Not every recommendation card can be detected this way - see [Titles TVRelay can't detect automatically](#titles-tvrelay-cant-detect-automatically).

## Requirements

- A device with the **Google TV** launcher (Chromecast with Google TV, or Google TV editions from Sony, TCL, Hisense, etc.) or a **Fire TV** device.
- **Nuvio**, **Stremio**, and/or **Kodi** installed on the device.

## Installation

TVRelay isn't distributed through Google Play or the Amazon Appstore yet - install the APK from this repository's [Releases](../../releases) page.

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
4. From your computer:
   ```
   adb connect <tv-ip>:5555
   adb install app-release.apk
   ```

### Activating the service

Open **TVRelay** from the TV's launcher, accept the first-run disclosure, then select **"Enable in Accessibility settings"**. Enable the service there.

#### The toggle turns itself off immediately

On Android 13 and newer, the system blocks any sideloaded app (anything not installed from an app store, which currently includes TVRelay) from enabling Accessibility by default, as a security measure. If the toggle switches itself back off right after you enable it - sometimes immediately, sometimes a few seconds later, with no warning - this is why.

On some devices you can lift this from **Settings → Apps → TVRelay → app info screen**, by looking for an option along the lines of "Allow restricted setting" (the exact wording and location varies by manufacturer). If you find it, enable it, then go back into Accessibility and enable the service again.

**If you can't find any such option** (this is the case on the Google TV Streamer, for example), you'll need the ADB workaround instead:

```
adb connect <tv-ip>:5555
adb shell settings put secure enabled_accessibility_services com.hmartinez94.tvrelay/com.hmartinez94.tvrelay.TvRelayAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

This writes the setting directly at the system level, which isn't subject to the same restriction as the Settings app toggle. Note the second command **replaces** the whole list of enabled accessibility services - if you already use another one (like TalkBack), separate them with a colon (`:`) instead of overwriting it.

If setting up ADB from a computer sounds like a hassle, **[atvTools](https://play.google.com/store/apps/details?id=dev.vodik7.atvtools)** (free, on Google Play) does the same thing from your phone.

On some devices (especially Chinese-manufacturer Android TV boxes with aggressive battery managers), you may also need to exclude TVRelay from any manufacturer "optimizer"/RAM cleaner, or the system will kill the service's process after a few seconds.

### Enabling the "Watch now" confirmation

By default, TVRelay shows a **"Watch now in {App}"** button before opening anything - a loading indicator appears the moment you click a recommendation, then swaps to the confirm button once the title's been identified. It disappears on its own after about 10 seconds if you don't tap it, so an accidental click never silently redirects you.

This needs the **"Display over other apps"** permission, since the button is a small overlay drawn on top of whatever the launcher navigates to. Grant it from TVRelay's Settings → **"Enable 'Watch now' confirmation"**, which takes you straight to the right system screen.

**Without that permission**, TVRelay falls back to opening your chosen app immediately, with no confirmation step - it still works, just without the accidental-click protection.

### Setting up Kodi

Kodi has no direct deep-link scheme, so TVRelay talks to it over its local JSON-RPC control interface instead:

1. In Kodi: **Settings → Services → Control → turn on "Allow remote control via HTTP"**, and set a username/password if you'd like.
2. In TVRelay's Settings, open **Kodi connection settings** and enter the host (`127.0.0.1` if Kodi runs on the same device, which is the common case), port (`8080` by default), and credentials.
3. Use **Test connection** to confirm it can reach Kodi before relying on it.

Kodi support only finds titles already present in your local Kodi library - unlike Nuvio/Stremio, which resolve any title against their own online catalogs regardless of local state.

## Metadata provider (TheTVDB / TMDB)

TVRelay uses **[TheTVDB](https://www.thetvdb.com/)** by default to identify a clicked title - no setup needed. If TheTVDB ever gives you a wrong match for a title, TVRelay's Settings → **Metadata provider** lets you switch to **TMDB** instead, using **your own free personal TMDB API key** (get one at [themoviedb.org](https://www.themoviedb.org/), under Settings → API - it has to be your own, not one bundled with the app, since TMDB's terms treat an app profiting from a shared key as commercial use; TVRelay charges nothing, but the key itself is still subject to TMDB's own terms of use).

Typing a 32-character key with a TV remote is painful, so there's a **"Send key from phone…"** option on that screen: it shows a QR code, you scan it with your phone, type or paste the key on the page that opens, and it fills in on the TV automatically within a few seconds - no remote typing required.

## Titles TVRelay can't detect automatically

Not every recommendation card exposes its title to accessibility tools - this is a real limitation of the launcher, not a TVRelay bug, and there's no way to fix it from the app's side. If clicking a recommendation does nothing at all, use Settings → **"Search for a title manually"**: type the title yourself, and everything after that (looking it up, showing the confirm button) works exactly the same way as an automatically-detected click.

## Troubleshooting

**Service is enabled, but selecting a recommendation doesn't open anything.** Check that Nuvio/Stremio is up to date - outdated or unofficial builds (common since neither app is on Google Play) sometimes don't register their deep link scheme (`nuvio://`, `stremio://`) correctly.

To narrow it down (needs ADB access), test the deep link directly, bypassing TVRelay entirely:
```
adb shell am start -a android.intent.action.VIEW -d "nuvio://movie/tt0371746"
```
If that doesn't open Nuvio on Iron Man's page, the issue is with your Nuvio build, not TVRelay. If it works, the click likely isn't being detected - check for that with:
```
adb logcat -s TvRelayService:D TvdbClient:D PlayerLauncher:D KodiClient:D
```

**TCL devices: service stops working after a while.** Some TCL units lock down background auto-start permissions for third-party apps with no toggle exposed in Settings. Fix via ADB:
```
adb shell appops set com.hmartinez94.tvrelay APP_AUTO_START allow
adb shell appops set com.hmartinez94.tvrelay APP_ASSOC_START allow
```

## Known limitations

- Some recommendation rows (personalized/algorithmic ones in particular) don't expose their title to accessibility tools at all - see [Titles TVRelay can't detect automatically](#titles-tvrelay-cant-detect-automatically) above.
- Occasionally, the metadata provider's search may match a lesser-known movie or show with the same name and open the wrong page - this is a data-coverage gap in the provider's own catalog, not something TVRelay's matching logic can fully guard against. Try [switching provider](#metadata-provider-thetvdb--tmdb) or the manual search with a more specific query (e.g. add the year) if it happens.
- Kodi support depends on the title already being in your local Kodi library.

## Building from source

Requires Android Studio / JDK 17+ and the Android SDK. Get a free API key from [TheTVDB](https://www.thetvdb.com/dashboard/account/apikeys) and add it to `local.properties` (not committed):

```
TVDB_API_KEY=your_key_here
```

(A TMDB key is *not* needed to build - that one's entered by each user at runtime in Settings, not baked into the build.)

Then:
```
.\gradlew.bat assembleDebug
```
The APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

## Legal notice

TVRelay is an independent navigation and automation tool for Android TV / Google TV / Fire TV devices.

TVRelay does not host, store, distribute, or provide movies, series, streams, torrents, or audiovisual content sources. Its function is limited to detecting certain recommendations shown by the device's launcher, identifying the selected content, and facilitating the opening of its page through third-party apps installed and configured by the user.

TVRelay does not provide or control the content sources available within those apps. The user is responsible for their use of third-party apps, and for making sure that use complies with applicable law and the corresponding terms of service.

TVRelay is not affiliated with, sponsored by, authorized by, or endorsed by Google, Google TV, Amazon, Fire TV, Nuvio, Stremio, or the Kodi Foundation. Google, Google TV, Android TV, Amazon, Fire TV, Nuvio, Stremio, and Kodi are trademarks or products of their respective owners.

## Credits

TVRelay uses the **[TheTVDB](https://www.thetvdb.com/)** API by default to identify movies and shows. This product uses the TheTVDB API but is not endorsed or certified by TheTVDB.

If you opt into **[TMDB](https://www.themoviedb.org/)** as an alternative provider (using your own key - see [Metadata provider](#metadata-provider-thetvdb--tmdb) above): this product uses the TMDB API but is not endorsed or certified by TMDB.

## License

[PolyForm Noncommercial 1.0.0](LICENSE) - free to use, study, modify, and share for noncommercial purposes. Running a fork commercially (including soliciting donations on one) requires the licensor's permission.
