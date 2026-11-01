# Hakatron 0.13.0 — the engine is now tested, and the APK installs everywhere

**versionCode 14 · versionName 0.13.0 · 29.8 MB · universal (arm64-v8a, armeabi-v7a, x86, x86_64)**

Two things drove this release: the 0.12.0 APK was refused by the phone
(*"App not installed as package appears to be invalid"*), and the engine had
**zero tests and no CI** — which made every fix a gamble.

## The install failure is fixed

0.12.0 shipped `arm64-v8a` only. On a phone whose primary ABI is 32-bit the
installer reads the manifest, shows the icon, then rejects the package because
no native library matches the device. 0.13.0 is **universal** again:

```
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
```

Signed with the same key as 0.1.0 (`CN=DevStudio`), so it upgrades in place.

## 42 unit tests, no emulator needed

The whole reasoning layer — `Analyzer`, `Planner`, `KnowledgeBase`, `TreeReader`
— is pure Kotlin, so it is tested on the JVM:

| file | tests | what it pins down |
|---|---|---|
| `KnowledgeBaseTest` | 17 | every rule against a **real error line**, including the honesty rules: `docker` is refused, `apk: not found` is reported as the guest breaking, a syntax error never runs a command, unrecognised output returns *no* fix |
| `TreeReaderTest` | 9 | the screenshot parser: box-drawing, dropped glyphs, ±3 px pixel jitter, brace expansion, prose rejection, and the rule that a long wrapped line can't shift the levels below it |
| `PlannerTest` | 8 | declared intent wins: `package.json` scripts, Makefile targets, CTest, and notes — and nothing is invented when the project is silent |
| `AnalyzerTest` | 8 | indentation by rank (2/3/4/6 spaces), bundle headers, stack detection, imports → pip names |

Run them with `./gradlew :app:testReleaseUnitTest`.

## GitHub Actions CI

`.github/workflows/ci.yml` runs on every push, PR and tag:
tests → `assembleDebug` → `assembleRelease` → uploads the test report and both
APKs. The release keystore is **not** in git, so the build now treats it as
optional: signed when present, unsigned in CI, and the pipeline still verifies
the whole app compiles and packages. `gradlew` is committed for the first time.

## Real fixes the tests forced

- **A refused connection is now attributed by port.** `ECONNREFUSED 127.0.0.1:6379`
  used to start PostgreSQL. It now starts **Redis**; `:3306` starts MariaDB;
  `:5432` starts PostgreSQL. Tested.
- **An empty `requirements.txt` no longer hides the imports.** The planner used
  to emit `pip install -r requirements.txt` and install nothing when the file was
  blank; it now falls back to the packages it found by reading the code.
- **`stripPrefix` counts a bar column and a connector as two levels** — that was
  the actual behaviour, and the test documents it instead of assuming otherwise.
- **The build survives 2 GB machines.** The Gradle heap was raised to 1100 MB and
  Metaspace capped, after the daemon was OOM-killed mid-dex on the first attempt.

## Notes

- The repository now also carries the source, the CI workflow and the changelog:
  https://github.com/lorenzz20105-del/Hakatron
- The keystore stays out of git on purpose — a public signing key would let
  anyone produce a valid update for this app.
