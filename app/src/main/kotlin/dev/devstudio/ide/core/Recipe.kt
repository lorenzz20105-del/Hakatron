package dev.devstudio.ide.core

/**
 * Recipe — Devos writing the app, not just reading one.
 *
 * When the user says "make me a notes app for android" and pastes no code,
 * [Brain] decides what kind of thing that is and [Recipe] produces the files. The
 * files are real: they compile with the toolchain the plan installs, or they are a
 * complete project the guest cannot build — and then Devos says exactly that
 * instead of pretending.
 *
 * Every recipe declares its own honesty: [Build] lists what the guest can do, and
 * [Notes] says what it cannot.
 */
object Recipe {

    data class Scaffold(
        val intent: Brain.Intent,
        val name: String,
        val files: Map<String, String>,
        /** the command that builds it in the guest, empty when the guest cannot */
        val guestBuild: String,
        /** what cannot be done here, said plainly — never hidden */
        val limit: String? = null,
        /** what to run somewhere that has the full toolchain */
        val elsewhere: String? = null
    )

    /** Turn "make me a todo app called Focus" into a project name. */
    fun projectName(description: String): String {
        // three ways people name a project, in order of how explicit they are
        Regex("[\"']([A-Za-z][\\w-]{1,20})[\"']").find(description)?.groupValues?.get(1)
            ?.let { return slug(it) }
        Regex("(?:called|named)\\s+([A-Za-z][\\w-]{1,20})", RegexOption.IGNORE_CASE)
            .find(description)?.groupValues?.get(1)?.let { return slug(it) }
        val words = description.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ").split(" ")
            .filter { it.length > 2 && it !in STOP }
            .take(2)
        return slug(words.joinToString("-").ifBlank { "app" })
    }

    private val STOP = setOf(
        "make", "build", "create", "give", "want", "need", "please", "with", "that", "this",
        "the", "and", "for", "app", "application", "simple", "small", "basic", "new"
    )

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        .trim('-').ifBlank { "app" }

    private fun className(name: String) = name.split('-', '_', ' ')
        .filter { it.isNotBlank() }.joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
        .ifBlank { "App" }

    /** Build the scaffold for an intent. */
    fun scaffold(intent: Brain.Intent, description: String): Scaffold? {
        val name = projectName(description)
        return when (intent) {
            Brain.Intent.Android -> android(name, description)
            Brain.Intent.PythonCli -> pythonCli(name, description)
            Brain.Intent.PythonApi -> pythonApi(name, description)
            Brain.Intent.WebStatic -> webStatic(name, description)
            Brain.Intent.NodeApi -> nodeApi(name, description)
            Brain.Intent.CNative -> cNative(name, description)
            else -> null
        }
    }

    // ------------------------------------------------------------------ android
    /**
     * A complete Android project. The guest can compile the Kotlin only if the
     * Android SDK were present — it is not, and no amount of `apk add` provides
     * one — so [limit] says so and [elsewhere] gives the command that really
     * produces the APK (AGP, on any machine or in the bundled CI workflow).
     */
    private fun android(name: String, description: String): Scaffold {
        val cls = className(name)
        val pkg = "dev.devos.$name".replace("-", "")
        val title = description.take(40).ifBlank { "My App" }
        val files = linkedMapOf(
            "settings.gradle.kts" to """
                pluginManagement {
                    repositories { google(); mavenCentral(); gradlePluginPortal() }
                }
                dependencyResolutionManagement {
                    repositories { google(); mavenCentral() }
                }
                rootProject.name = "$name"
                include(":app")
            """.trimIndent() + "\n",

            "build.gradle.kts" to """
                plugins {
                    id("com.android.application") version "8.5.2" apply false
                    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
                }
            """.trimIndent() + "\n",

            "gradle.properties" to """
                org.gradle.jvmargs=-Xmx2048m
                android.useAndroidX=true
                kotlin.code.style=official
            """.trimIndent() + "\n",

            "app/build.gradle.kts" to """
                plugins {
                    id("com.android.application")
                    id("org.jetbrains.kotlin.android")
                }

                android {
                    namespace = "$pkg"
                    compileSdk = 34

                    defaultConfig {
                        applicationId = "$pkg"
                        minSdk = 24
                        targetSdk = 34
                        versionCode = 1
                        versionName = "1.0"
                    }
                    buildTypes {
                        release {
                            isMinifyEnabled = false
                        }
                    }
                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_17
                        targetCompatibility = JavaVersion.VERSION_17
                    }
                    kotlinOptions { jvmTarget = "17" }
                }

                dependencies {
                    implementation("androidx.core:core-ktx:1.13.1")
                    implementation("androidx.appcompat:appcompat:1.7.0")
                }
            """.trimIndent() + "\n",

            "app/src/main/AndroidManifest.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">

                    <application
                        android:allowBackup="true"
                        android:label="$title"
                        android:supportsRtl="true"
                        android:theme="@style/Theme.AppCompat.DayNight.NoActionBar">
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
            """.trimIndent() + "\n",

            "app/src/main/java/${pkg.replace('.', '/')}/MainActivity.kt" to """
                package $pkg

                import android.os.Bundle
                import android.view.Gravity
                import android.widget.LinearLayout
                import android.widget.TextView
                import androidx.appcompat.app.AppCompatActivity

                /**
                 * $title — first screen.
                 *
                 * Plain views, not Compose: this project is meant to build anywhere
                 * with AGP alone, and a Compose dependency adds a compiler plugin the
                 * user would have to match to their Kotlin version.
                 */
                class MainActivity : AppCompatActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        val column = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            setPadding(48, 48, 48, 48)
                        }
                        column.addView(TextView(this).apply {
                            text = "$title"
                            textSize = 26f
                        })
                        column.addView(TextView(this).apply {
                            text = "Built with Hakatron · Devos"
                            textSize = 14f
                        })
                        setContentView(column)
                    }
                }
            """.trimIndent() + "\n",

            ".github/workflows/android.yml" to """
                # Devos wrote this so the APK can be produced by real AGP, not faked.
                name: android
                on: [push, workflow_dispatch]
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                      - uses: actions/setup-java@v4
                        with: { distribution: temurin, java-version: '17' }
                      - run: chmod +x gradlew || true
                      - run: gradle wrapper --gradle-version 8.7 || true
                      - run: ./gradlew assembleDebug
                      - uses: actions/upload-artifact@v4
                        with:
                          name: $name-apk
                          path: app/build/outputs/apk/**/*.apk
            """.trimIndent() + "\n"
        )
        return Scaffold(
            intent = Brain.Intent.Android,
            name = name,
            files = files,
            guestBuild = "",
            limit = "An APK cannot be assembled inside the guest: AGP needs the Android " +
                "SDK (aapt2, d8, platform jars), and there is no Android SDK for Alpine on " +
                "a phone. Devos will not fake a .apk — the project above is complete and " +
                "the bundled workflow builds the real one.",
            elsewhere = "./gradlew assembleDebug   (or push the repo and let .github/workflows/android.yml do it)"
        )
    }

    // --------------------------------------------------------------- python cli
    private fun pythonCli(name: String, description: String): Scaffold {
        val what = description.ifBlank { "a small command line tool" }.trim().removeSuffix(".")
        return Scaffold(
            intent = Brain.Intent.PythonCli,
            name = name,
            files = linkedMapOf(
                "$name.py" to """
                    #!/usr/bin/env python3
                    \"\"\"$what

                    Built by Devos. Args first, side effects last, so testing is possible.
                    \"\"\"
                    import argparse
                    import sys


                    def run(text: str, count: int) -> int:
                        for i in range(count):
                            print(f"{i + 1:>3}. {text}")
                        return 0


                    def main(argv: list[str]) -> int:
                        parser = argparse.ArgumentParser(description="$what")
                        parser.add_argument("text", nargs="?", default="hello", help="what to repeat")
                        parser.add_argument("-n", "--count", type=int, default=3, help="how many times")
                        args = parser.parse_args(argv)
                        if args.count < 0:
                            print("count must not be negative", file=sys.stderr)
                            return 2
                        return run(args.text, args.count)


                    if __name__ == "__main__":
                        raise SystemExit(main(sys.argv[1:]))
                """.trimIndent() + "\n",

                "requirements.txt" to "# no third-party dependencies — stdlib only\n",

                "README.md" to """
                    # $name

                    $what

                    ```
                    python3 $name.py hello -n 5
                    ```
                """.trimIndent() + "\n"
            ),
            guestBuild = "python3 -m py_compile $name.py",
            limit = null
        )
    }

    // --------------------------------------------------------------- python api
    private fun pythonApi(name: String, description: String): Scaffold {
        val what = description.ifBlank { "a small web API" }.trim().removeSuffix(".")
        return Scaffold(
            intent = Brain.Intent.PythonApi,
            name = name,
            files = linkedMapOf(
                "app.py" to """
                    \"\"\"$what

                    Built by Devos. One file, one app, nothing hidden.
                    \"\"\"
                    from flask import Flask, jsonify, request

                    app = Flask(__name__)
                    items: list[dict] = []


                    @app.get("/health")
                    def health():
                        return jsonify(ok=True, service="$name")


                    @app.get("/items")
                    def list_items():
                        return jsonify(items=items, count=len(items))


                    @app.post("/items")
                    def add_item():
                        body = request.get_json(silent=True) or {}
                        text = (body.get("text") or "").strip()
                        if not text:
                            return jsonify(error="text is required"), 400
                        items.append({"text": text})
                        return jsonify(item=items[-1], count=len(items)), 201


                    if __name__ == "__main__":
                        app.run(host="0.0.0.0", port=5000)
                """.trimIndent() + "\n",

                "requirements.txt" to "flask==3.0.3\n",

                "README.md" to """
                    # $name

                    $what

                    ```
                    pip install --break-system-packages -r requirements.txt
                    python3 app.py
                    # then: curl localhost:5000/health
                    ```
                """.trimIndent() + "\n"
            ),
            guestBuild = "python3 -c \"import flask\" || pip install --break-system-packages -r requirements.txt",
            limit = null
        )
    }

    // -------------------------------------------------------------- static web
    private fun webStatic(name: String, description: String): Scaffold {
        val title = description.take(48).ifBlank { className(name) }
        return Scaffold(
            intent = Brain.Intent.WebStatic,
            name = name,
            files = linkedMapOf(
                "index.html" to """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                      <meta charset="utf-8">
                      <meta name="viewport" content="width=device-width, initial-scale=1">
                      <title>$title</title>
                      <link rel="stylesheet" href="style.css">
                    </head>
                    <body>
                      <main>
                        <h1>$title</h1>
                        <p>Built with Hakatron.</p>
                        <button id="go">Say hello</button>
                        <p id="out"></p>
                      </main>
                      <script src="app.js"></script>
                    </body>
                    </html>
                """.trimIndent() + "\n",

                "style.css" to """
                    :root { color-scheme: dark; }
                    body {
                      margin: 0; min-height: 100vh; display: grid; place-items: center;
                      background: #0d0f14; color: #e8ecf4;
                      font: 16px/1.5 system-ui, sans-serif;
                    }
                    main { text-align: center; padding: 32px; }
                    h1 { font-size: clamp(24px, 6vw, 44px); margin: 0 0 8px; }
                    button {
                      margin-top: 18px; padding: 12px 20px; border-radius: 999px; border: 0;
                      background: linear-gradient(90deg, #7c5cff, #22d3ee); color: #0d0f14;
                      font-weight: 700; font-size: 15px;
                    }
                """.trimIndent() + "\n",

                "app.js" to """
                    const out = document.getElementById("out");
                    document.getElementById("go").addEventListener("click", () => {
                      out.textContent = "hello from $name";
                    });
                """.trimIndent() + "\n"
            ),
            guestBuild = "python3 -m http.server 8080 --bind 0.0.0.0",
            limit = null
        )
    }

    // ---------------------------------------------------------------- node api
    private fun nodeApi(name: String, description: String): Scaffold {
        val what = description.ifBlank { "a small Node service" }.trim().removeSuffix(".")
        return Scaffold(
            intent = Brain.Intent.NodeApi,
            name = name,
            files = linkedMapOf(
                "package.json" to """
                    {
                      "name": "$name",
                      "version": "1.0.0",
                      "private": true,
                      "type": "module",
                      "main": "server.js",
                      "scripts": { "start": "node server.js" }
                    }
                """.trimIndent() + "\n",

                "server.js" to """
                    // $what — stdlib only, so `npm install` is not even needed.
                    import { createServer } from "node:http";

                    const items = [];

                    const json = (res, code, body) => {
                      res.writeHead(code, { "content-type": "application/json" });
                      res.end(JSON.stringify(body));
                    };

                    createServer((req, res) => {
                      if (req.url === "/health") return json(res, 200, { ok: true, service: "$name" });
                      if (req.url === "/items" && req.method === "GET")
                        return json(res, 200, { items, count: items.length });
                      if (req.url === "/items" && req.method === "POST") {
                        let raw = "";
                        req.on("data", (c) => { raw += c; });
                        req.on("end", () => {
                          try {
                            const body = JSON.parse(raw || "{}");
                            const text = String(body.text || "").trim();
                            if (!text) return json(res, 400, { error: "text is required" });
                            items.push({ text });
                            json(res, 201, { item: items.at(-1), count: items.length });
                          } catch {
                            json(res, 400, { error: "invalid json" });
                          }
                        });
                        return;
                      }
                      json(res, 404, { error: "not found" });
                    }).listen(3000, "0.0.0.0", () => console.log("listening on :3000"));
                """.trimIndent() + "\n"
            ),
            guestBuild = "node --check server.js",
            limit = null
        )
    }

    // --------------------------------------------------------------- C native
    private fun cNative(name: String, description: String): Scaffold {
        val what = description.ifBlank { "a small native program" }.trim().removeSuffix(".")
        return Scaffold(
            intent = Brain.Intent.CNative,
            name = name,
            files = linkedMapOf(
                "main.c" to """
                    /* $what */
                    #include <stdio.h>
                    #include <stdlib.h>

                    int main(int argc, char **argv) {
                        const char *who = argc > 1 ? argv[1] : "world";
                        for (int i = 0; i < 3; i++) {
                            printf("%d. hello, %s\n", i + 1, who);
                        }
                        return EXIT_SUCCESS;
                    }
                """.trimIndent() + "\n",

                "Makefile" to """
                    CC ?= cc
                    CFLAGS ?= -O2 -Wall -Wextra -std=c17

                    all: $name

                    $name: main.c
                    	${'$'}(CC) ${'$'}(CFLAGS) -o ${'$'}@ ${'$'}^

                    clean:
                    	rm -f $name

                    .PHONY: all clean
                """.trimIndent() + "\n"
            ),
            guestBuild = "make",
            limit = null
        )
    }
}
