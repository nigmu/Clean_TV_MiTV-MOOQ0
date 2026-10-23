# CleanYourTV

A small home screen for this Xiaomi Mi TV, plus a list of preloaded packages that can be turned off and put back. The home app is [`launcher/`](launcher/) (`com.gathin.tvhome`). Packages are disabled for user 0. [`tools/restore.ps1`](tools/restore.ps1) (or [`tools/restore.sh`](tools/restore.sh)) puts the same list back.

https://github.com/user-attachments/assets/77cdd963-8ade-4535-b4e9-9d78055cdc3e

60-second before and after: stock PatchWall, then the replacement home on this set. The file in the repo is [`shots/before-after-60s-white.mp4`](shots/before-after-60s-white.mp4).

The procedure for a coding agent is [PROMPT.md](PROMPT.md). The rules that agent follows are in [AGENTS.md](AGENTS.md). When the set misbehaves, see [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

## This set

| | |
|---|---|
| TV | Xiaomi Mi TV (MiTV-MOOQ0, croods) |
| System | Android TV 10, firmware `QTG3.200305.006.3270` |
| Board | MediaTek m7632, 1.8 GB RAM, Android low-RAM mode on |
| Also applied | Firmware `QTG3.200305.006.5135` (Vietnam retail unit, 2026-10) |
| Debloat list | [`examples/xiaomi-mitv-mooq0/debloat.txt`](examples/xiaomi-mitv-mooq0/debloat.txt) |
| List applied on this computer | `work/mitv-mooq0-session.txt` |
| Dock | Live TV, YouTube, VLC, Spotify |

The shared list is the one in `examples/`. The file under `work/` is the copy that was applied here. Both stay out of a factory reset: disabling is per user, and a firmware update can turn packages back on. [`tools/check.py`](tools/check.py) compares the TV with the list after an update.

On the Vietnam unit the remote’s Input / Source button sends `KEYCODE_MEDIA_RECORD`, so [`InputKeyService`](launcher/app/src/main/java/com/gathin/tvhome/InputKeyService.java) needs that keycode in `input_key_keycodes` before the button opens the Inputs menu. `KEYCODE_TV` and `KEYCODE_TV_INPUT` are already handled. A key injected over ADB does not reach that service; the real remote is the check.

`com.duokan.factorytest` also declares HOME, at the same priority as a replacement launcher. The list disables it, and the install script sets `com.gathin.tvhome` as Home before the stock launcher is touched. The stock launcher (`com.google.android.tvlauncher`) is left out of the debloat list on purpose.

Live TV (`com.mediatek.wwtv.tvcenter`) stays enabled. Its activity is also the HDMI and composite window, so the launcher hides it from the grid when you want it only inside Inputs.

## What you get

### Home

- A time-of-day greeting, a clock and the date. An optional name can follow the greeting. The Solar Hijri date can sit above the Gregorian one.
- A dock of pinned apps, with the focused app’s name in large type and a glow in that app’s colour. Free dock slots can stay empty or fill from the grid.
- The rest of the launchable apps in a grid, from one to eight columns. Rows can sit under the dock, and tiles shrink to fit the screen.
- Inputs, Wi-Fi or Ethernet, and Settings along the top. Each can be a labelled pill or a round icon. The three can fold into one gear menu.
- Reordering from the Settings button’s long-press menu, with an optional reorder button in the top bar.
- Hold OK on a tile for App info, or, when the tile menu is on, Move, Remove from Home and App info.
- An app library of every installed app, with add and remove, when `app_library` is on. Choices stay on the TV across theme pushes.
- Launcher settings on the TV: apps per row, rows below the dock, dock opacity, wallpaper, animations, advanced effects, and Follow theme.
- One optional status line under the clock: CPU, free memory and free storage.
- A clock screensaver that starts only after Home has sat idle, and only from Home.
- Module cards: an app that offers a card is asked once, and the answer can be changed later.

### Look

Themes live in [`themes/`](themes/). A theme is a `config.json`, an optional wallpaper, and a screenshot from a real TV. Pushing one changes the dock, layout and colours without rebuilding the APK. The launcher reads it over the built-in [`config.xml`](launcher/app/src/main/res/values/config.xml) the next time Home starts. Choices made in Launcher settings win until Follow theme, the next theme push, or `tools/theme.ps1 -Reset`.

| Theme | Look |
|---|---|
| [`default/`](themes/default/) | The built-in look, written out as JSON |
| [`aurora/`](themes/aurora/) | Dark aurora wallpaper, teal and violet on navy |
| [`glass/`](themes/glass/) | Cinematic shelf, frosted dock, icon buttons, glow in the app’s colour |
| [`scenes/`](themes/scenes/) | 14 dark landscapes, a new one every hour |
| [`six-columns/`](themes/six-columns/) | Six smaller tiles per row, one grid row under the dock |
| [`dussehra/`](themes/dussehra/) | Festive skin through 21 Oct 2026: toran, rangoli, diya, bell, marigold, petal shower |

Every key is optional. A missing key keeps the `config.xml` value, a bad key is ignored, and a file that does not parse is ignored as a whole, so a theme cannot break Home. The full key list, wallpaper rules and motion notes are in [themes/README.md](themes/README.md).

Wallpapers are dark, 1920×1080 and kept small: this class of set has about 1 GB of RAM, and this board has 1.8 GB. A folder of pictures can rotate on a timer, in order or shuffled, with an optional dim and captions. The launcher stores the last picture and resumes from it. There is no download of those theme pictures, and no extra network permission for them.

With an `UNSPLASH_ACCESS_KEY` in `.env`, Home can also keep a local set of Nature photos from Unsplash. An empty key fetches nothing. A theme file cannot turn that path on or off; it is read from `config.xml` only.

Other looks the same keys already carry: a time-of-day sky, a cinematic top shelf with “Last opened”, frosted-glass dock, horizon or radial glow, reduced motion for a weak GPU, and festive skins (`dussehra`, `diwali`) that turn themselves off after `festival_until`.

### Inputs, remote and apps

- The Inputs menu lists HDMI, composite and Live TV. On this board those ports are served by `com.mediatek.tvinput`, which the list leaves enabled.
- The network pill opens Wi-Fi settings. Point `vpn_app` at a VPN package and the pill opens that app, with a red “VPN off” badge while no VPN is up.
- Settings opens the TV’s own settings. Xiaomi’s settings activity is the first fallback in `config.xml`.
- Optional quick-settings panel for a spare remote button: TV settings, Display, Network, Apps (re-enable) and Launcher settings.
- Optional accessibility helpers, off unless the install is run with `-InputKeyService` or `-HomeKeyService`: the Input button, a Home button that the stock launcher used to catch, and app buttons (`app_keys`, including long-press).
- `boot_to_live_tv` opens Live TV on the first Home after power-on, once per boot.
- An F1 race-weekend panel can sit under the clock. [`tools/race_calendar.py`](tools/race_calendar.py) writes the season into a theme on this computer. The launcher itself stays offline.

### What the list turns off

Grouped in [`examples/xiaomi-mitv-mooq0/debloat.txt`](examples/xiaomi-mitv-mooq0/debloat.txt). Comment out any line you still use.

- Xiaomi telemetry and analytics, automatic bug-report upload, and Google feedback
- PatchWall, Mi recommendation channels, promo web content, the floating promo frame, and partner auto-install config
- TV Manager, the Alexa bridge, the dormant Aha duplicate, Mi Gallery, the MiDream screensaver, and the Google ambient screensaver
- Handbook, TV lock, legal screens, the retail demo loop, Mi Music, and Play Games
- JioTV and YouTube Kids, from this owner’s checklist
- The factory test app that also claims HOME, and the stock launcher’s recommendation plumbing

Left enabled on purpose, after `dumpsys package`: Bluetooth remote pairing, the remote hotkey service (including Source), HDMI 1–3, composite, the tuner, the channel-setup wizard, and the core Mi TV service. Play Store and Play Services stay. The firmware updater, voice assistant, Chromecast, Mi Cast, Miracast and the USB players are in the list as comments. The updater is better left enabled.

Checked on this firmware after the list: HDMI 1–3, composite, the tuner, the Bluetooth remote, Gboard, Play Store, Chromecast, Miracast and USB video playback.

## Safety

- One package per call, `pm disable-user --user 0`. There is no `pm uninstall` in the list or the scripts, and nothing is rooted or flashed.
- [`tools/disable.ps1`](tools/disable.ps1) records the previous state in `~/.cleanyourtv/` before the first change. [`tools/restore.ps1`](tools/restore.ps1) replays that record. A later run does not overwrite the original record.
- The scripts refuse Android’s core packages (system UI, settings, the package installer, Play Services modules, and the providers the system needs to boot). There is no switch that overrides that refusal.
- The stock launcher is disabled only after the new home is installed, set as Home, and started. A failed install leaves the stock launcher enabled.
- On a CVTE board the stock CVTE launcher stays enabled unless that board’s own example says otherwise. This Mi TV is not a CVTE board.
- Factory and test packages can be disabled. They are never launched. On some boards opening one sets a factory-mode flag the shell cannot clear.
- Streaming apps, the voice assistant, Chromecast, Miracast and the firmware updater belong in one checklist. The commented lines at the bottom of this list are that checklist.
- Disable in small batches. [`tools/tv.py --screenshot`](tools/tv.py) saves a frame between them. Live TV, HDMI and some streaming apps return an empty frame; that is the TV.
- When you are finished, turn USB debugging and network ADB off, revoke authorisations, reboot, and run [`tools/adb-closed.py`](tools/adb-closed.py). Port 5555 is plaintext.
- Backups (`backup/`), screenshots (`shots/`), notes (`work/`) and the ADB key (`tools/adbkey/`) stay on this computer. The 60-second demo, `shots/before-after-60s-white.mp4`, is the one file from `shots/` that is kept with the repo.

If `pm disable-user` is refused for every package, a sideload fails verification, or the TV’s SDK is below the launcher’s minimum, stop. The troubleshooting page says what is left to do.

## Quick start

From the repo root, with network debugging on and the TV’s pairing prompt accepted. JDK 17 and Android SDK platform 34 are required for the launcher build. The Windows install script uses Android Studio’s bundled JDK when `JAVA_HOME` is unset.

```powershell
python -m venv tools\venv
tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt

$env:TV_HOST = '<tv ip>'
tools\venv\Scripts\python.exe -I tools\tv.py --probe

tools\disable.ps1 -TvHost $env:TV_HOST examples\xiaomi-mitv-mooq0\debloat.txt
launcher\build-and-install.ps1 -TvHost $env:TV_HOST
tools\theme.ps1 -TvHost $env:TV_HOST themes\aurora
```

`--probe` prints the model, Android version and firmware build. Quote those if you share notes. Leave serial numbers, Android IDs, MAC addresses and account names out.

The shell scripts are the same steps on macOS and Linux: `tools/disable.sh`, `launcher/build-and-install.sh`, `tools/theme.sh`. Pass the TV with `TV_HOST`.

Optional flags on the install script, repeated on every install that should keep them:

| Flag | Effect |
|---|---|
| `-InputKeyService` | The remote’s Input button opens the Inputs menu, and `app_keys` can launch an app |
| `-HomeKeyService` | A Home button the stock launcher used to catch opens this home |
| `-RestoreKeyServices` | After a force-stop, Home puts those helpers back on the accessibility list |
| `-StockLauncher <package>` | Disable this package as the old home. `none` leaves it alone |

The script detects the current home when `-StockLauncher` is omitted. It accepts `com.google.android.tvlauncher`, `com.google.android.apps.tv.launcherx`, `com.google.android.leanbacklauncher` and `com.mitv.tvhome`.

Undo the list:

```powershell
tools\restore.ps1 -TvHost $env:TV_HOST examples\xiaomi-mitv-mooq0\debloat.txt
```

Return to the built-in look:

```powershell
tools\theme.ps1 -TvHost $env:TV_HOST -Reset
```

A local vitals page (CPU, memory, and the app on screen) installs nothing on the TV and listens only on this computer:

```powershell
tools\venv\Scripts\python.exe dashboard\server.py
```

After debugging is off:

```powershell
tools\venv\Scripts\python.exe -I tools\adb-closed.py $env:TV_HOST
```

## Tools

| Tool | Role |
|---|---|
| [`tools/tv.py`](tools/tv.py) | ADB-over-TCP client. `--probe`, a shell command, `--screenshot`, `--push` / `--pull` |
| [`tools/b64push.py`](tools/b64push.py) | Send a larger file (the APK) as checked base64 chunks |
| [`tools/disable.ps1`](tools/disable.ps1) | Disable every package in a list, after the safety checks |
| [`tools/restore.ps1`](tools/restore.ps1) | Put that list back from the recovery record |
| [`tools/recovery.py`](tools/recovery.py) | The record itself: state before the first change, one file per TV and list |
| [`tools/check.py`](tools/check.py) | Report what a firmware update turned back on. `--fix` applies the list again |
| [`tools/theme.ps1`](tools/theme.ps1) | Push a theme folder, or `-Reset` to the built-in config |
| [`tools/privacy.py`](tools/privacy.py) | Report voice, content recognition and telemetry packages, with the evidence |
| [`tools/adb-closed.py`](tools/adb-closed.py) | Confirm network ADB is no longer accepting connections |
| [`tools/race_calendar.py`](tools/race_calendar.py) | Write this season’s F1 calendar into a theme |
| [`tools/scenes_wallpapers.py`](tools/scenes_wallpapers.py) | Draw the pictures for the `scenes` theme |
| [`dashboard/server.py`](dashboard/server.py) | Live CPU, memory and focused app, in a browser on this computer |
| [`launcher/build-and-install.ps1`](launcher/build-and-install.ps1) | Build the release APK, install it, and set it as Home |

Each script’s usage is in its first lines. PowerShell twins match the shell scripts (`disable.ps1` for `disable.sh`, and the same for restore, theme and the install script).

## The launcher

The home app needs Android 8.0 (API 26) or newer. `minSdk` is set in [`launcher/app/build.gradle`](launcher/app/build.gradle), and the install script asks the TV for its SDK before it builds. What is left on an older set is in [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

It is plain Java, with AndroidX RecyclerView and DynamicAnimation, `compileSdk` 34. The release APK stays under 480 KB. The target is a set with about 1 GB of RAM, so the launcher does no blur and plays no video. Motion is a sliding focus, a short tile zoom and a glow. Reduced motion shortens that further for a weak GPU. Advanced effects (a lifted tile, icon zoom, smoother colour) stay off until Launcher settings or a theme turns them on.

Per-TV choices belong in [`launcher/app/src/main/res/values/config.xml`](launcher/app/src/main/res/values/config.xml) or in a theme. A new behaviour ships off until one of those turns it on. Strings are English only.

Built copies of the APK can be kept in `apk_versions/` so a bad install can be replaced without a rebuild. `*.apk` is gitignored.

Run the launcher tests from `launcher/` with JDK 17: `./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease`. Lint stays at zero errors. The Python suite, from the repo root after `tools/venv` exists, is `python -m unittest discover -s tests -v`.

## Supported TVs

| TV | OS | Firmware | List |
|---|---|---|---|
| Xiaomi Mi TV (MiTV-MOOQ0, croods) | Android TV 10 | `QTG3.200305.006.3270` | [`examples/xiaomi-mitv-mooq0/debloat.txt`](examples/xiaomi-mitv-mooq0/debloat.txt) |

The same list also applied on firmware `QTG3.200305.006.5135`. Notes for a second unit on the original firmware are in that file, from the upstream report #81. The index of lists in this copy is [examples/README.md](examples/README.md).

## Privacy

[`tools/privacy.py`](tools/privacy.py) prints what this TV reports about voice, content recognition and telemetry.

What the audit cannot do is prove intent. It shows that an operation was touched, not what happened to the audio or the picture afterwards. The report separates names the firmware nominated, known components, name-only matches, and microphone access in the returned AppOps history. It does not claim a package is listening. `--write` emits a debloat list; disabling still goes through `tools/disable.ps1`, so the undo path stays the recovery record.

The launcher’s top bar can show the Wi-Fi name, and a theme’s `greeting_name` can show a name. Blur both, and any city, before a screenshot leaves this computer. Settings screens that show accounts or device names stay local. `backup/`, `shots/` and `work/` are gitignored for that reason, except the before-and-after video linked above.

## Layout of this repo

| Path | What it is |
|---|---|
| [PROMPT.md](PROMPT.md) | The procedure an agent follows on the real TV |
| [AGENTS.md](AGENTS.md) | The rules for every coding agent in this repo |
| [examples/](examples/) | The debloat list for the TV in this house |
| [launcher/](launcher/) | The home app |
| [themes/](themes/) | Looks: `config.json`, wallpaper, screenshot |
| [tools/](tools/) | The ADB client and the scripts |
| [dashboard/](dashboard/) | The local vitals page |
| [docs/](docs/) | Troubleshooting, the app library, and the sky backdrop |
| [tests/](tests/) | Python checks for lists, themes, the Home guard and the tools |
| [apk_versions/](apk_versions/) | Built APKs kept for rollback |

## Licence

[MIT](LICENSE). Copyright (c) 2026 Gathin.
