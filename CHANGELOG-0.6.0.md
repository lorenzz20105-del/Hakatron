# Hakatron 0.6.0 — "Devos"

versionCode 6 · versionName 0.6.0 · package `dev.devstudio.ide` (unchanged, so it
upgrades over 0.5.0 in place with your data) · signed with the same key.

## The trained engine, now inside the app

The Python brain (`ide/smart/`, **Devos v9**) was ported to Kotlin and this build
ships it:

* **59 rules**, same priority order as `kb.py` — specific before generic, so
  `jupyter: not found` can never become `apk add jupyter`.
* New in this port: `pg_lock`, `db_down` (psycopg2 wording), `db_bootstrap`,
  `db_client`, `ts_rootdir`, `esm_cjs`, `apk_lock`, `apk_sig`, `cert_verify`,
  `git_safe`, `ninja_missing`, `mvn_missing`, `gradle_missing`, `deno_missing`,
  `bun_missing`, `wasm_pack`, `bundle_missing`, `notebook_tool`, `kernel_missing`,
  `pip_missing_tool`, `pip_ssl`, `npm_404`, `eacces`, `no_file`, `go_dep`,
  `rust_dep`, `rust_linker`, `php_class`, `unicode`, `stdcpp`, `java_home`,
  `gem_native`, `deno_perm`, `wasm_target`, `inotify`, `seccomp`, `browser_missing`.
* `cmake_pkg` now matches what CMake really prints (`Could NOT find OpenSSL`) and
  maps it through `CMAKE_PKG` (OPENSSL→openssl-dev, ZLIB→zlib-dev, …).
* **Config patches, never source edits:** `tsconfig.json` gets `rootDir`, and
  `package.json` gets `"type": "module"`. Your logic is never rewritten to make a
  build pass.
* A fix that is applied **3× without the error changing** stops the build honestly
  instead of looping.

## System screen

New **DEVOS ENGINE** panel: rule count, corpus size, toolchain count and the
verified numbers (50/56 built · 6 honest refusals · 0 unexpected · 30/30 real
sandbox) — plus the promise that source is never edited. Toolchain probe, one-tap
installs, self-test, storage and Termux verdict are unchanged.

## Proof this round

* `ide/real_sandbox_test.py` against a real Alpine 3.20.3 chroot: **30/30**
  (19/19 toolchains installed for real; flask+postgres, cmake+openssl,
  typescript, go, rust all built; sqlite 42; nbconvert; native gem; Composer;
  honest dotnet refusal).
* `ide/many_tests.py`: **8/8**.
* Corpus: **50/56 built, 6 honest stops, 0 unexpected** (`ide/DEVOS-ROUND9-REPORT.txt`).
