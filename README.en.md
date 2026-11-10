[中文](README.md) · **English**

# TVHome / TVCleaner · Field Notes for Lightweight Android TV Launchers

> **Origin and development credits**
> This project is derived from the list-driven approach of the open-source project
> **CleanYourTV**, adapted specifically for **Hisense TVs running the 32-bit VIDAA system**.
> The analysis, design and implementation were carried out with **WorkBuddy** invoking
> **DeepSeek** and **HY4 (Tencent Hunyuan)**.

---

## What this is

On many Android TVs, a phone-version video client exits after ten seconds of playback —
sometimes freezing, sometimes rebooting the whole system. The stock launcher is heavy and
OEM services stay resident. The usual explanation is "not enough RAM" or "incompatible app",
which leads to endless cache clearing and reinstalling without solving anything.

This repository documents one complete investigation and two small, buildable tools:

| Component | Purpose |
| --- | --- |
| **TVHome** | A pure-Canvas launcher: no AndroidX, no network, low memory footprint, re-orderable favorites |
| **TVCleaner** | Memory cleaner + disabled-package baseline + boot guard that verifies each package was really stopped |

The real value is in `docs/`: how to collect evidence without root, how a third-party app can
actually start at boot, why full AOT compilation breaks video playback, and which approaches
are dead ends. The tools are just carriers for those conclusions.

## Platform and limits

- Target: **Hisense TVs, 32-bit VIDAA system** (Android 9 / SDK 28 generation, 32-bit `zygote32`, ~1.5 GB RAM)
- **No root required**; `/system` and `/vendor` remain read-only
- The method is general, but **package names, component names and allow-lists are examples only** — verify them for your own model
- No prebuilt APK, signing key, device backup or third-party installer is included

> This is not a generic ROM guide. Changing the default launcher or disabling preinstalled
> packages can make a TV temporarily unusable. Make sure **ADB over TCP works** first
> (wired Ethernet is more reliable than Wi-Fi) and keep the restore scripts handy.

## Quick start

```bash
export TV_HOST=<电视IP>:5555
adb connect "$TV_HOST"

# JDK 17 + Android SDK 34 / build-tools 34 required
bash scripts/tvhome/build.sh 1.5.1 151 tvhome-release
bash scripts/tvcleaner/build.sh 1.1.4 15 tvcleaner-release

bash scripts/tvhome/enable-autostart.sh

bash scripts/tvhome/restore.sh check
bash scripts/tvhome/restore.sh home-back
bash scripts/tvhome/restore.sh tvhome
bash scripts/tvhome/restore.sh full
```

Build scripts generate local debug signing keys and ADB keys on demand.
**These files are never committed.**

## Three conclusions worth reading first

1. **Do not run full AOT on a video client** (`cmd package compile -m speed -f <package>`).
   With `speed`, three consecutive playback attempts all crashed during the ad; after
   reverting to `speed-profile`, three consecutive attempts played through into the content.
   See `docs/05-dexopt-aot.md`.
2. **Third-party apps do not receive `BOOT_COMPLETED`** here, and persisted jobs do not
   survive reboot. The working channel is `NotificationListenerService` plus a
   `Settings.Global.BOOT_COUNT` gate. See `docs/02-boot-autostart.md`.
3. **There is more than one native crash stack.** OMX callbacks, GPU/Surface, audio
   consumption and ART odex each crashed once, so "free some RAM" cannot fix it.
   See `docs/01-diagnosis-method.md` and `docs/09-limitations.md`.

## Privacy statement

This repository contains **no real device data**: LAN IPs, serial numbers / MAC addresses,
firmware build numbers, local filesystem paths, usernames / hostnames, ADB keys, signing
keys and fingerprints, membership / login state, watch history, internal ticket numbers and
second-by-second operation logs have all been removed or replaced with placeholders.

Package and component names in the source are **public Android identifiers used as example
configuration** — replace them for your device. `<电视IP>`, `<package>` and `<SDK_ROOT>`
are placeholders.

## Evidence levels and disclaimer

`docs/09-limitations.md` states the evidence strength of each conclusion. Several results
come from controlled reproductions **within a single boot cycle**, without an A/B/A reversal,
so a reboot clearing accumulated state and the configuration change are confounded in time.
They are not strict causal proof.

These are experiences from one device, not vendor documentation. Evaluate the risk before
changing system configuration. The authors are not responsible for bricked devices, data
loss, or changes to account / membership state.

## License and credits

Code is **GPL-3.0** (it includes key-handling code derived from a GPL project — see
`NOTICE.md`); documentation may be freely quoted with attribution.

- **CleanYourTV** — source of the list-driven cleanup methodology
- **WorkBuddy + DeepSeek + HY4 (Tencent Hunyuan)** — analysis, design and coding assistance
- **AndroidTvMouse** — upstream GPL component and publishing-practice reference
