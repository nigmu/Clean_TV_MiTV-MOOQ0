# AGENTS.md

Instructions for coding agents (Claude Code, Codex, Gemini CLI, Cursor, Copilot and others) working in
this repository. Start with [README.md](README.md). This file is the rules an agent follows.

## First: which job are you doing?

| You were asked to | Do this |
|---|---|
| Clean up a TV: debloat it, install the launcher, push a theme | Read [PROMPT.md](PROMPT.md) and follow it exactly, on the real TV. Use the tools in `tools/` and the launcher in `launcher/` rather than writing new ones; the only file you need to change is `launcher/app/src/main/res/values/config.xml`, or a theme's `config.json`, for this TV. Do not edit the repo's docs, tests or other code, and do not open issues or pull requests unless the person asks you to. |
| Change this repo: a TV list, a fix, a translation, a theme, docs | Skip the next section and read on from [Repo map](#repo-map). |

### Cleaning a TV: the rules that are not negotiable

[PROMPT.md](PROMPT.md) is the procedure. Its safety rules, in brief:

- **Disable, never uninstall.** `pm disable-user --user 0`, one package per shell call. No
  `pm uninstall`, no root, nothing flashed. Every step must be undone by `tools/restore.sh`.
- **Back up first.** Package list, disabled list, `getprop` and settings into `backup/` before the first
  change. `tools/disable.sh` also writes its own record in `~/.cleanyourtv/`; keep the list you applied.
- **Leave alone what the TV needs to work:** the TV app and input services behind HDMI and the tuner,
  the input/source menu, remote and Bluetooth pairing, the on-screen keyboard, Play Store and Play
  Services. When a package name says nothing, read `dumpsys package <name>`, then ask.
- **Never disable Android's core packages:** the permission controller, package installer, ext
  services/shared and module metadata (`com.android.*` and `com.google.android.*`),
  `com.google.android.overlay.modules*`, `com.android.providers.*` (calendar, contacts, user dictionary
  and blocked numbers aside), `com.android.systemui`, `com.android.shell`, `com.android.phone`,
  `com.android.settings`, `com.android.tv.settings`, `android`. Without them Android may not boot (#884);
  `tools/disable.sh`, `disable.ps1` and `check.py --fix` refuse such a list, with no override.
- **Never start a factory or test package** (`com.cvte.tv.hkcfac`, `mediatek.factorymenu.ui` and the like),
  not even to look. On some boards that flips a factory-mode flag the shell cannot undo.
- **Keep the Home guard.** The stock launcher is disabled only after the new launcher is installed and
  confirmed as Home (`dumpsys activity activities | grep mFocusedApp`). On Google TV, check
  `dumpsys wifi | grep -o 'cname=[^ ]*'` before disabling setupwraith: if it owns the saved Wi-Fi
  network, disabling it takes the TV off Wi-Fi and ADB. `tools/disable.sh` and
  `launcher/build-and-install.sh` already enforce this. Use `FORCE_HOME=1` or `FORCE_SETUPWRAITH=1` only in
  the situation the script's own message describes, and only with the person's agreement.
  - On a CVTE board (any `com.cvte.*` package), leave its stock launcher (`com.cvte.tv.launcher.*`) enabled
    unless the `examples/` folder for that exact board disables it. Make the new launcher Home with
    `cmd package set-home-activity`, reboot once, and confirm Home and ADB come back before anything else.
    Don't enable a remote-key helper in the same pass: a JVC set hung on its boot animation with ADB gone
    after both were done before one reboot (#741).
- **Ask before anything the family uses.** Streaming apps, the voice assistant, Chromecast, Miracast and
  the firmware updater go into one checklist question; disable only what the person does not tick.
- **Disable in small batches** and screenshot between them (`tools/tv.py --screenshot shots/01.png`).
  Live TV, HDMI and some streaming apps give empty screenshots; that is the TV, not damage.
- **An injected key is not proof** for a remote button that goes through an accessibility helper: ask the
  person to press the real button. `am force-stop com.gathin.tvhome` switches those helpers off.
- **Close ADB afterwards.** USB debugging and Network/ADB debugging off, authorisations revoked, reboot,
  then `tools/adb-closed.py <tv ip>` must report the port closed. Port 5555 is plaintext.
- **Stop and say so** when the TV refuses: `pm disable-user` refused for every package, a sideload that
  fails verification, or an SDK below the launcher's `minSdk` 26. Do not retry in a loop, and do not lower
  `minSdk` or hand over an APK built elsewhere.

The person's backups (`backup/`), screenshots (`shots/`), notes (`work/`) and the ADB key
(`tools/adbkey/`) are listed in `.gitignore` and must never be committed. The one exception is
`shots/before-after-60s-white.mp4`, the before-and-after video embedded in `README.md`.
If the person wants to
share their TV's list afterwards, follow [Add a TV list](#add-a-tv-list) below.

## Repo map

| Path | What it is |
|---|---|
| `PROMPT.md` | The prompt people paste into their agent. Brand-neutral; safety rules live here. |
| `README.md` | How this copy is used: this TV, the debloat list, and the commands. |
| `examples/` | The debloat list for the TV in this house. Indexed in `examples/README.md`. |
| `docs/` | `docs/TROUBLESHOOTING.md`, `docs/sky.md`, `docs/app-library.md`. |
| `launcher/` | The home app, package `com.gathin.tvhome`: plain Java, AndroidX RecyclerView and DynamicAnimation, `minSdk` 26. |
| `themes/` | One folder per theme: `config.json`, a required `screenshot.jpg`, optional wallpapers. Keys are documented in `themes/README.md`. |
| `dashboard/` | A local page of CPU, memory and the app on screen, read over ADB. Nothing is installed on the TV. |
| `tools/` | The ADB client and the scripts: `tv.py`, `b64push.py`, `disable.sh`, `restore.sh`, `recovery.py`, `check.py`, `privacy.py`, `theme.sh`, `adb-closed.py`, `race_calendar.py`. Shell scripts have PowerShell twins (`disable.ps1` and the like). `scenes_wallpapers.py` draws the scenes theme. Each script's usage is in its first lines: read it before running it. |
| `tests/` | Python `unittest` suite for the list format, themes, the Home guard and the tools against fake TVs. |
| `apk_versions/` | Built copies of the home app, kept so a bad install can be put back without a rebuild. |

## Where a change goes

The core stays small on purpose.

| The change | Where it goes |
|---|---|
| A TV list, a fix, a translation, docs, a tool fix | In this copy |
| A small launcher switch that is off by default | `launcher/`: see [the switch recipe](#add-an-off-by-default-launcher-switch) |
| A new look: colours, wallpaper, dock, columns, that existing config keys can carry | `themes/<name>/` |
| A launcher change, including a new permission or network access | `launcher/` in this copy |
| Safety rules in `PROMPT.md`, the Home-safety logic in the scripts, protected packages, CI and workflows, the APK size gate | Change them here. Disable, never uninstall, and leave the packages the TV needs to boot and to show a picture. |

## Recipes

Run the tests after every recipe: `python3 -m unittest discover -s tests -v` (see [Before opening a PR](#before-opening-a-pr)).

### Add a TV list

1. Check `examples/README.md` first. The same product code and firmware as an existing folder is usually a
   second unit or a re-badge: add your notes to that folder instead of a new one.
2. Create `examples/<brand>-<model>/debloat.txt`, lower-case with hyphens. Copy the format of
   `examples/xiaomi-mitv-mooq0/debloat.txt`:
   - The first line is a comment with the exact model, Android version and firmware build
     (`tools/tv.py --probe` prints them).
   - Then the Apply and Undo lines, the stock launcher and Live TV package if they differ, and what was kept and why.
   - One package per line, grouped under a comment that names the group, with a trailing comment on anything
     non-obvious. No duplicates, nothing but package names outside comments.
3. List only packages you actually disabled and lived with for a few days. Note anything you re-enabled.
   Mark untested entries as comments.
4. Add one row to the table in `examples/README.md` (`` | `<folder>/` | TV | OS | Notes | ``) and one to the
   TV table in `README.md` (`| TV | OS |`). The TV cell must be the same text in both; `tests/test_examples.py`
   compares them.
5. Leave the counts alone (the `TV models` number in the card's alt text, the brand line): CI checks them only
   on `main`, and they are fixed up on merge.
6. A `README.md` for TV-specific notes and a `config.json` with the dock are welcome in the folder. Do not add
   install or restore scripts, Private DNS or AdGuard setup, or `pm uninstall` steps: the folder only
   disables, and `tools/disable.sh` and `tools/restore.sh` do the rest.

A folder in `examples/` must have packages in it.

### Update an existing list

Keep the header accurate: add the second unit's model, firmware and region rather than replacing the first.
Explain each added or re-enabled package in a comment. A list that disables the firmware updater says why in the
header; keeping it enabled is preferred. `pm hide` or `pm block` needs the matching undo in the header. Edits to
someone else's list are judgement calls, so say in the PR which TV you tested on and for how long.

### Languages

This copy is English only. Launcher strings live in `launcher/app/src/main/res/values/strings.xml`, and `launcher/app/src/main/res/xml/locales_config.xml` lists `en`. Do not add other locales.

### Add an off-by-default launcher switch

The core takes a switch only if it changes nothing until a theme or `config.xml` turns it on:

1. Add the key to `launcher/app/src/main/res/values/config.xml` with a comment that says what it does and
   that a theme can set it, defaulting to off (`false`, `0` or the current behaviour).
2. Read it in `launcher/app/src/main/java/com/gathin/tvhome/Config.java`, from the resource and from the
   theme JSON, the way existing keys such as `show_stats` are read.
3. Add a row to the Keys table in `themes/README.md`.
4. If it adds a row to Launcher settings, put its strings in `launcher/app/src/main/res/values/strings.xml`.
5. With the key off, nothing new runs: no receiver, no service, no write, no permission grant in the install
   script. The bot checks this in the diff, not in the description.
6. Add a JVM unit test under `launcher/app/src/test/java/com/gathin/tvhome/` for any logic that can be tested
   without a TV.
7. Keep it small: a switch should cost a few hundred bytes to about 1 KB of APK. Build and check the size
   gate; the gate is hard.
8. Verify on a real TV with the switch on and off, D-pad from every edge, and attach screenshots.

Anything TV-specific goes in `config.xml` or a theme, not in code. A bug fix that corrects wrong behaviour needs
no switch. No new dependencies in `launcher/app/build.gradle` without an issue first.

### Add a theme

1. Create `themes/<name>/config.json` using only keys from the table in `themes/README.md`;
   `themes/default/config.json` is the starting point.
2. Add `wallpaper.jpg` or a `wallpapers/` folder if it uses them: dark, 1920x1080, under about 300 KB each,
   and art you have the right to use.
3. Push it to a real TV with `tools/theme.sh themes/<name>` and take `screenshot.jpg` of Home with the focus
   on the dock: a JPEG at 1280x720 (16:9), at most 300 KB. Not a mock-up, not an emulator.
   `tests/test_themes.py` fails without it.
4. Add a row to the table in `themes/README.md` showing the screenshot, like the others.
5. A look that needs a new key is a launcher switch first.

### Docs and troubleshooting notes

- `docs/TROUBLESHOOTING.md` is grouped under headings (Connecting, Working on the TV, then one per larger
  problem). An entry is a bullet that opens with the symptom in bold, then the cause and the fix, naming the
  TV model and firmware it was seen on.
- A fix for something your agent got stuck on usually belongs in `PROMPT.md` as one extra sentence, kept
  brand-neutral. Brand-specific knowledge goes in `examples/`.
- Several tests pin facts in `PROMPT.md` and `docs/TROUBLESHOOTING.md`. If you reword one of those passages, run the suite and keep the fact.
- Docs and the README stay in step: a new list, theme or tool gets its row where the others are.

## Before calling a launcher change done

| Check | Command or rule |
|---|---|
| Tests | `python3 -m unittest discover -s tests -v` after `python3 -m venv tools/venv` and `tools/venv/bin/pip install -r tools/requirements.txt`. |
| Launcher, if you touched `launcher/` | JDK 17, Android SDK platform 34, build-tools 34.0.0. In `launcher/`: `./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease`. Lint must stay at zero errors. |
| APK size | `launcher/app/build/outputs/apk/release/app-release.apk` must be under 491,520 B (480 KB). The set has about 1 GB of RAM. |
| README rows | A new list has its row in `README.md` and `examples/README.md`; a theme in `themes/README.md`. |
| Real TV | Launcher changes are verified on a real TV, not only the emulator: `tools/tv.py --screenshot shots/after.png DPAD_DOWN`. D-pad from every edge (Up, Down, Left, Right). A button handled by an accessibility helper needs a press on the real remote, and a force-stop of the launcher switches the helpers off (`docs/TROUBLESHOOTING.md`). |
| Screenshots | Every visual change has a screenshot from the TV. |

## Hard rules

Never do these:

- **No uninstalling.** No `pm uninstall` in a list or a script. `pm hide` or `pm block` only with the matching
  undo written in the list header.
- **No blur or video in the launcher.** It targets TVs with 1 GB of RAM.
- **No new launcher dependencies** without a reason that belongs in the change.
- **No changing a default for every TV.** New behaviour is off until a theme or `config.xml` turns it on.
- **Firmware updaters are better left enabled** (commented out in the list); a list that disables one says
  why in its header.
- **No Private DNS or AdGuard setup, and no install or restore scripts, inside a list folder.** The README
  already has the optional DNS extra; a note that Private DNS works on that TV is fine.
- **No secrets or identifiers** in commits, lists or screenshots: no IP addresses, Wi-Fi names, account emails,
  serial numbers, Android IDs, MAC addresses or ADB keys. Use `<tv ip>` or `192.168.1.50` in docs.
- **Don't open factory or test menus** on a TV to see what they do. Factory packages may be disabled, never
  launched.
- **No root, no getting around app-store protection, no private builds** others cannot reproduce from this repo.
- **No extras added to the PROMPT flow** beyond debloating and the launcher.
- **Don't relax `minSdk`** (26).

## Privacy and screenshots

- The launcher's top bar shows the Wi-Fi network name, and a theme's `greeting_name` shows a name. Blur
  Wi-Fi names, people's names and cities, or crop them out, before a screenshot goes anywhere public.
- Never commit or post TV Settings screenshots that show accounts, email addresses or device names.
- `tools/tv.py --probe`, `getprop` and backups contain the serial number and device names. Quote only the model,
  Android version and firmware build.
- Keep session files in `backup/`, `shots/` and `work/`, which git ignores. The README's
  before-and-after video is the only file under `shots/` that is kept.
- The code is under the repo's MIT licence.
