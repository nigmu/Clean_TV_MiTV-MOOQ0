# This TV

Xiaomi Mi TV (MiTV-MOOQ0, croods), Android 10, firmware QTG3.200305.006.3270.

The home app is `launcher/` (`com.gathin.tvhome`). Packages are disabled, never uninstalled. The list applied on this set is [work/mitv-mooq0-session.txt](work/mitv-mooq0-session.txt). Restore, the dock, and pushing a theme are in [work/README.md](work/README.md).

The procedure for a coding agent is [PROMPT.md](PROMPT.md). The rules that agent follows are in [AGENTS.md](AGENTS.md). When the set misbehaves, see [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

## Supported TVs

| TV | OS |
|---|---|
| Xiaomi Mi TV (MiTV-MOOQ0, croods) | Android TV 10 |

The debloat list for firmware `QTG3.200305.006.3270` is [`examples/xiaomi-mitv-mooq0/debloat.txt`](examples/xiaomi-mitv-mooq0/debloat.txt).

## Doing it by hand

From the repo root, with the TV's IP:

```
$env:TV_HOST = '<tv ip>'
tools\venv\Scripts\python.exe -I tools\tv.py --probe
launcher\build-and-install.ps1 -TvHost $env:TV_HOST
tools\theme.ps1 -TvHost $env:TV_HOST themes\aurora
```

`dashboard\server.py` shows CPU, memory and the app on screen. It installs nothing on the TV.

## The launcher

The home app needs Android 8.0 (API 26) or newer. `minSdk` is set in [`launcher/app/build.gradle`](launcher/app/build.gradle), and the install script asks the TV for its SDK before it builds. What is left on an older set is in [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

## Privacy

[`tools/privacy.py`](tools/privacy.py) prints what this TV reports about voice, content recognition and telemetry.

What the audit cannot do is prove intent. It shows that an operation was touched, not what happened to the audio or the picture afterwards.
