# Hakatron 0.7.0 — "Editor"

versionCode 7 · 11,776,096 B · package `dev.devstudio.ide` (unchanged → upgrades over 0.6.0 in place)

This release is the code editor itself. Everything below was dead code, missing,
or actively losing your work before.

## Typing

* **Real undo / redo.** The ⤺ button used to do nothing ("handled by system
  keyboard"). Now there is a 200-step history with working **⤺ undo** and
  **⤼ redo**, and line-duplicate / move-line / comment / symbol inserts all go
  through it too.
* **Smart brackets and indent.** `(`→`()`, `[`→`[]`, `{`→`{}`, `"`→`""`, `'`→`''`
  with the caret in the middle; typing a closing bracket steps over the one that
  is already there; ⏎ keeps the current indentation and adds a level after
  `{`, `(`, `[` or `:`.
* It only reacts to a single character at a collapsed caret, so paste, IME
  composition and multi-line edits are passed through untouched.

## Tabs that don't lose work

* Every tab keeps an in-memory **draft**. Switching tabs and coming back restores
  your unsaved edits instead of re-reading the file from disk.
* Closing a tab with unsaved edits now asks **Discard / Keep editing** instead of
  silently dropping them.
* Open tabs and the last active file are **persisted**, so the editor reopens
  where you left off after the app is killed.

## Layout

* The **Wrap** button finally does something. Wrapping was stored in prefs but
  never used; off = one row per line with horizontal scrolling, on = soft wrap.
* **Line numbers** highlight the current line, and only render when they can't
  drift (wrap off). With wrap on, the status bar carries **Ln / Col**.
* Word wrap now defaults to off, like a code editor rather than a notes app.

## Finding things

* **Find** in the quick bar opens **project-wide search**: it greps inside the
  guest (`~/projects`, skipping `node_modules`, `.git`, `build`, `dist`,
  `target`, `.gradle`) and lists `file:line — text`. Tapping a hit opens that
  file **at that line**.
* In-file find & replace now records history, so a mistaken *replace all* is one
  undo away.

## Files

* `Hakatron-0.7.0-release.apk` — release build, same signing key as 0.1.0 → 0.6.0.
