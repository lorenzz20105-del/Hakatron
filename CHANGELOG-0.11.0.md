# Hakatron 0.11.0 — incremental highlighting, file operations, Termux log capture

versionCode 11 · package `dev.devstudio.ide` (unchanged → upgrades in place)

## Editor: highlighting is incremental now

`Highlighter` was re-written around a per-line cache:

* the text is split into lines and each line is coloured **once**, cached under
  `(language, block-comment-open-at-line-start, line text)`;
* an edit re-colours only the lines that changed — typing in a 3,000-line file is
  a couple of lines of work instead of a full-file scan;
* the open block-comment state is part of the key, so multi-line comments still
  colour correctly and scrolling back hits the cache;
* a whole-result memo means an unchanged frame does **no** highlighting work;
* the cache is bounded (20k lines) so it cannot grow without limit.

This is the "option 1" fix from the editor-library discussion — no new
dependency, no APK weight, and the VFS/undo/drafts all stay as they are.

## Files: create, rename, delete

The Files tab could only open files. Now, inside the guest tree:

* **＋file** / **＋dir** in the header (full guest path, mkdir -p under the hood);
* every row has **⋯** → **Rename** (mv) or **Delete** (rm -rf), with the path shown;
* results are reported honestly (`created …` / `delete failed`), then the list reloads.

## System: Termux build output, captured

Termux is a different app with its own screen, so the only way to see what it did
is a file. The generated build script now runs inside a block whose output is
**teed to `/sdcard/Download/Hakatron/last-build.log`**, and the System tab has a
**Last Termux build** card that shows the tail of it, with Reload and Clear.

Already in place from earlier releases and still working: proot-distro install /
login commands with `proot-distro list --installed` mirrored into `distros.txt`,
the shared folder with the All-files-access grant, and seeded samples.

## Also

* Build Studio shows **phase chips** after a build (`✓ install ✓ build ✓ test
  ✓ run ✓ verify`), with a `done` badge only when every phase passed.
* `SharedFiles` now has one documented contract (ensure / list / read / write /
  buildLog / clearLog / distros / seed) used by every screen.

## Proof

* Compiles clean (`:app:compileReleaseKotlin`), release APK built and signed with
  the same key.
* Engine unchanged: corpus **61 projects · 55 built · 6 honest stops · 0
  unexpected**, real Alpine guest **33/33**.
