# Hakatron 0.17.0 — editor and shell rebuilt as real tools, and no old chrome left

**Built:** 2026-11-01 · versionCode **20** · `dev.devstudio.ide` · label `Hakatron`
**APK:** 29,923,992 bytes · sha256 `ebb8f5e1a9ce508e47670f4f2c5bc6a07b32c7a054ea47c4b43fcc2ff1b70f2e`
**Tests:** 108 / 108 green · **ABIs:** arm64-v8a · armeabi-v7a · x86 · x86_64

You said the editor and shell still looked wrong, and that old chrome kept appearing. Both
were true: Editor and Shell were the last two screens still drawn with the 0.15-era layout,
and every dialog in the app was still a bare Material `AlertDialog`, which is why a rebuilt
screen still ended in a widget that looked two versions old.

---

## 1. Editor — now laid out like an editor, not a text box with buttons

**Header.** A 4 dp accent bar, then the file name with a `modified`/`saved` pill beside it
and the folder underneath, then four square actions (explorer, find, git, new). Save state
is visible without reading a status line.

**Tabs.** Pill chips instead of rectangles-with-a-border. The active tab carries the accent
and its close button; unsaved tabs show an amber dot.

**Explorer.** The old "Files" sheet was a flat search list. It is now an explorer panel:
an `EXPLORER` header with a file count and a `＋` new-file button, the same `find a file…`
well, and rows **indented by path depth** with the open file marked. Files and folders are
distinguishable at a glance instead of being one undifferentiated column.

**Writing surface.** The page is one shade below the ink (`#0A0C12`), the gutter numbers are
dim except on the caret line, which goes violet and bold, and the divider is a hairline.
Line numbers stay virtualised, so a 5,000-line file still scrolls.

**Bottom panel.** Problems and Output are now **one panel with two tabs**, closed by default.
Before, problems were an inline amber strip and output was a drawer that pushed the editor
around; the status bar now has a `panel`/`output` toggle so you can open it in one tap.
Problems shows the same `QuickReview` checks Build Studio uses; Output shows the last run.

**Action rails.** Grouped by intent with hairline dividers — undo/redo │ line tools │ view —
and then only two filled buttons on the whole screen: **Save changes** (mint, disabled when
nothing is dirty) and **Save & run** (violet). Everything else is a quiet tonal pill.

**Empty state.** A gradient tile, "Nothing open", and two action tiles that say how many
files the guest actually has and open the explorer or the template picker.

**Status bar.** Language pill, `Ln 42:7`, line and character counts, the panel toggle, and a
mint/amber dot with `saved` / `modified`.

## 2. Shell — a terminal, not a text pane with chips

**Header.** A glyph tile whose colour *is* the state (mint alive, rose exited, grey no
engine), the terminal's own title from the VT emulator, and two truthful pills: `pty`/`pipes`
and `live`/`exited`. The subtitle says which userland you are actually in — `termux · native
binaries` or `alpine · proot guest` — never a guess.

**Toolbar.** `A-` `A+` font, `follow on/off`, and `restart`.

**Grid.** One column narrower padding, a proper `↓ latest` button that appears when you have
scrolled away from the tail, and tapping anywhere focuses the invisible key sink so the IME
opens where you tapped.

**Keys.** `CTRL` arms violet, `ESC` amber, `^C`/`^D` rose, `⏎` mint — the same spring press
as the rest of the app instead of flat bordered boxes. Quick commands are tonal pills.

## 3. No old chrome anywhere

Material's `AlertDialog` and `OutlinedTextField` were the last old-looking pieces, because
every screen uses one. They are replaced by two kit primitives:

* **`HkDialog`** — one shape (26 dp corners), one tonal surface, accent bar in the title,
  filled confirm + quiet dismiss, and the label/colour chosen per intent. Used by find &
  replace, go-to-line, new file, git, project search, unsaved-changes, and file
  create/rename — seven call sites, one look.
* **`HkField`** — the app's only text field: ink well, hairline border, monospace by default.

Build Studio's four paste boxes now use `HkField` too, so the last `OutlinedTextField` is
gone. `Preview` gained the standard `Screen` frame, a reload button and a kit close button.

## 4. Everything else that changed

* `ui/Kit.kt` grew `HkDialog` + `HkField`; that is the only dialog/field in the app now.
* Terminal state is read from the live `ShellSession` (`userland`, `mode`) rather than
  assumed, so the chips cannot drift from reality.
* Files' create/rename dialog moved to `HkDialog` + `HkField` with a note about where the
  file lands.

## 5. Tests

**108 / 108**, unchanged suites plus the three added for the last release:
Analyzer 12 · BuildNaming 9 · KnowledgeBase 17 · PartsSplit 10 · Planner 12 ·
TermuxRemap 3 · TermuxSymlinks 4 · TermuxUserland 10 · TreeReader 9 ·
TreeReaderVerdict 4 · Vt 18.

## 6. Honest limits

The editor still has no autocomplete and no inline error squiggles — it highlights syntax,
indents, auto-pairs, reviews the file for known mistakes and runs it. The terminal's pty
depends on `script(1)` being present (it is, in both the bundled Alpine guest and the Termux
bootstrap), and the header says `pipes` when it is not. No claim here was tested on your
phone; every layout change compiles and the logic is covered by tests, but the pixels are
yours to judge.
