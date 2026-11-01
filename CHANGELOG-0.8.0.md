# Hakatron 0.8.0 — "Your C++ project, and a nicer UI"

versionCode 8 · package `dev.devstudio.ide` (unchanged → upgrades in place)

## Your sample project now builds

The modular C++ / CMake skeleton from your screenshots — `core` + `features`
libraries, one executable, headers under `src/` — was run through Devos inside a
real Alpine guest. The first attempt failed exactly the way it fails for a human:

    fatal error: Core/Memory/IMemory.hpp: No such file or directory

because `target_include_directories(MyCheatProject PRIVATE src)` only covers the
executable, not the libraries it links. Devos now recognises that the missing
header is **your own** (not an Alpine package), finds the include root, and writes

    target_include_directories(core PUBLIC src)
    target_include_directories(features PUBLIC src)

then rebuilds and runs it:

    menu
    MyCheatProject built

* New rule `cmake_include` (Python and Kotlin), **not** learnable as a shell hack —
  it is a build-config repair, like `tsconfig.json` and `package.json`.
* It never touches `.cpp`/`.hpp` source, only `CMakeLists.txt`.
* Permanent real-guest case `cpp+cmake modular` → real sandbox is now **31/31**.

## UI

* **Templates in Build Studio** — a "START FROM" row: `C++ · CMake` (your exact
  layout), `Python · Flask`, `Go · service`. One tap fills structure + parts +
  build/run notes.
* Bottom navigation **animates** (tint and pill fade over 180 ms) instead of
  snapping between tabs.
* The version chip on Home reads the **real** installed version (it was hard-coded
  "v0.5"); the System header follows too.

## Proof

* Real Alpine chroot: **31/31** checks, 19/19 toolchains installed for real,
  11 real projects built (including plain and modular CMake/C++).
* Corpus unchanged: **50/56 built · 6 honest stops · 0 unexpected**.
