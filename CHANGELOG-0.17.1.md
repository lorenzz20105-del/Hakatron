# Hakatron 0.17.1 — the parts really are parts now

**Built:** 2026-11-02 · versionCode **21** · `dev.devstudio.ide` · label `Hakatron`
**APK:** 29,926,816 bytes · sha256 `e22b88ba4ed09b027d7ff925c1dcca2050a4d0413b000ccdb0d2c584efc4c2bd`
**Tests:** 112 / 112 green · **ABIs:** arm64-v8a · armeabi-v7a · x86 · x86_64

Your screenshot showed the real failure: an eight-file paste arrived in the parts box as

```
=== main.cs ===
V deepseek
"I need all in one go
Thought for 6 seconds >
IDE Bot - Complete Package (One Drop)
Everything: build files, all sources, tests, README…
Project Tree
0 Copy
text
IDEBotAutomation/
```

— one part, named `main.cs`, holding the chat preamble. Two separate bugs produced that.

## 1. The chat preamble was treated as a file

Your screenshot was of a conversation, so the OCR text starts with the model's preamble
before the code. The splitter cut the eight real files correctly (the chip line even said
`8 files`), but the block *before* the first header had no header of its own, so it fell
through to `inferName()` and was named **`main.cs`** — a name that made prose look like a
C# file and, because it was first, made the whole paste look like one part.

Now that block is recognised and **dropped**:

* `Parts.isChatNoise()` matches the furniture a chat screenshot drags in — `V deepseek`,
  `Thought for 6 seconds`, `0 Copy`, `Project Tree`, `text`, "I need all in one go",
  "Complete Package (One Drop)", "Copy these into…", "Here is…", "Would you like…".
* `Parts.isProse()` rejects a headerless block when it has no code: chat lines are counted
  separately from code lines first, because `Thought for 6 seconds >` and `…Package (One
  Drop)` contain `>` and `(` and were being scored as code. That one detail is why the
  preamble survived the first attempt.
* Dropped blocks are **counted, never hidden**: the header shows
  `N chat block(s) ignored` next to the part count.

## 2. Every part is now visible, not just counted

The parts were previously summarised in one compressed grey line. The composer now lists
them as a scrollable set of cards, one per file:

```
① main.rs        rust · 14 lines
② App.kt         kotlin · 22 lines
③ core/TriggerBus.kt   kotlin · 9 lines · name inferred
```

with a colour per language (kotlin violet, java rose, python cyan, rust amber, go mint),
the line count, and whether the name came from a header or was inferred from the code. Above
it: `8 parts` and `8 header(s) found`; below it, a full-width **Write 8 file header(s)**
button that rewrites the paste into canonical `=== path ===` blocks.

So a paste can no longer *look* like one part even when the split was correct — and when the
split is wrong, the card list says exactly which file is wrong.

## 3. Tests

`ChatPreambleTest` (4 new) pins the case from your screenshot, verbatim: the preamble is not
a file (`main.cs` must not appear), `dropped >= 1`, all seven code files survive, prose is
prose and code is code, and the noise matcher accepts `V deepseek` / `Thought for 6 seconds >`
/ `0 Copy` / `Project Tree` while rejecting `class App`.

| Suite | Count |
|---|---|
| Analyzer | 12 |
| BuildNaming | 9 |
| **ChatPreamble (new)** | **4** |
| KnowledgeBase | 17 |
| PartsSplit | 10 |
| Planner | 12 |
| TermuxRemap | 3 |
| TermuxSymlinks | 4 |
| TermuxUserland | 10 |
| TreeReader | 9 |
| TreeReaderVerdict | 4 |
| Vt | 18 |
| **Total** | **112 · 0 failures** |

## 4. Unchanged

The OCR reader still refuses to call a screenshot of code a "tree" (0.16.2), the Termux
install still unpacks with real symlinks and a checksum (0.16.1), and the editor and shell
are the rebuilt 0.17.0 versions. Devos still never edits your source.
