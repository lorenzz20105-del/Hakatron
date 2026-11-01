# The Termux engine

The engine is the part of LinuxDesk that lives inside Termux. It is pushed
into Termux by the app the first time and then works on its own.

## Files

| File | Purpose |
| --- | --- |
| `engine/linuxdesk` | The command line tool. |
| `engine/linuxdeskd` | The control daemon (Python 3). |

Both are shipped inside the APK (`app/build/generated/engineAssets`) and
copied to `$HOME/.local/share/linuxdesk` inside Termux.

## `linuxdesk`

```bash
linuxdesk bootstrap [--no-sudo]       install the Termux packages
linuxdesk distro list                 list the installed distributions
linuxdesk distro install <name>       install and provision a distribution
linuxdesk distro remove <name>        remove a distribution
linuxdesk session start <name> [opts] start a desktop session
linuxdesk session stop <name>         stop a desktop session
linuxdesk session list                list the sessions
linuxdesk session status <name>       status as JSON
linuxdesk session resize <name> WxH   change the framebuffer size
linuxdesk session exec <name> <cmd>   run a command inside the distribution
linuxdesk session logs <name>         show the session log
linuxdesk selftest                    check the installation
```

Options of `session start`:

```
--distro <name>     the proot distribution        (default: ubuntu)
--geometry <WxH>    the size of the X display      (default: 1280x800)
--port <n>          the VNC port                   (default: 5901)
--display <n>       the X display number           (default: 1)
--desktop <name>    xfce4, lxde, lxqt, openbox, fluxbox, i3, gnome, mate, none
--vnc-server <bin>  Xtigervnc or x11vnc
```

State is kept in `$PREFIX/var/lib/linuxdesk`, logs in
`$PREFIX/var/log/linuxdesk`.

### What a session does

1. `proot-distro login <distro>` starts the distribution.
2. The X server is started inside it:
   `Xtigervnc :1 -geometry 1280x800 -depth 24 -rfbport 5901 -SecurityTypes
   None -AlwaysShared -localhost`. When `Xtigervnc` is not available the
   engine falls back to `Xvfb` plus `x11vnc`.
3. PulseAudio is started with the native TCP protocol on the loopback
   interface, so the desktop has sound.
4. The desktop environment is started through `dbus-run-session`, which is
   what makes the session bus available.

### Useful environment variables

| Variable | Purpose |
| --- | --- |
| `XDG_RUNTIME_DIR` | A private directory with mode 0700. Without it many applications refuse to start. |
| `LIBGL_ALWAYS_SOFTWARE=1` | Software rendering fallback when the GPU driver does not work. |
| `GALLIUM_DRIVER=llvmpipe` | The software rasteriser. |
| `PULSE_SERVER=tcp:127.0.0.1:4713` | Connects the guest to the PulseAudio server of Termux. |
| `QT_QPA_PLATFORM=xcb` | Makes Qt applications use X11. |

## `linuxdeskd`

A small JSON line server on `127.0.0.1:7801`.

```jsonc
// request
{"cmd": "start", "args": ["main", "--distro", "ubuntu"]}
// response
{"ok": true, "output": "...", "error": ""}
```

Commands: `ping`, `status`, `session-status`, `start`, `stop`, `resize`,
`exec`, `logs`, `distro-list`, `distro-install`, `bootstrap`, `selftest`,
`raw` and `quit`.

The daemon runs the CLI in a subprocess, so a long running command (for
example `distro install`) simply keeps the connection open until it is
finished.

## How the app installs the engine

The `com.termux.app.execute` broadcast can only carry a limited amount of
text, so the app:

1. creates `$HOME/.local/share/linuxdesk`,
2. splits the base64 of each script into chunks of 3000 characters and
   appends them to `<script>.b64`,
3. decodes the file and makes it executable,
4. adds the directory to `PATH` in `.bashrc`,
5. starts `linuxdeskd`.

The user has to allow external apps in the Termux settings once
(`Settings → Allow external apps`).

## Notes and limits

* `proot-distro` is a user space container, not a virtual machine. There is
  no systemd, no Docker, no FUSE and no kernel modules.
* A session is bound to one distribution. Switching distributions means
  starting a new session.
* The VNC server is bound to the loopback interface, so the desktop cannot
  be reached from the network unless the port is forwarded.
* On some devices the Android "child process restrictions" kill background
  processes. The fix is to disable them in the developer options or to run
  `device_config put activity_manager max_phantom_processes 2147483647`.
