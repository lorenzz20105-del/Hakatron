# Hakatron 0.5.0 (formerly DevStudio)

APK: `release/Hakatron-0.5.0-release.apk` · 11,753,940 B
Package stays `dev.devstudio.ide` (code 5) → installs straight over 0.4.0, keeps your data.
Label on the home screen is now **Hakatron**.

## What changed

**Name**
- App label, in-app branding, shared folder (`/sdcard/Download/Hakatron`) and the entry composable renamed. Package id and signing key untouched, so it is an in-place upgrade, not a new app.

**UI — rebuilt to the mockup**
- Five tabs with hand-drawn vector icons (house / folder / chevrons / prompt / sliders) instead of glyph placeholders: **Home, Files, Editor, Shell, System**.
- Home: gradient hero ("Continue" card that follows your last file), 4-tile KPI bento row (engine, files, storage, fixes learned), two rows of quick-action tiles, Termux card, recent files.
- Files: guest projects ↔ shared folder switcher, one-tap open in the editor.
- Shell: live persistent Alpine shell (real `cd`, real env vars), quick-command chips, ANSI-stripped output.
- Build Studio / Termux / Preview open as full-screen flows with a Close action instead of being buried in tabs.

**System tab — improved and made honest**
- Engine hero: state, size on disk, one-tap install, **Self-test** that prints the guest's own answers.
- **Toolchain matrix**: gcc, make, python3, node, javac, rustc, git — each detected with `command -v` inside the guest, missing ones install in one tap with the correct package (`build-base`, `openjdk17`, `rust`, …).
- Storage row: free space, guest size, shared-folder file count, log trimming.
- Shared folder card with permission state and sample seeding.
- Termux card: installed / genuine / version, distro list, refresh.
- About: version, package, device, ABI, and a plain statement that PRoot is ptrace-based and therefore slower than native.

**Termux (real, still optional)**
- Signature check against F-Droid / GitHub builds; RUN_COMMAND path; 8 one-tap `pkg install` tiles; custom command box.
- **proot-distro one-taps**: Ubuntu, Debian, Kali, Alpine, Arch — installs inside Termux, list written to the shared folder and shown here.
- **Output capture**: builds sent to Termux are `tee`'d into `…/Hakatron/last-build.log`, and Hakatron reads that file (Termux runs as its own UID, so streaming its screen is impossible — this is the honest workaround, and it works).

**Shared folder**
- `…/Hakatron/shared` is read/write for both apps; Hakatron seeds `hello.py` + `build.sh`; guest `/root/shared` is the same place.

## Not done
- GitHub release not published — no API token in this session. Tell me and I'll push `hakatron-v0.5.0` with the APK attached.
