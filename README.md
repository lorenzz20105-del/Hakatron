# LinuxDesk

**A complete Linux desktop on an Android phone, with a built-in VNC viewer.**

LinuxDesk is an Android application that turns the phone into a Linux
workstation. It ships its own VNC client (written from scratch in Kotlin),
so nothing has to be installed on the desktop side: the app talks to the VNC
server that runs inside Termux, renders the framebuffer on the GPU and turns
touches and keystrokes back into RFB input events.

```
┌──────────────────── Android phone ────────────────────┐
│                                                        │
│   LinuxDesk app          │        Termux               │
│   ┌──────────────┐       │   ┌────────────────────┐   │
│   │ :rfb module  │       │   │ proot-distro       │   │
│   │ RFB client   │◀──────┼──▶│  └ Linux + X11     │   │
│   │ (Kotlin)     │ TCP   │   │     └ VNC server   │   │
│   └──────────────┘ 5901  │   └────────────────────┘   │
│   ┌──────────────┐       │   ┌────────────────────┐   │
│   │ Compose UI   │◀──────┼──▶│ linuxdeskd daemon  │   │
│   │ viewer/HUD   │ 7801  │   └────────────────────┘   │
│   └──────────────┘       │                              │
└────────────────────────────────────────────────────────┘
```

## What is in the box

| Module | What it does |
| --- | --- |
| `app/` | The Android application (Kotlin + Jetpack Compose). Dashboard, built-in VNC viewer, settings, profiles, shell, first-run wizard. |
| `rfb/` | A pure Kotlin RFB (VNC) client library. No Android dependencies, fully unit tested, independently usable. |
| `engine/` | The Termux side: `linuxdesk` (CLI) and `linuxdeskd` (control daemon). Shipped inside the APK and pushed into Termux by the app. |
| `tools/` | Build, install and icon helpers. |
| `docs/` | Architecture, protocol notes and the engine reference. |

## The store (all free)

The Store tab installs everything with one tap, through the engine:
Modded OS editions (Ubuntu XFCE/KDE, Debian XFCE, Manjaro, Kali and a
Dev Station), plain distros, desktop styles (XFCE, LXQt, MATE, KDE Plasma,
Openbox, i3), IDE apps (Code Server/VS Code, Geany, Kate, Neovim, Jupyter),
CLI agent apps (OpenCode, Claude Code, Codex, Gemini CLI, Aider, Goose,
Crush, Qwen Code, Ollama), browsers, media players, fonts, office apps,
dev tools, games and system tools — about 80 items, no paywall anywhere.

## The VNC client

The client in `rfb/` implements the RFB protocol from the specification
(`docs/rfbproto.rst`). It supports:

* **Protocol** – RFB 3.3 and 3.7/3.8 handshakes, `None` and `VNC`
  authentication (DES with the bit reversed key), shared sessions.
* **Encodings** – `Raw`, `CopyRect`, `RRE`, `CoRRE`, `Hextile`, `zlib`,
  `ZRLE` and `Tight` (including the fill, palette, gradient and JPEG
  variants). The client only advertises encodings it can actually decode, so
  a server never picks something that would corrupt the picture.
* **Pseudo encodings** – `DesktopSize` (-223), `LastRect` (-224) and
  `ExtendedDesktopSize` (-308), plus the `SetDesktopSize` client message
  (251) so the desktop can be resized from the phone.
* **Input** – pointer events (all buttons plus the wheel), key events
  translated from Android key codes into X11 keysyms, and clipboard
  transfer in both directions.
* **Colour** – the client asks for the canonical 32bpp little endian true
  colour format, so every decoder is a simple byte shuffle. 16 and 24 bit
  formats are supported as well.

The library is verified against a real server: the test suite connects to a
live TigerVNC server, forces one encoding at a time and compares the decoded
framebuffer with the colour the X server painted its root window with.

```
$ gradle :rfb:cleanTest :rfb:test --no-daemon --no-build-cache
...
35 tests, 0 failures
```

The nine `LiveServerTest` cases need a VNC server on `127.0.0.1:5901`; they
are skipped when there is none.

| Test | What it proves |
| --- | --- |
| `VncAuthTest` | The DES challenge response matches an independent implementation (pycryptodome). |
| `RfbPixelFormatTest` | Pixel formats, CPIXEL/TPIXEL byte order and colour scaling. |
| `RfbDecoderTest` | Every decoder against hand built byte streams. |
| `LiveServerTest` | The whole client against a real TigerVNC server, one encoding at a time. |

## The engine

`engine/linuxdesk` is a small command line tool that lives inside Termux:

```bash
linuxdesk bootstrap            # install the Termux packages
linuxdesk distro install ubuntu
linuxdesk session start main --geometry 1280x800 --port 5901 --desktop xfce4
linuxdesk session status main
linuxdesk session stop main
```

`engine/linuxdeskd` is a small control daemon that listens on
`127.0.0.1:7801` and exposes the commands above over a JSON line protocol.
Because the loopback interface is shared by all apps on a device, the
Android app can start and stop sessions without going through Termux for
every call.

The app pushes both scripts into Termux the first time ("Install the
engine"), in base64 encoded chunks, because an Android intent cannot carry a
multi kilobyte payload in one go.

## Customisation

Everything is configurable at runtime, from the settings screen. All of it
is stored in a DataStore and survives a restart of the app.

* **VNC server** – host, port and password of the server, plus the
  encoding the client asks for: `auto` (everything the client can decode),
  `zrle`, `tight`, `hextile`, `zlib` or plain `raw`.
* **Engine daemon** – host and port of `linuxdeskd` (127.0.0.1:7801 by
  default), with buttons to install the engine, start it, stop it and run
  the self test.
* **Distributions** – one tap installation of `ubuntu`, `debian`, `arch` or
  `alpine` through the engine.
* **Viewer** – the scale mode (fit, fill, stretch, 1:1), the touch point
  indicator, the status bar, swipe sends keys and "keep the screen on".
* **Profiles** – saved desktops with a name, a distribution, a desktop
  environment, a geometry, a VNC port and a display number. A profile can be
  started, stopped or opened directly in the viewer.

The first start shows a wizard that walks through Termux, the engine, the
distribution and the first desktop; it can be run again from the settings.

The console screen talks to the engine: it runs commands inside a running
session, shows the session log and the output of the self test.

## Building

The project is a plain Gradle build:

```bash
source tools/env.sh            # JDK 17, the Android SDK and Gradle 8.7
tools/build_release.sh          # signed release APK in release/
tools/build_release.sh --debug  # the debug APK
tools/install_on_device.sh      # install it on a connected phone
```

`tools/build_release.sh` creates the signing key on the first run
(`app/keystore/linuxdesk.jks`, the password is `linuxdesk`) and verifies the
result with `apksigner`. The tests of the protocol run without a device:

```bash
gradle :rfb:cleanTest :rfb:test --no-daemon --no-build-cache
```

Requirements: JDK 17, Android SDK with platform 34 and build tools 34.0.0,
Gradle 8.7.

## How it is used

1. Install **Termux** (F-Droid) and optionally **Termux:X11**.
2. Open LinuxDesk and follow the wizard:
   * install the engine (the app pushes `linuxdesk` and `linuxdeskd` into
     Termux),
   * allow external apps in Termux (`Settings → Allow external apps`),
   * install the Termux packages and a proot distribution,
   * pick a desktop environment and a resolution.
3. Press **Start desktop** and the app opens the built-in viewer.

The viewer is also a normal VNC client: point it at any server
(`127.0.0.1:5901` for the local desktop, or a machine on the network) and it
works the same way.

## Documentation

* [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) – modules, threads and data flow.
* [docs/RFB.md](docs/RFB.md) – what the client implements and how it was verified.
* [docs/ENGINE.md](docs/ENGINE.md) – the Termux engine reference.
* [docs/BUILD.md](docs/BUILD.md) – toolchain, build and release.

## Licence

MIT.
