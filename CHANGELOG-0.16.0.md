# Hakatron 0.16.0 — rebuilt UI, real Termux inside the APK

**Built:** 2026-11-01 · versionCode **17** · `dev.devstudio.ide` · label `Hakatron`
**APK:** `hakatron-0.16.0.apk` — 29,890,056 bytes · sha256 `2755dc2dbed35683ba89455ef0c8320877c8442d5ae6ce0eaa2c7cc71cb5fc22`
**ABIs:** arm64-v8a · armeabi-v7a · x86 · x86_64 · **Size on disk:** 28.5 MB
**Tests:** 75 / 75 green (AnalyzerTest 12, KnowledgeBaseTest 17, PlannerTest 12, TermuxUserlandTest 7, TreeReaderTest 9, VtTest 18)

---

## 1. The overlay is gone — structurally, not cosmetically

You were right: the old app drew the current tab **and then stacked Build Studio and the
Termux screen on top of it**, so two screens were visible at once and the old one showed
through the new one.

That is fixed at the root, not with z-indexes:

* `MainActivity` now has **one** `setContent { HakatronApp() }`. No dialogs-as-screens, no
  second activity, no fragment stack.
* `HakatronApp` holds a single `Route` state. `AnimatedContent` composes **exactly that one
  route**. There is no code path that renders two destinations, because there is only one
  slot to render into.
* Build Studio and Preview are ordinary routes (`Route.Build`, `Route.Preview`) reached from
  the dock and from the dashboard — not panels layered over something else.

If a future change tries to overlay a screen, it cannot do it without deleting the
`AnimatedContent` — the architecture doesn't leave room for the old bug.

## 2. Complete UI rebuild

New design system in `ui/Kit.kt`, new frame in `ui/AppShell.kt`, new Files screen in
`ui/Files.kt`, new System screen in `ui/System.kt`. `ui/Shell.kt` — the file that contained
the old shell — is now an empty stub, so nothing can accidentally render the old frame.

**Layout**
* Floating **dock** instead of a navigation bar: five destinations, the active one grows
  from 34 → 52 dp on a spring, plus a raised `＋` that opens Build Studio from anywhere.
* `Screen()` page frame: accent bar, title, subtitle, actions — every screen now starts the
  same way, so moving between them reads as one app instead of four.
* Tonal surfaces in three steps (`ink → surface → surface2`), hairlines instead of shadows,
  one accent per intent: violet = act, cyan = inspect, mint = healthy, amber = caution,
  rose = stop. Colour never carries meaning alone — every state also has a word.

**Motion** (`ui/Motion.kt`, Material 3 Expressive physics)
* Two token families, as the spec requires: **spatial** (spring, damping 0.85, stiffness 220,
  may overshoot) for position/size, and **effect** (`tween`) for colour and opacity — a
  colour that bounces looks like a glitch.
* `responsive()` (500 / 0.9), `expressive()` (380 / 0.72), `spatial()` (220 / 0.85).
* Every press dips to 0.96 on the expressive spring; entering cards stagger 40 ms apart and
  rise 14 dp; transitions animate through `graphicsLayer` only, so no frame triggers relayout.
* **"Remove animations" in the OS is respected**: the route transition collapses to a 0 ms
  swap when animators are disabled (`rememberAnimationsEnabled`).

## 3. No Termux bridge — real Termux inside the APK instead

**Removed**
* `termux/TermuxBridge.kt` — all `RUN_COMMAND` intents, all permission requests, all
  "open Termux and run this" hand-offs. Deleted.
* `ui/TermuxScreen.kt` — deleted.
* `com.termux.permission.RUN_COMMAND` and the `<queries><package com.termux>` block — gone
  from `AndroidManifest.xml`. Hakatron no longer knows or cares whether the Termux app is
  installed.
* `MainActivity` no longer asks for that permission at runtime.

**Added — `core/TermuxUserland.kt`**
* Downloads the **official Termux bootstrap** (`bootstrap-{aarch64,arm,x86_64}.zip` from
  `github.com/termux/termux-packages/releases`), matched to the device ABI by preference
  order, and installs it **inside Hakatron's own data directory**:
  `$PREFIX = <files>/termux/usr`, `$HOME = <files>/termux/home`.
* Unpacking runs through the guest's own `unzip`, because a Termux bootstrap carries
  symlinks and executable modes that Java's zip reader cannot express. It stages in the
  shared folder, then is **renamed** into place — same filesystem, so modes and symlinks
  survive intact.
* `remapPaths()` rewrites the bootstrap's hard-coded `/data/data/com.termux/files/usr`
  symlinks and script shebangs to our prefix; binaries are untouched because they resolve
  libraries through `$PREFIX` and the RUNPATH Termux built in.
* Termux's own environment is set (`PREFIX`, `TERMUX__PREFIX`, `TERMUX__ROOTFS`, `HOME`,
  `LD_LIBRARY_PATH`, `TMPDIR`, `PATH`, `TERM`, `SHELL`), plus `etc/apt/sources.list`,
  `.bashrc` and `termux.properties`.
* **The shell prefers it**: `Engine.termuxSession()` runs the Termux `bash` natively
  (Bionic binaries, no ptrace, faster than the Alpine guest). If it is not installed, the
  bundled Alpine guest runs — and the terminal says which one you are in.
* **Honest about ptys**: if the userland has Termux's `script(1)` (util-linux), the shell
  gets a real pseudo-terminal; otherwise stdio is piped and the terminal shows `pipes`
  instead of `pty`. The header always shows both facts: `termux · native` or `alpine · proot`,
  and `pty` or `pipes`.
* **Honest about size**: ~58–64 MB download, ~200 MB on disk, fetched once, only when you
  press Install. Nothing about Termux state is ever shown unless
  `TermuxUserland.isInstalled()` is true (i.e. `$PREFIX/bin/bash` exists and is non-empty).

> Design note: the bootstrap is **fetched, not bundled**, so the APK stays 28.5 MB instead
> of ~92 MB. The install button, the progress, the size estimate and the failure text all
> live in System. If you want the 62 MB aarch64 bootstrap baked into the APK for offline
> first-run, that is a one-line asset change — say the word.

## 4. The rest, improved

* **Files** — rebuilt. Guest/shared segmented switch, real `＋ file` / `＋ dir` / rename /
  delete done in place on the selected row (no hidden `⋯` menu), skeleton loading, engine
  notes when nothing is set up yet, and per-root operation messages.
* **System** — rebuilt around `TermuxUserland`: engine card with a real `diagnose()` dump,
  tool pills, storage/free/shared tiles, the Termux install with phase + byte progress, and
  an About card that states what the app actually does. It no longer instructs you to go
  install another app.
* **Terminal** — new header with two truthful pills (userland + pty/pipes), remapped onto
  the new palette, real pty preserved via the bundled `script(1)` in the Alpine guest.
* **Editor / Build Studio** — remapped to the new tokens through the legacy aliases in
  `Editor.kt`, which now point at `Pal`, so every old screen adopted the rebuilt palette in
  one move instead of being half-restyled. Build Studio is now a route, not an overlay.
* **Colour system** — `Pal.ink #07080C`, `surface #0F1117`, `surface2 #171A23`,
  `surface3 #1F2431`, `line #252B39`; text `#EDEFF6`, dim `#959DB1`, faint `#5E6678`;
  violet `#7C5CFF`, cyan `#22D3EE`, mint `#3DDC97`, amber `#FFB84D`, rose `#FF6B81`.

## 5. Tests

| Suite | Count |
|---|---|
| AnalyzerTest | 12 |
| KnowledgeBaseTest | 17 |
| PlannerTest | 12 |
| **TermuxUserlandTest (new)** | **7** |
| TreeReaderTest | 9 |
| VtTest | 18 |
| **Total** | **75 · 0 failures** |

The new suite proves the parts of the userland we control: every build points at an official
Termux release URL with a matching arch, ABI preference order picks the right bootstrap
(arm64 device → aarch64, 32-bit arm → arm, x86_64 → x86_64, unknown → still a real URL), and
`sha256` matches a known digest and returns `""` for a missing file instead of throwing.

## 6. Not changed, on purpose

Devos still never edits your source — only config files, and only when the error text says
so. Unbuildable stacks are still refused honestly. No mock runners anywhere. Nothing is
uploaded; the only network calls are the bootstrap download you asked for.
