# Hakatron 0.9.0 — Devos v10

versionCode 9 · package `dev.devstudio.ide` (unchanged → upgrades in place)

## Devos v10: read the project, don't guess over it

Rounds 8–9 taught Devos to install everything a paste needs and keep fixing until
it runs. Round 10 is **fidelity** — a real project already declares how it is
built and tested, and guessing over that is how a build drifts away from the
author's intent.

* **`package.json` scripts are obeyed.** `build`, `test` and `start` become the
  build, test and run steps. Before this, `npm test` was assumed and `node
  index.js` was invented even when the project said `start`.
* **Makefile first.** If the tree ships a Makefile (and no CMakeLists), Devos uses
  `make` with the target the Makefile actually declares (`all` or the first one),
  and `make test` when that target exists — instead of inventing a compiler line.
* **CTest.** A `CMakeLists.txt` containing `enable_testing()` or `add_test(`
  produces `ctest --test-dir build --output-on-failure` as a real test phase.
* **Node scripts are honoured by the guest**, so `npm run build` fails honestly
  (`npm ERR! Missing script`) when the script genuinely isn't there.
* **Same-fix guard (Python engine now matches the app):** if the identical fix is
  applied three times and the error text never changes, the build stops honestly
  instead of burning the retry budget.
* **Phase report.** Every build now returns
  `install ✓ build ✓ test ✓ run ✓ verify ✓`, and `done` is true only when every
  phase the plan actually has, passed. The System screen moved to **v10**.

## Proof

* Corpus: **61 projects · 55 built · 6 honest stops · 0 unexpected**
  (cold and warm passes) — five new projects exist only to test this round:
  `vite-dashboard` (npm scripts), `math-lib-ctest` (CTest), `makefile-daemon`
  (Makefile), `pyproject-cli`, `cargo-binary`.
* Real Alpine guest: **33/33** — 19/19 toolchains installed for real, and 13 real
  project builds including `npm scripts` and `c++ ctest`, plus your modular
  C++/CMake layout.
* Published to GitHub: `hakatron-0.5.0 … 0.9.0` with APKs attached.
