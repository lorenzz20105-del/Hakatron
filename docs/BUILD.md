# Building

## Toolchain

| Tool | Version |
| --- | --- |
| JDK | 17 (the Android Gradle plugin needs 17, the sandbox default is 11) |
| Android SDK | platform 34, build tools 34.0.0 |
| Gradle | 8.7 |
| Kotlin | 1.9.24 |

The toolchain of this machine lives outside of the home directory, so that
it survives a reset of the workspace:

```bash
source tools/env.sh
```

`tools/env.sh` sets `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`,
`PATH` and `GRADLE_USER_HOME`. On another machine the three variables
below are enough:

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$JAVA_HOME/bin:$PATH
```

## Build

```bash
tools/build_release.sh          # signed release APK -> release/
tools/build_release.sh --debug  # debug APK
tools/install_on_device.sh      # install on a connected device
```

The release script creates `app/keystore/linuxdesk.jks` on the first run
(store and key password `linuxdesk`, `CN=LinuxDesk, OU=Development,
O=LinuxDesk, L=Manila, ST=Metro Manila, C=PH`), builds the APK, copies it
into `release/` and verifies the signature with `apksigner`.

The plain Gradle commands work as well:

```bash
gradle --no-daemon :app:assembleDebug
gradle --no-daemon -x lintVitalRelease :app:assembleRelease
```

`lintVitalRelease` is skipped on a small machine, it needs more memory than
the rest of the build together.

## Tests

```bash
gradle :rfb:cleanTest :rfb:test --no-daemon --no-build-cache
```

The unit tests do not need a device. The `LiveServerTest` is skipped unless
a VNC server is listening on `127.0.0.1:5901`:

```bash
# a TigerVNC server with a known root window colour
xsetroot -display :1 -solid "#3366CC"
gradle :rfb:cleanTest :rfb:test --no-daemon --no-build-cache \
    -Dlinuxdesk.test.port=5901 -Dlinuxdesk.test.rootColor=3366CC
```

With a server in front of it the suite is 35 tests, 0 failures.

## Memory

The reference machine for this project has 2 GB of RAM. `gradle.properties`
is therefore tuned for it: a small heap, one worker, no parallel execution
and the Kotlin compiler running inside the Gradle process. On a bigger
machine the defaults can be raised again.

Build the debug and the release variant in separate runs, they are both
heavy.

## Layout of the build

* `settings.gradle.kts` – the two modules `:app` and `:rfb`.
* `build.gradle.kts` – the plugin versions.
* `rfb/build.gradle.kts` – a plain Kotlin JVM library with JUnit 4.
* `app/build.gradle.kts` – the Android application, Compose and the engine
  asset sync (`syncEngineAssets` puts `engine/linuxdesk` and
  `engine/linuxdeskd` into the assets of the APK).
