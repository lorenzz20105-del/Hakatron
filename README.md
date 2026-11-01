# Hakatron

An Android code studio that actually **builds** things.

Hakatron is a code editor, a project planner and a terminal that runs a real Linux
userland on the phone — plus **Devos**, the trained layer that reads a project,
works out how it is built, runs the build, and fixes what the compiler actually
says. No mock runners, no fake progress bars.

**Latest release: [0.12.0](https://github.com/lorenzz20105-del/Hakatron/releases/latest)** — `Hakatron-0.12.0-release.apk` (18.0 MB, arm64-v8a, Android 7.0+)

```
package: dev.devstudio.ide
label:   Hakatron
signed with the same key since 0.1.0 — upgrades in place, keeps your projects
```

## What it does

### Editor
Kotlin/Compose editor with incremental syntax highlighting (60+ languages via a
line cache), a virtualised gutter, large-file mode, go-to-line, find & replace
with regex, undo/redo, per-file drafts, and a real filesystem for the guest
(`GuestFiles`) with new file / new folder / rename / delete.

### Build Studio
1. **structure** — paste a tree, or **read one from a screenshot** (`image/*`,
   on-device OCR, works on 46,000-pixel-tall chat images),
2. **the parts** — paste files one by one, or the whole project with
   `=== path ===` headers,
3. **notes** — "Tools needed first: gcc, make / Build: make / Run: ./app".

Devos turns that into an ordered plan: install → build → test → run → verify,
with predictions and honest warnings before a single command runs.

### Devos (the trained layer)
- **59 fix rules** in a knowledge base, 33 of them learned from real build logs.
- **Learned memory** — a fix that worked once is tried first next time
  (persisted in the app, seeded from the training corpus).
- Reads what the project *declares* instead of guessing: `package.json` scripts,
  `Makefile` targets, `CMakeLists.txt` + CTest, `mvn`/`gradle` wrappers.
- Hard rule: it may patch **config** files (`tsconfig.json`, `package.json`,
  `CMakeLists.txt`). It never rewrites your source.
- Unbuildable stacks are refused honestly instead of faking success.

Training results (offline corpus, 56 real-shaped projects): **50 built, 6 honest
stops, 0 surprises**. Real-sandbox suite: **30/30** across 19 stacks, including
genuine multi-minute builds.

### Terminal & engines
- **Built-in engine** — PRoot + Alpine, ships in the APK. Works standalone: no
  daemon, no Termux, no root.
- **Termux bridge** (optional) — generates a script that builds inside your
  existing Termux install and tees the output to
  `/sdcard/Download/Hakatron/last-build.log`, which the app reads back.
- Distro picker, shared folder access, storage & toolchain self-test.

## Install

1. Download the APK from the [latest release](https://github.com/lorenzz20105-del/Hakatron/releases/latest).
2. Allow "install unknown apps" for your browser/file manager.
3. Open Hakatron → **System** → let it set up the Linux engine (needs ~600 MB
   free; it unpacks Alpine on first run).

Android 11+ asks for all-files access so the shared folder
(`/sdcard/Download/Hakatron`) works — that is where builds export their logs.

## Version history

| version | what landed |
|---|---|
| 0.12.0 | read a whole project tree out of a screenshot (on-device OCR, tiled decoding, brace expansion, rank-based indentation) |
| 0.11.0 | Termux build-log card: the last build, read straight from the shared folder |
| 0.10.0 | shared-folder rewrite, file CRUD in the Files screen, phase chips in Build Studio |
| 0.9.0 | incremental highlighter (line cache) — typing stopped repainting the file |
| 0.8.0 | Devos v10 rules: package.json / Makefile / CTest declarations |
| 0.5.0 – 0.7.0 | Hakatron rename, five-tab shell, System screen, Termux distros |

Full notes: [`CHANGELOG-0.12.0.md`](https://github.com/lorenzz20105-del/Hakatron/releases).

## Built with

Kotlin + Jetpack Compose, ML Kit (bundled Latin OCR, on-device), PRoot, Alpine.
Package: `dev.devstudio.ide`. minSdk 24, targetSdk 34.

## Licence / credits

PRoot is GPL-2.0 (see its own sources). The Alpine rootfs is distributed under
Alpine's licences. Andronix's open-source scripts (MIT) informed the engine
bootstrap and are credited in the app's Help screen.
