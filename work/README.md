# This TV

Xiaomi Mi TV (MiTV-MOOQ0, croods), Android 10, firmware QTG3.200305.006.3270.

Packages were disabled for user 0, not uninstalled. The list is `work/mitv-mooq0-session.txt`.
The stock launcher `com.google.android.tvlauncher` was disabled by `launcher/build-and-install.ps1`
after `com.gathin.tvhome` was confirmed as Home. A reboot left that set unchanged.

## Put the TV back

From the repo root, with the TV's IP:

```
$env:TV_HOST = '<tv ip>'
tools\restore.ps1 -TvHost $env:TV_HOST work\mitv-mooq0-session.txt
```

That re-enables every package in the list and returns Home to the stock launcher.
The recovery record is in `%USERPROFILE%\.cleanyourtv\`. Keep it.

One app only:

```
tools\venv\Scripts\python.exe -I tools\tv.py "pm enable --user 0 com.netflix.ninja"
```

## Dock and wallpaper

Dock order and the six columns are in `launcher/app/src/main/res/values/config.xml`
(`dock_apps`, `columns`). Change them, then:

```
launcher\build-and-install.ps1 -TvHost <tv ip>
```

On the TV, hold OK on a tile for the launcher's own settings once `tile_menu` is on,
or change the wallpaper from a theme: `tools\theme.ps1 themes\<name>`.

Screensaver is still Aerial Views.
