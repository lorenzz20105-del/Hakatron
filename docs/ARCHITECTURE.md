# Architecture

## Modules

```
LinuxDesk/
├── app/                  Android application (com.android.application)
│   └── src/main/kotlin/dev/linuxdesk/app/
│       ├── MainActivity.kt        Compose entry point and navigation
│       ├── AppViewModel.kt        Settings, profiles, engine control
│       ├── data/AppData.kt        Settings and profile models + DataStore
│       ├── rfb/                   The viewer (Android side of :rfb)
│       │   ├── VncSession.kt      Owns the connection and the bitmap
│       │   ├── VncViewer.kt       Compose canvas, touch gestures
│       │   └── KeyMap.kt          Android key code -> X11 keysym
│       ├── session/DesktopService.kt   Keeps the session alive
│       ├── termux/                The bridge to Termux
│       │   ├── TermuxBridge.kt    RunCommandService, engine installation
│       │   └── EngineClient.kt    JSON line client for linuxdeskd
│       └── ui/                    Screens and widgets
├── rfb/                  Pure Kotlin RFB client (org.jetbrains.kotlin.jvm)
├── engine/               The Termux side
│   ├── linuxdesk         CLI
│   └── linuxdeskd        Control daemon
└── tools/                Build helpers
```

## Threads

| Thread | Work |
| --- | --- |
| Main | Compose UI, bitmap uploads, key and pointer dispatch. |
| `linuxdesk-vnc` | The `RfbClient` network loop: reads server messages, decodes rectangles, writes input events. |
| `linuxdeskd` (in Termux) | Accepts control commands, runs the `linuxdesk` CLI, returns the output. |

The framebuffer is owned by the network thread. When a rectangle has been
decoded, the pixels are copied into an Android `Bitmap` on the main thread
and published as a `VideoFrame`. The bitmap is reused between frames, so a
session does not allocate per frame.

## Data flow

### Session

1. `AppViewModel.connect()` turns the settings into `RfbOptions` and
   calls `VncSession.start()`.
2. `VncSession` opens the socket, performs the handshake and enters the
   message loop.
3. Every `FramebufferUpdate` is decoded into the `Framebuffer` and published
   to the UI.
4. Touches are converted to framebuffer coordinates and sent as pointer
   events; the Android key map turns hardware keys into X11 keysyms.

### Engine

1. The app pushes `engine/linuxdesk` and `engine/linuxdeskd` into
   `$HOME/var/lib/linuxdesk` inside Termux, base64 encoded, using
   `com.termux.app.execute`.
2. It starts `linuxdeskd`, which listens on `127.0.0.1:7801`.
3. From then on the app talks to the daemon over a JSON line protocol:
   `{"cmd":"start","args":["main","--distro","ubuntu"]}`.
4. The daemon runs the CLI and returns `{"ok":true,"output":"..."}`.

Because the daemon listens on the loopback interface, no permission is
needed and the connection survives the app being restarted.

## Why a separate `:rfb` module

The RFB client has no Android dependency at all, which means it can be
unit tested on a plain JVM and reused outside the app. The module contains
its own test suite, including a test that talks to a real VNC server.

## The viewer

`VncViewer` draws the frame with `Canvas.drawImage`, so the scaling mode
only changes the destination rectangle. The touch handling lives in one
`awaitPointerEventScope` block:

* one finger – the pointer moves, the button mask is 1 (left),
* two fingers – the mask becomes 1 + 4, so the right button is held as
  well and a drag with two fingers is a right drag,
* the toolbar offers a *right click* button that sends a single event with
  the mask 4 in the middle of the picture,
* the optional touch point under the finger shows where the pointer is.

The hardware keyboard is handled with `onPreviewKeyEvent`: the Android key
code is translated into an X11 keysym by `KeyMap` and sent as a key event.
The virtual keyboard in the session bar sends the same keysyms, so it works
with a server that has no keyboard at all.

The layout is recomputed whenever the frame size, the canvas size or the
scale mode changes, so the remote coordinates are always computed from the
screen coordinates with `toRemote()`.
