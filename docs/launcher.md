# The launcher

[Back to the README](../README.md#the-launcher)

A small native home screen for Android TV: **about 572 KB**, plain Java, built for TVs with 1 GB of RAM.
No blur, no video, no network permission, no background services. This page has the details the
README leaves out: settings, languages, remote keys and building it yourself. Every key a theme can
set is in [`themes/README.md`](../themes/README.md#keys), and the opt-in switches with their
screenshots are in [`docs/switches.md`](switches.md).

## Which TVs

The **TV** needs Android 8.0 (API 26) or newer: `minSdk` is a compile-time floor in
[`launcher/app/build.gradle`](../launcher/app/build.gradle), and the install script asks the TV for
`ro.build.version.sdk` before it builds, so an older set is told both numbers instead of failing at
`pm install` (see [Troubleshooting](TROUBLESHOOTING.md) for what is left on such a set).

## Build it yourself

It needs JDK 17 and Android SDK platform 34. On macOS: `brew install openjdk@17 android-commandlinetools`
and `sdkmanager "platforms;android-34" "build-tools;34.0.0"`. On Linux the install script finds
`~/Android/Sdk` and the `java` on your PATH.

## Configure

[`config.xml`](../launcher/app/src/main/res/values/config.xml) sets:

- the dock order, hidden apps and the Live TV package;
- columns (3 wide 16:9 app cards by default; **Auto** picks 3..6 from the app count, and a manual
  choice in Settings wins) and rows under the dock;
  a card is the app's artwork: a TV banner fills it, or a large icon (half the card height) sits
  centred on a tinted card with `fitCenter`, so low-resolution TV icons stay sharp and in proportion.
  Banners carry no caption or scrim; the name is the line under the card, shown on focus (or always
  with `always_show_names`). Only icon tiles print their name inside.
- in the top bar the clock sits at the left edge and the pills at the right, both vertically centred
  in the bar; the network line shows the broadband ISP name by default and only prints the LAN/WAN
  IP lines when **Show IP** is on in the settings; the drawer's panel uses the same opacity as the bar;
- an optional VPN app for the network pill;
- `reduced_motion`, for TVs whose GPU cannot keep up.

Themes set the same keys plus a background colour and a wallpaper.

## Launcher settings and the default home

Click **Settings** for the TV's own settings. Hold OK on it for its menu:

- **Launcher settings**. With `launcher_settings_button` a click opens those instead, and the menu
  leaves the row out.
- **Native settings**, where the TV ships a settings app of its own beside Android's - Xiaomi China,
  TCL/FFALCON and Fire OS are in `settings_fallback_components` - because that app holds pages
  Android's has no row for, such as Xiaomi's Account & Security with the ADB switch.
- **Module cards**, with `module_cards`.
- **Edit dock**, with `dock_row`. A place can also be filled straight from the app library: hold OK on
  a card there and choose **Add to dock**. That matters on firmware where only a handful of apps declare
  a TV entry, since Home lists only those and the settings page or the media player would otherwise have
  no tile to pick.
- **Reorder apps**: OK picks an app up, the arrows move it, Back places it, and Back again finishes.
  With `tile_menu` (on by default since 3.3), holding OK on a tile offers **Move**, which picks that app up straight away.
- **Restart…** while `HomeKeyService` runs, which opens the system power menu (a launcher may not
  restart the TV itself).

The launcher's own page has these rows, top to bottom:

- **Reorder apps**, only with `launcher_settings_button`, the same as the menu's.
- **Show IP** prints the LAN and WAN IP lines in the top bar. Off by default, when the bar shows the
  broadband ISP name (read from the public-IP echo service) and the connection type instead.
- **Apps per row** 3, 4, 5 or 6, or **Auto**, which picks the count from the number of installed apps so
  a small library keeps big cards and a larger one still fits on a screen; **Rows below dock** 0 to 3, how many rows of tiles stay visible under the
  dock band; **Dock band opacity** 100%, 70%, 50% or 30%. The last two only do anything while the dock is
  its own row (`dock_row`, on by default).
- **Clock**: **System** follows the TV's 12- or 24-hour setting; **12-hour** or **24-hour** sets the launcher's
  clocks (Home and the clock screensaver) to that, whatever the TV uses (`clock_format` in a theme).
- **Wallpaper**: one picture from the theme's collection (or its `wallpaper.jpg`), **None**, or **Theme
  default**. A pick holds that picture and stops the rotation until you choose Theme default, press
  Follow theme, or push the theme again.
- **Rotation interval**: **1 hour**, **1 day**, or **Theme**. Picks how often a rotating collection changes
  picture, and with **360 wallpapers** on, how often a new batch is fetched (following the theme keeps the
  old 6-hour throttle). A theme sets the same thing with `wallpaper_rotation_minutes`.
- **360 wallpapers (live)** on or off, and **Refresh wallpaper**, which changes the picture now and fetches
  a fresh batch.
- **★ (top bar)** saves or unsaves the picture currently on screen with one tap. The star turns
  champagne gold while that picture is saved, and follows the rotation as the picture changes.
  Long-press it to open **Wallpaper favourites** without going into settings. Video wallpaper has no
  still to save, but the long-press list can still switch Home back to a saved picture.
- **Favourite this wallpaper** copies the picture currently on screen into a private favourites folder, so
  a later wallpaper refresh cannot delete it. The row reads **Remove this wallpaper from favourites**
  while that picture is saved. Video wallpaper has no still to save.
- **Wallpaper favourites** opens the saved pictures: pick one to hold it on screen, long-press one to
  remove it, or turn on **Favourites only** to rotate through the saved pictures alone. Either choice
  outranks the theme, the Apple stills and 360, and **Follow theme** clears both.
- **Film sources** opens the source list in the same drawer: pick a source for the film app, or scan the
  QR code with a phone on the same Wi-Fi to switch it or add a custom URL. A `.py` uploaded from the
  phone is a config source in its own right: it goes **above every built-in source** (newest upload
  first) and Home switches to it automatically. The small LAN server stays up for the whole Home
  process, so that address still works after the drawer is closed.
- **Films** (top-bar pill) opens the content page, a full-screen wall of real posters from Douban's public
  subject collections (`movie_showing`, `tv_hot`, `tv_animation`, …), cached per collection. Cards show the
  Douban title, subtitle/rating and cover. Tapping a card passes the **title**, not the category, to
  `com.fongmi.android.tv` through `ACTION_SEARCH`; when the film app has no search entry it just launches,
  and when it is not installed Home says so. Home stores no streams and plays nothing — playback happens
  inside the film app. Douban's collection API gives no synopsis or watch progress, so neither is shown.
  Down from the last app row still launches the film app directly: the content page is a separate entry.
- **Animations** on or off, and **Advanced effects** (tile lift, icon zoom and smooth ambient-colour
  transitions; they may use more memory) once animations are on.
- **Open on start**, only with `open_on_start_choice`: Off, or an app that opens instead of Home the first
  time Home comes up after the TV starts. Once per boot, so Home and Back still reach the launcher.
- **Follow theme (reset on-TV choices)**, which clears every choice above. Pushing or resetting a theme
  clears them too.
- **Choose default home** opens Android's home picker, and **TV system settings** the TV's own settings
  page.

**Change the default home:** Launcher settings and the optional Default-home tile
(`show_home_settings_shortcut`) open Android's home picker. Choose the original launcher to stop using
CleanYourTV as Home, or CleanYourTV to return. The original launcher must be enabled; if the install
script disabled it, run the [restore script](../README.md#restoring-your-tv) first. Some TVs provide no
home picker.

## Languages

English, German, Danish, Spanish, Latin American Spanish, Catalan, French, Russian, Serbian (Latin), Polish, Czech, Italian, Portuguese,
Turkish, Arabic, Hebrew, Simplified Chinese, Traditional Chinese (Taiwan), Thai, Finnish, Vietnamese,
Bangla, Indonesian, Norwegian Bokmål, Swedish, Dutch, Uzbek (Latin), Ukrainian, Korean and Asturian. The launcher follows the TV's language. Android 13+ can set it for the launcher
alone: `tools/tv.py 'cmd locale set-app-locales com.gathin.tvhome --locales ca'`.

Hebrew mirrors the layout:

- menu buttons on the left, date and clock on the right with the greeting above;
- dock and app rows starting on the right, with matching remote navigation;
- the input selector's icons and labels on the right;
- dates with the month prefix ב (for example, 19 באוקטובר).

The Clock screensaver right-aligns its date and time. Russian covers every screen, including race
weekends, festival greetings and the Shamsi date. Other languages keep their original layouts.

## Module cards

Any TV app can declare its own tile (label, caption, glow). The launcher asks you once per app, keeps it
hidden by default, and remembers the answer for that app's signing key and card name. Hold OK on Settings
and choose Module cards to change your mind. How to make one: [`docs/modules.md`](modules.md); the
contract: [`docs/specs/card.md`](specs/card.md). Ready-made ones are in [`addons/`](../addons/), and
[cleanyour.tv/addons](https://cleanyour.tv/addons) shows the themes, switches and add-ons together.

## Clock screensaver

Pick "Clock" in the TV's screensaver settings, or over ADB:

```bash
tools/tv.py 'settings put secure screensaver_components com.gathin.tvhome/.ClockDream; settings put secure screensaver_enabled 1'
```

## Remote keys

- An optional accessibility service keeps the remote's **Input** button working on Google TV
  (`INPUT_KEY_SERVICE=1` at install).
- It can bring back other buttons only the stock launcher answered, such as the **YouTube** button
  (`app_keys` and `input_key_keycodes`, in config.xml or a theme; `HOME_KEY_SERVICE=1` reads `app_keys` too).
- A `hold:` prefix on an `app_keys` entry gives that button a second app: a normal press opens the
  unprefixed app, holding for half a second opens the hold one (by keycode or `hold:scan:...`). An entry can
  name an activity (`package/.Activity`), such as an add-on's quick bar, instead of the app's launcher entry.
- In place of an app, an `app_keys` entry can name an action: `back`, `home`, `recents`, `mute`, `sleep`,
  `screensaver`, `settings` or `wifi`, so a spare colour button can mute the TV or, held, put it in standby.
- `HOME_KEY_SERVICE=1` also fixes sticks whose Home key sends ALL_APPS, and opens Settings from the remote's
  **gear** button, even one Android does not map (scancodes or keycodes in config.xml).
- With `settings_key_panel` the gear opens a [small quick settings panel](quick-settings-panel.jpg)
  over Home or any app: TV settings, Display, Network, Apps and Launcher settings.

The full `app_keys` format is in the [`themes/README.md` keys table](../themes/README.md#keys).

## Navigation and artwork

Up and Down move one row. The top buttons never fall through to tiles, and Down from them returns to the
dock tile you came from. The last focused app is remembered, and installing or removing an app refreshes
the tiles while Home is open. App banners load at xhdpi or better, so low-density TV UIs do not downsample
them before the focus zoom.
