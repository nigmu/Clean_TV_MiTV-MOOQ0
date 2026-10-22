# TV vitals

A local page that reads the connected TV over ADB twice a second. It shows CPU, memory, `/data` storage, uptime, the app on screen, and the twelve processes using the most memory. Nothing is installed on the TV, and nothing is written there. The page is served only on this computer.

The TV must already be connected, the way `tools/tv.py` connects. `adb` has to be on `PATH`.

From the repo root:

```
python dashboard/server.py
```

That opens [http://127.0.0.1:8765](http://127.0.0.1:8765). Ctrl+C stops it.

`TV_HOST` picks the set when more than one device is attached (`TV_HOST=<tv ip>`, or `host:5555`). With one device and no `TV_HOST`, it uses whichever `adb devices` lists.

| Flag | Default | |
|---|---|---|
| `--port` | `8765` | Port on this computer |
| `--host` | `127.0.0.1` | Bind address. Leave this as localhost. |
| `--interval` | `0.5` | Seconds between samples. `0.5` is the minimum; a sample takes about 0.4s. |
| `--no-open` | off | Do not open a browser. |

Memory is `total − available` from `/proc/meminfo`. The "lost RAM" line from `dumpsys meminfo` is not included; that dump takes several seconds. Process sizes are RSS, which is larger than the PSS share `dumpsys meminfo` reports. The load average on this MediaTek board stays high because parked tuner and HDMI threads are counted, so the CPU percentage is the useful figure. Reading processes and the focused window this often is itself part of that percentage.
