# Hakatron 0.10.0 — VFS + an editor that survives big files

versionCode 10 · package `dev.devstudio.ide` (unchanged → upgrades in place)

This release takes the three warnings that actually matter on a phone — memory,
disk round-trips, and large files — and fixes them in the app we already have.

## 1. A VFS, not "read the file again"

New `core/Vfs.kt`: an in-memory file system for open documents.

* Opening a tab reads the guest **once**. Switching back to it is a memory hit,
  not another PRoot round-trip (each one costs ~0.4 s and is what made tab
  switching stall on a 4 MB file).
* `flush()` writes back **only if the buffer differs from disk**, so 🖫 Save on an
  untouched file is a genuine no-op instead of a shell call.
* Dirty buffers are never evicted; clean ones are, under a 24-file / ~8 MB cap.

## 2. The editor no longer lays out the whole file

* Line splitting is cached per text change (`remember(doc.text)`) instead of
  running on every frame.
* **The gutter is virtualised** — only the ~24 lines around the viewport are
  composed, with spacers keeping the scroll height exact. A 5,000-line file used
  to compose 5,000 `Text` nodes.
* **Large-file mode**: above 1,500 lines, syntax highlighting switches itself off
  (a per-frame cost you cannot afford on mobile) and the status bar says
  `large · highlight off` in amber instead of silently getting slow.
* The status bar now reports the real line count next to Ln/Col.

## 3. Go to line

Big files need navigation, not just scrolling: **Ln** in the quick bar jumps to any
line in the open file.

## What we deliberately did not do

No WebView, no WebContainers, no embedded browser engine: the ~350 MB tab ceiling
is real and the whole toolchain here is native PRoot + Alpine, which is why builds
run offline and at full speed.

## Proof

* Real Alpine chroot: **33/33** checks · 19/19 toolchains installed for real ·
  13 real project builds (flask+postgres, plain and modular CMake/C++, npm
  scripts, CTest, Go, Rust, sqlite, nbconvert, native gem, Composer, honest
  dotnet refusal).
* Corpus: **61 projects · 55 built · 6 honest stops · 0 unexpected**.
