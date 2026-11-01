# Hakatron 0.14.0 — a real terminal, inside the app

**versionCode 15 · versionName 0.14.0 · 29.9 MB · universal · same signing key since 0.1.0**

Until now the Terminal tab was a command runner: it sent a line, waited for a
marker, and stripped the colours off what came back. That is not a terminal, and
it could never run `vi`, `top` or `less`. 0.14.0 replaces it with a real one —
a VT-100/xterm emulator in front of a live shell that owns a **pseudo-terminal**,
with nothing outside the app. No Termux installed, no daemon, no root.

## What "real" means here

**A real pty.** The session now runs `/bin/script -q -e -f -c "sh -i"` inside the
Alpine guest. `script(1)` allocates a pseudo-terminal with `openpty` and attaches
the shell to it, so `isatty()` returns true, the shell has job control, `stty`
works, prompts arrive as prompts, and full-screen programs paint the screen.
Hakatron now **bundles `script` (67 KB, musl-only, from Alpine's util-linux-misc)
inside its rootfs**, so this works offline on first run. If the binary is ever
missing the session falls back to piped stdio — and the header says `pipes`
instead of `pty` rather than pretending.

**A real VT-100/xterm emulator** (`core/Vt.kt`, ~470 lines, no library):

| | |
|---|---|
| grid | 80×24 cells with 3,000 lines of scrollback, deferred-wrap autowrap, tabs |
| colour | SGR 16 / 256 / 24-bit, bold, dim, italic, underline, reverse, hidden, strike |
| cursor | CUP, CUU/CUD/CUF/CUB, CNL/CPL, CHA, VPA, save/restore (`ESC 7`/`ESC 8`) |
| edit | ED, EL, ECH, ICH, DCH, IL, DL |
| screens | alternate screen (47 / 1047 / 1049) so `vi` and `top` don't eat your history |
| regions | DECSTBM scroll regions, SU/SD |
| protocols | OSC title (shown in the header), DSR/CPR replies so programs can ask where the cursor is |
| encoding | UTF-8 decoded across read boundaries, so a split multi-byte character isn't mangled |

**Keystrokes, not lines.** Every character typed goes to the pty as it is typed —
per-character IME deltas, not a text box submitted at the end. The extra-keys row
gives you `CTRL` (sticky), `ESC`, `TAB`, `^C`, `^D`, arrows, `| - / ~ $ " '` and
`⏎`, exactly the keys a phone keyboard doesn't have.

**One process for the whole session**, so `cd`, `export`, aliases and an
`apk add` all persist.

**A coloured prompt on first run**, from `PS1` in the environment plus a
`/root/.hakatron_init` the guest sources — no typing required.

## Interface

- live title from the shell's OSC title escape, or `shell exited` in red
- `pty` / `pipes` badge — which mode you actually got, not a wish
- `A-` / `A+` font size, `⇣` autoscroll toggle, `↻` restart the session
- tap anywhere to focus the keyboard; scrollback is scrollable

## Tests

18 new emulator tests (`VtTest`), covering the things that actually appear on the
wire: prompt rendering, `ls --color` output, `top`-style full-screen paints,
insert/delete line, scroll regions, in-place progress-bar redraws
(`\r` overwrites), alternate-screen restore, OSC title, cursor-position replies,
and UTF-8 split across reads.

```
AnalyzerTest 8 · KnowledgeBaseTest 17 · PlannerTest 8 · TreeReaderTest 9 · VtTest 18
= 60 tests, 0 failures
```

Two real bugs the tests caught before the phone did:

- **CUP was off by one parameter** — `ESC[3;7H` put the cursor on row 7 instead of
  row 3, which is exactly what makes `vi` open with everything in the wrong place.
- The emulator's `lines()` returns scrollback **and** screen; the first version of
  the tests read the bottom row as if it were the top, which is a mistake the UI
  could have made too.

## Notes

- The guest still needs its one-time setup (System → set up the engine). The pty
  path needs no network; `apk add` inside the terminal does.
- `script` is GPL-2.0 (util-linux). It ships unmodified inside the Alpine rootfs,
  matching the licence of the guest it runs in; the source is linked from
  util-linux's own project pages.
- Universal APK: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.
