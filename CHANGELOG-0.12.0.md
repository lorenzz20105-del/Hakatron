# Hakatron 0.12.0 — read a whole project out of a screenshot

**versionCode 12 · versionName 0.12.0**

People don't hand you a repo, they hand you a picture: a chat answer with the
entire project tree drawn in `├── └── │`, sometimes 46,000 pixels tall. Build
Studio can now take that picture and turn it into the structure the planner
builds from — on the phone, with nothing uploaded.

## What's new

### From a screenshot (Build Studio → *Read a project tree from a picture*)
- Pick the image with the system picker (`image/*`); OCR runs **on-device**
  (bundled ML Kit Latin model — no Play Services download, no network call).
- **Tiled region decoding.** A 720 × 46,063 px screenshot decoded in one go is
  ~130 MB and is killed instantly. The image is now cut with
  `BitmapRegionDecoder` into 2,600 px strips (64 px overlap), OCR'd one strip at
  a time, and the line repeated in the overlap is dropped. Memory stays flat no
  matter how tall the picture is.
- Progress is visible while it works (`strip 4/18`), and a failure says why in
  plain words instead of doing nothing.
- The result lands in the structure box, ready to edit, with
  *"read 17 entries under IDEbotAutomation/"* so you can see what it understood.

### How the tree depth is worked out (`core/TreeReader.kt`, new)
Three signals, in order of trust:

1. **Indentation + box marks** — `depth = spaces/4 + marks`, counting only the
   spaces *before* the first `├`/`│`. The spaces that draw a vertical bar's
   column are decoration and used to add four fake levels, which flattened real
   trees.
2. **Pixel left edges** — ML Kit reports a bounding box per line; near-identical
   left edges (12 px tolerance) are the same level. Verified against the real
   screenshot: its levels sit at x = 52, 84, 116, 148 — a clean 32 px step.
   Both signals are combined as `max(glyphs, pixels + 1)`.
3. **Monotonic clamp** — a line can never be more than one level deeper than the
   line above it. This is what stops one wrapped long line (the 188 px outlier
   in the real image) from shifting everything after it.

Root detection looks for the first line carrying a box character and takes the
line above it as the project root — so `IDEbotAutomation/` stays the root
instead of `app/` silently becoming it.

### Brace expansion
`res/values/{strings.xml, themes.xml}` becomes the folder `res/values/` plus
both files, at the right depth. `java/com/example/idebot/` stays a path and
becomes real nested directories once the planner runs.

### Prose is dropped, not turned into files
`Project Tree`, `Copy`, `Thought for 6 seconds`, fences and stray punctuation are
ignored (and counted) instead of becoming a folder called *Project Tree*.

### Reading the structure got better everywhere (`core/Analyzer.kt`)
`readStructure` used to divide indentation by a hard-coded 3. Screenshots and
hand-made trees indent by 2, 3, 4 or 6 spaces, so deep directories were being
attached to the wrong parent. Indentation is now interpreted by **rank of the
distinct indent widths**, which is unit-independent — the bundled templates
(C++/CMake, Rust, JVM) now nest correctly too.

## Verified
- `:app:compileReleaseKotlin` clean.
- The parser was validated against four renderings of the same project (as it
  appears in the picture, with ±3 px pixel jitter, with every `│`/`├` dropped by
  OCR, and with all spacing collapsed). The first three give the identical,
  correct 17-entry tree; the fully collapsed one degrades honestly instead of
  inventing depth.
- End-to-end: that tree → `Analyzer.readStructure` produces the expected 7
  directories and 10 files, e.g.
  `IDEbotAutomation/app/src/main/java/com/example/idebot/ui/EditorScreen.kt`.

## Notes
- ML Kit's Latin recogniser is the bundled model (~4 MB in the APK); code in
  other scripts may need the image to be zoomed before sharing.
- The screenshot path only *reads the structure*. Paste the code itself in
  step 2, as before — nothing is guessed and nothing is invented.

## Size
The bundled OCR pipeline carries one native library per CPU architecture
(~10 MB on arm64, ~28 MB of dead weight for x86/x86_64 emulators). The APK now
ships **arm64-v8a only** — the same architecture the built-in Linux engine
already required for `libproot.so`/`libglibc.so` — so 0.12.0 is 18.0 MB instead
of 29.8 MB, and still works offline on any modern phone.
