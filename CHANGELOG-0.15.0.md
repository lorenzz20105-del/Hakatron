# Hakatron 0.15.0 — the screenshot bugs fixed, and the app moves properly

**versionCode 16 · versionName 0.15.0 · 29,892,304 B · universal · same signing key since 0.1.0**

Your screenshot showed three real defects. All three are fixed, with tests, plus the
motion and visual pass you asked for.

## 1. "Kotlin project · 547 files" from an import block

The structure box had Kotlin imports in it, and the parser dutifully read every line
as if it were a path.

- `Analyzer.isSourceCode()` now recognises source code (imports, `package`, `fun`,
  `class`, braces, semicolons) and Build Studio shows an amber card:
  **"THAT LOOKS LIKE CODE, NOT A TREE"** — with a one-tap **"Move it to the parts"**
  that relocates the text into step 2 under a `=== file ===` header, and a
  **"Clear step 1"**.
- The **Analyze** button is disabled while step 1 is code, so the wrong plan can no
  longer be produced by accident.
- `readStructure` skips code lines outright, so even a mixed paste can't invent files.

## 2. The plan said `runs: true`

`Analyzer.runCommand` fell back to the shell's `true` builtin — a command that
"succeeds" and does nothing, which is exactly the kind of fake progress this project
is supposed to refuse.

- Unknown stacks now produce **no run command**: the card reads
  `N file(s) · no run step`, and placeholder steps are dropped from every plan.
- **A plan that cannot work is blocked, not built.** `Plan.blocked` carries a reason
  a human can act on, the Build button is replaced by a card explaining it, and
  `Planner.build()` returns before writing or executing anything:

  > *Nothing to build: no files and no tree were read. Paste the tree in step 1 and
  > the code in step 2 (or use "Read a project tree from a picture").*
  > *The tree lists 3 file(s) but none of their contents were pasted, so there is
  > nothing to compile.*

## 3. "fix failed: [exit 159]" — the seccomp fix was applied in the wrong place

The knowledge base answered exit 159 with `export PROOT_NO_SECCOMP=1` and the planner
ran that **inside the guest**, where it does nothing: the variable has to be in
PRoot's own environment, on the host side.

- `Engine.applyHostFix()` routes environment remedies to the launcher, and the planner
  retries the same step instead of dead-ending.
- Launching is now a **ladder** — `default → no-seccomp → kill-on-exit → link2symlink
  → quiet` — climbed automatically when a rung dies with SIGSYS, and remembered for
  the session so the device pays for the retry once.
- The exit line names the mode that ran (`[exit 0 · built-in Linux · kill-on-exit · no Termux]`),
  and when every rung fails the app says so in words instead of showing a raw 159:
  *"This device's kernel blocks PRoot's syscall interception (SIGSYS). Every launch
  mode was tried. Use the Termux engine…"*
- `Engine.diagnose()` reports exactly which stage is missing (loader, proot, glibc).

## Motion and visuals

The rules are Material 3 Expressive's physics model, not hand-tuned durations
([1](https://saglitz.com/design/material-3), [2](https://www.sabaoon.dev/blog/material-expressive)):

- **Spatial tokens** (position/size/shape) may overshoot: `spring(damping 0.72, stiffness 380)`.
- **Effect tokens** (colour/alpha) are never springy — they tween, because a colour
  that bounces reads as a glitch.
- Everything goes through `graphicsLayer`, so animation never triggers relayout.

What you'll see:

| where | what |
|---|---|
| bottom bar | the pill **grows and lifts** on a spring (46×27 selected vs 30×22), labels brighten, haptic tick on change |
| tabs | direction-aware slide + cross-fade (14 % of width, so it reads as one surface moving, not five screens swapping) |
| full-screen flows | Build Studio / Termux / Preview rise over the app with a sheet-style slide + scale 0.985 |
| home | hero, KPI row and tiles **stagger in** 40 ms apart |
| tiles | spring press (dip to 0.96) with a real ripple |
| Analyze | gradient pill button, springs on press |
| plan card | confidence bar **fills** on an expressive spring, evidence lines stagger in |
| motion off | if the system animation scale is 0, tokens collapse — motion sensitivity respected |

`ui/Motion.kt` holds the whole system: `Motion.responsive/expressive/spatial/fade`,
`Pressable`, `Staggered`, `animatedCount`, `rememberShimmer`, `Springs`.

## Tests

```
AnalyzerTest 12 · KnowledgeBaseTest 17 · PlannerTest 12 · TreeReaderTest 9 · VtTest 18
= 68 tests, 0 failures
```

New ones pin exactly your screenshot: an import block is code; a real tree is not;
code pasted as a structure produces **0 files**; an unknown stack never returns
`true`; an empty plan **is blocked and never executes**; no plan contains a
placeholder step; a host remedy is handed to the launcher, not the guest.
