# Hakatron

[![CI](https://github.com/lorenzz20105-del/Hakatron/actions/workflows/ci.yml/badge.svg)](https://github.com/lorenzz20105-del/Hakatron/actions/workflows/ci.yml)

An Android code studio that actually **builds** things.

Hakatron is an editor, a project planner and a terminal that runs a real Linux
userland on the phone — plus **Devos**, the trained layer that reads a project,
works out how it is built, runs the build, and fixes what the compiler actually
says. No mock runners, no fake progress bars.

**Latest release: [0.13.0](https://github.com/lorenzz20105-del/Hakatron/releases/latest)** —
`Hakatron-0.13.0-release.apk` · 29.8 MB · universal (`arm64-v8a`, `armeabi-v7a`,
`x86`, `x86_64`) · Android 7.0+ · signed with the same key since 0.1.0, upgrades in place.

```
package: dev.devstudio.ide
label:   Hakatron
```

## What it does

### Editor
Kotlin/Compose editor with incremental syntax highlighting, a gutter, large-file
mode, go-to-line, find & replace, undo/redo, per-file drafts and a real
filesystem for the guest (new file / folder / rename / delete).

### Build Studio
1. **structure** — paste a tree, or **read one from a screenshot** (`image/*`,
   on-device OCR; works on 46,000-pixel-tall chat images),
2. **the parts** — paste files one by one, or the whole project with
   `=== path ===` headers,
3. **notes** — "Tools needed first: gcc, make / Build: make / Run: ./app".

Devos turns that into an ordered plan — install → build → test → run → verify —
with predictions and honest warnings before a single command runs.

### Devos (the trained layer)
- **50+ fix rules** in a knowledge base, most of them learned from real build logs.
- **Learned memory** — a fix that worked once is tried first next time.
- Reads what the project *declares* instead of guessing: `package.json` scripts,
  `Makefile` targets, `CMakeLists.txt` + CTest, `mvn` / `gradle` wrappers.
- A refused connection is attributed **by port** (5432 → PostgreSQL, 6379 → Redis,
  3306 → MariaDB), not by habit.
- It may patch **config** files (`tsconfig.json`, `package.json`,
  `CMakeLists.txt`). It never rewrites your source.
- Unbuildable stacks are refused honestly instead of faking success.

### Terminal & engines
- **Built-in engine** — PRoot + Alpine, ships in the APK. Standalone: no daemon,
  no Termux, no root.
- **Termux bridge** (optional) — builds inside an existing Termux install and tees
  the output to `/sdcard/Download/Hakatron/last-build.log`, which the app reads back.
- Distro picker, shared-folder access, storage & toolchain self-test.

## Install

1. Download the APK from the [latest release](https://github.com/lorenzz20105-del/Hakatron/releases/latest).
2. Allow "install unknown apps" for your browser/file manager.
3. Open Hakatron → **System** → set up the Linux engine (~600 MB free; Alpine
   unpacks on first run).

## Testing

```bash
./gradlew :app:testReleaseUnitTest      # 42 tests, no emulator
./gradlew :app:assembleDebug            # debug APK
./gradlew :app:assembleRelease          # signed if app/keystore/devstudio.jks exists
```

The reasoning layer is pure Kotlin, so `Analyzer`, `Planner`, `KnowledgeBase` and
`TreeReader` are covered by JVM tests: every rule is asserted against a real error
line, and the honesty rules are asserted too — `docker` is refused, a syntax error
never runs a command, unrecognised output returns *no* fix, and a wrapped line in
a screenshot can never shift the tree levels below it.

CI runs the tests, `assembleDebug` and `assembleRelease` on every push and PR. The
release keystore is deliberately not in git: builds are signed locally, unsigned in
CI, and the pipeline still verifies the whole app compiles and packages.

## Version history

| version | what landed |
|---|---|
| 0.13.0 | 42 unit tests + GitHub Actions CI; **universal APK** (0.12.0's arm64-only build was refused on 32-bit phones); service fixes chosen by port; empty `requirements.txt` no longer hides imports |
| 0.12.0 | read a whole project tree out of a screenshot (on-device OCR, tiled decoding, brace expansion, rank-based indentation) |
| 0.11.0 | Termux build-log card |
| 0.10.0 | shared-folder rewrite, file CRUD, phase chips |
| 0.9.0 | incremental highlighter (line cache) |
| 0.8.0 | Devos v10 rules: package.json / Makefile / CTest declarations |
| 0.5.0 – 0.7.0 | Hakatron rename, five-tab shell, System screen, Termux distros |

Full notes live in the [releases](https://github.com/lorenzz20105-del/Hakatron/releases)
and in `CHANGELOG-*.md`.

## Built with

Kotlin + Jetpack Compose, ML Kit (bundled Latin OCR, on-device), PRoot, Alpine.
`dev.devstudio.ide` · minSdk 24 · targetSdk 34.

## Licence / credits

PRoot is GPL-2.0; the Alpine rootfs follows Alpine's licences. Andronix's
open-source scripts (MIT) informed the engine bootstrap and are credited in the
app's Help screen. The Andronix APK itself is not modified or redistributed here.
