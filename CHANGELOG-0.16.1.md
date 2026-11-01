# Hakatron 0.16.1 — the install actually installs, and Build Studio stops hiding its work

**Built:** 2026-11-01 · versionCode **18** · `dev.devstudio.ide` · label `Hakatron`
**APK:** 29,904,264 bytes · sha256 `bc774baec7143e8f01d107ac074d6ab5cd5c8a55c1d74407efc1b28b702685af`
**Tests:** 92 / 92 green · **ABIs:** arm64-v8a · armeabi-v7a · x86 · x86_64

Everything here comes from the four screenshots you sent: `Download failed: HTTP 404`,
seven grey tool pills under "Alpine guest ready", and a Build Studio that gave you a
page of paste boxes, then "NOTHING TO BUILD YET", with `=== paste_here.kt ===` naming the
files.

---

## 1. Termux install: the 404 is fixed, and the install now actually produces a userland

**The 404.** 0.16.0 pinned `bootstrap-2024.10.24`, a release the Termux project no longer
publishes. The pin is now `bootstrap-2026.11.01-r1+apt.android-7` — the current release,
with its published checksums:

| ABI | asset | size | sha256 (first 12) |
|---|---|---|---|
| arm64-v8a | bootstrap-aarch64.zip | 31.6 MB | `5a1429b6c68e…` |
| armeabi-v7a | bootstrap-arm.zip | 28.4 MB | `99660c5a9573…` |
| x86 | bootstrap-i686.zip | 30.7 MB | `3ebf0424a9ff…` |
| x86_64 | bootstrap-x86_64.zip | 31.5 MB | `c0321730de94…` |

The tag contains a `+`, which must be percent-encoded (`%2B`) in the download path — that
alone breaks the URL. If a pinned release is ever withdrawn again, the installer now asks
the Termux project for its current release and downloads that instead of showing you an
HTTP status code.

**The unpack was wrong, and that was the bigger bug.** The bootstrap is *not* a normal
unix tar: it contains **zero symlinks** and instead ships `SYMLINKS.txt` naming **1,213**
of them, plus 20 links that point at Termux's absolute
`/data/data/com.termux/files/usr/...`. Copying the zip out with `cp -a` produces a tree
where `bin/sh`, `bin/zipinfo` and the apt keyring dangle — a userland that looks installed
and then cannot run.

So the whole unpack moved off the guest and into Kotlin, on the host:

* unzip into `files/termux/usr.tmp` with a zip-slip guard on every entry;
* `markExecutables()` sets `+x` from **content** (ELF header or `#!`) under `bin/` and
  `libexec/` — `ZipEntry` cannot report unix modes in Java, so inferring from content is
  the only honest way;
* `parseSymlinks()` reads `SYMLINKS.txt` (`target←path`) and `Os.symlink` creates all
  1,213 links;
* the tree is **renamed** into `$PREFIX`, then `remapPaths()` rewrites the 20 absolute
  Termux link targets and any shebangs onto our prefix.

`Termux symlinks 0` is not something I can claim from a phone I do not have, so the
algorithm was run here against the **real** archive, step for step:

```
symlinks=1213 execs=304 remapped=20 dangling_after_remap=0
bin/sh OK · bin/bash OK · bin/apt OK · bin/pkg OK · bin/script OK
bin/zipinfo OK · bin/dash OK · etc/apt/trusted.gpg.d/termux-autobuilds.gpg OK
lib/libssl.so OK · lib/libncursesw.so.6 OK        (96 MB unpacked)
```

That is the same sequence the Kotlin performs (`java.util.zip` + `Os.symlink` instead of
`unzip` + `os.symlink`), and it is why the 404 was not the only thing standing between you
and a working shell.

**Also fixed:** the install no longer needs the Alpine engine at all (it runs in Kotlin, so
the "set the engine up first" gate is gone), it refuses up front if there is not ~160 MB
free instead of dying mid-copy, and it deletes the 32 MB zip after a successful install.

## 2. The grey tool pills were telling the truth — now they are actionable

Your guest has **14 apk packages** (busybox, musl, apk-tools, ca-certificates and friends).
There is no gcc, make, python3, node, javac, rustc or git in it, so those pills being grey
was correct: the engine was ready, the tools were simply not installed. The screen hid that
by showing seven grey chips with no explanation.

* Header now states the facts: `TOOLS · 0/7 installed · guest has 14 packages`.
* Each grey pill became a button: tap `＋ gcc` to `apk add gcc` from Alpine's repos.
  Libraries map to the right package (`node` → `nodejs`, `javac` → `openjdk17`,
  `rustc` → `rust`), the pill shows `gcc …` while it runs, and the count refreshes after.
* The last line of apk's output is shown, so a failure is a message, not a silent no-op.

## 3. Build Studio: three panes instead of one 4,000-pixel scroll

The old studio stacked paste fields, the plan, the build output and the preview link on a
single page, so the log was always somewhere off-screen and "Build" sat above a wall of
text. It is now a tab strip with **exactly one pane composed at a time**:

* **Compose** — screenshot OCR, templates, structure, parts, notes, Analyze.
* **Plan** — the match, evidence, predictions, review, steps, and the Build button. The tab
  stays disabled until a plan exists, so you cannot press Build on nothing.
* **Log** — live progress (a determinate-looking bar plus the current step) and every line
  the build produced, colourized by level.

Pressing **Build** switches to **Log** by itself: the thing you are waiting for is the thing
you see. Building also clears the previous result first, so an old "Build finished" card
cannot sit under a run that is still going.

### The file name bug

`=== paste_here.kt ===` was a literal placeholder, which is why a Python tree plus that
header was planned as a "Kotlin project". Now:

* `suggestFileName()` reads the tree (and the notes) and proposes a real name —
  `myapp/app.py` → `main.py`, `Cargo.toml + src/main.rs` → `main.rs` (source extensions beat
  manifests, so a Rust project is not named after its `toml`), and unknown trees get
  `main.txt`, which is obviously generic rather than misleadingly wrong.
* `renamePlaceholder()` replaces a stale `=== paste_here.<ext> ===` header with that name,
  and leaves genuine headers (`=== app.py ===`) untouched.
* "Move it to the parts" (for the code-in-step-1 warning) now writes the suggested name too.

## 4. Tests — 92, 0 failures

| Suite | Count | What it pins |
|---|---|---|
| AnalyzerTest | 12 | stack detection |
| KnowledgeBaseTest | 17 | fix knowledge |
| PlannerTest | 12 | plan/build |
| **BuildNamingTest** | **7** | name inference per language, placeholder rewrite |
| **TermuxSymlinksTest** | **4** | `SYMLINKS.txt` parsing against real lines |
| **TermuxRemapTest** | **3** | absolute Termux link targets rewritten, relative ones left |
| **TermuxUserlandTest** | **10** | release tag encoded, no 2024.10.24, checksums present, ABI order incl. x86 → i686, sha256 |
| TreeReaderTest | 9 | OCR tree |
| VtTest | 18 | terminal emulator |

## 5. Unchanged on purpose

Devos still never edits your source. Unbuildable stacks are still refused with a reason
rather than a fake success. No mock runners. And nothing about Termux is claimed until
`$PREFIX/bin/bash` is actually there — which is exactly why the 404 was reported to you
instead of being swallowed.
