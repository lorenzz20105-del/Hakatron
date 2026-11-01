package dev.devstudio.ide.core

/**
 * KnowledgeBase — error → fix, with fallback candidates.
 *
 * This is the part the training corpus tuned: 27 projects, 50 rules, 15 fixes
 * learned in a cold pass and all 15 recognised instantly in the warm pass.
 * Ported from ide/smart/kb.py. Rules are ordered; the first match wins, and a
 * fix that fails falls through to its alternatives instead of dead-ending.
 */
object KnowledgeBase {

    data class Fix(
        val kind: String,
        val cmd: String?,          // install/repair command to run before retrying
        val alt: List<String> = emptyList(),
        val why: String,
        val confidence: Double,
        val augment: String? = null,     // append to the failing step, then retry it
        val unsupported: Boolean = false,
        val env: Boolean = false,        // the guest itself is broken
        val needsEyes: Boolean = false,  // syntax: stop and point at the line
        val cmakeInclude: String? = null, // a header inside the project isn't on the include path
        val tsjson: Boolean = false,     // patch compilerOptions.rootDir in tsconfig.json
        val pkgjson: Boolean = false     // patch "type":"module" in package.json
    )

    private val JAVA_COORD = mapOf(
        "com.google.gson" to "com.google.code.gson:gson:2.11.0",
        "org.junit.jupiter" to "org.junit.jupiter:junit-jupiter:5.10.2",
        "org.junit" to "junit:junit:4.13.2",
        "org.apache.commons.lang3" to "org.apache.commons:commons-lang3:3.14.0",
        "com.squareup.okhttp3" to "com.squareup.okhttp3:okhttp:4.12.0",
        "org.slf4j" to "org.slf4j:slf4j-api:2.0.13",
        "com.fasterxml.jackson.databind" to "com.fasterxml.jackson.core:jackson-databind:2.17.1",
        "org.sqlite" to "org.xerial:sqlite-jdbc:3.45.1.0",
        "org.postgresql" to "org.postgresql:postgresql:42.7.3",
        "lombok" to "org.projectlombok:lombok:1.18.32"
    )

    private val APK_CMD = mapOf(
        "gcc" to "build-base", "cc" to "build-base", "g++" to "build-base", "make" to "build-base",
        "cmake" to "cmake", "git" to "git", "curl" to "curl", "wget" to "wget",
        "node" to "nodejs", "npm" to "npm", "python3" to "python3", "pip" to "py3-pip",
        "go" to "go", "cargo" to "cargo", "rustc" to "rust", "javac" to "openjdk17",
        "java" to "openjdk17", "mvn" to "maven", "gradle" to "gradle", "php" to "php82",
        "composer" to "composer", "ruby" to "ruby", "bundle" to "ruby-bundler",
        "sqlite3" to "sqlite", "ffmpeg" to "ffmpeg", "deno" to "deno", "psql" to "postgresql-client"
    )

    private val LIB_PKG = mapOf(
        "z" to "zlib-dev", "png" to "libpng-dev", "jpeg" to "libjpeg-turbo-dev",
        "ssl" to "openssl-dev", "crypto" to "openssl-dev", "ffi" to "libffi-dev",
        "sqlite3" to "sqlite-dev", "xml2" to "libxml2-dev", "curl" to "curl-dev",
        "pq" to "postgresql-dev", "stdc++" to "build-base"
    )

    private val HEADER_PKG = mapOf(
        "python.h" to "python3-dev", "openssl/ssl.h" to "openssl-dev", "zlib.h" to "zlib-dev",
        "curl/curl.h" to "curl-dev", "jpeglib.h" to "libjpeg-turbo-dev", "png.h" to "libpng-dev",
        "sqlite3.h" to "sqlite-dev", "libpq-fe.h" to "postgresql-dev", "ffi.h" to "libffi-dev",
        "stdio.h" to "build-base", "bits/c++config.h" to "g++", "iostream" to "g++"
    )

    private val CMAKE_PKG = mapOf(
        "OPENSSL" to "openssl-dev", "ZLIB" to "zlib-dev", "CURL" to "curl-dev",
        "SQLITE3" to "sqlite-dev", "PNG" to "libpng-dev", "JPEG" to "libjpeg-turbo-dev",
        "PostgreSQL" to "postgresql-dev", "Threads" to "build-base", "Python3" to "python3-dev",
        "Python" to "python3-dev", "BZip2" to "bzip2-dev", "LibXml2" to "libxml2-dev",
        "ZLIB-NG" to "zlib-dev", "OpenSSL111" to "openssl-dev"
    )

    private val NPM_BIN = mapOf(
        "tsc" to "typescript", "ts-node" to "ts-node", "vite" to "vite", "nodemon" to "nodemon",
        "jest" to "jest", "eslint" to "eslint", "prettier" to "prettier", "tsx" to "tsx",
        "next" to "next", "vitest" to "vitest"
    )

    private val UNSUPPORTED = setOf(
        "docker", "docker-compose", "podman", "systemctl", "kubectl", "nvidia-smi", "virtualbox"
    )
    private val ENV_BINARIES = setOf("apk", "sh", "bash", "busybox", "chmod", "mkdir", "touch")

    fun javaCoord(imp: String): String? {
        val key = JAVA_COORD.keys.filter { imp == it || imp.startsWith("$it.") }.maxByOrNull { it.length }
        return key?.let { JAVA_COORD[it] }
    }

    /** ordered: first regex that matches decides the fix. */
    fun planFix(error: String, port: Int?, entry: String): Fix? {
        fun m(rx: String) = Regex(rx, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).find(error)

        m("No module named '?\"?([\\w.]+)")?.let { r ->
            val mod = r.groupValues[1]
            val pkg = Analyzer.PY_ALIAS[mod.lowercase()] ?: mod.replace('_', '-')
            return Fix("py_module", "pip install --break-system-packages $pkg",
                alt = listOf("apk add --no-cache py3-$pkg"),
                why = "'$mod' is not installed; the pip name is '$pkg'", confidence = 0.95)
        }
        if (Regex("externally-managed-environment").containsMatchIn(error))
            return Fix("pep668", null, augment = "--break-system-packages",
                why = "PEP 668 blocks system pip; the step needs the override flag", confidence = 0.99)
        m("Could not find a version that satisfies|Temporary failure in name resolution|Network is unreachable|Could not resolve host|npm ERR! network")
            ?.let {
                return Fix("network", null, alt = listOf("apk add --no-cache ca-certificates"),
                    why = "the guest cannot reach the package registry — no network or DNS in the sandbox",
                    confidence = 0.9, env = true)
            }
        // ---- tools whose name is NOT their Alpine package (Round 8/9 lesson):
        // "jupyter: not found" must never become "apk add jupyter". These run
        // before the generic catch-all, exactly like _SPECIFIC_FIRST in kb.py.
        m("(?:^|\\s)cmake: (?:command )?not found")?.let {
            return Fix("cmake_missing", "apk add --no-cache cmake",
                alt = listOf("apk add --no-cache build-base"), why = "CMake isn't installed",
                confidence = 0.95)
        }
        m("(?:^|\\s)ninja: (?:command )?not found|CMAKE_MAKE_PROGRAM is not set")?.let {
            return Fix("ninja_missing", "apk add --no-cache samurai",
                alt = listOf("apk add --no-cache cmake"),
                why = "Alpine's ninja-compatible runner is samurai", confidence = 0.85)
        }
        m("(?:^|\\s)(?:mvn|maven): (?:command )?not found")?.let {
            return Fix("mvn_missing", "apk add --no-cache maven",
                alt = listOf("apk add --no-cache openjdk17"), why = "Maven isn't installed",
                confidence = 0.95)
        }
        m("(?:^|\\s)gradle: (?:command )?not found")?.let {
            return Fix("gradle_missing", "apk add --no-cache gradle",
                alt = listOf("apk add --no-cache maven"), why = "Gradle isn't installed",
                confidence = 0.9)
        }
        m("(?:^|\\s)(?:bundle|bundler): (?:command )?not found")?.let {
            return Fix("bundle_missing", "apk add --no-cache ruby-bundler",
                alt = listOf("gem install bundler"), why = "Bundler isn't installed",
                confidence = 0.95)
        }
        m("(?:^|\\s)deno: (?:command )?not found")?.let {
            return Fix("deno_missing", "apk add --no-cache deno", why = "Deno isn't installed",
                confidence = 0.85)
        }
        m("(?:^|\\s)bun: (?:command )?not found")?.let {
            return Fix("bun_missing", "curl -fsSL https://bun.sh/install | bash",
                alt = listOf("npm install -g bun"), why = "Bun installs with its official script",
                confidence = 0.8)
        }
        m("(?:^|\\s)(?:wasm-pack|emcc): (?:command )?not found")?.let {
            return Fix("wasm_pack", "apk add --no-cache emscripten || cargo install wasm-pack",
                alt = listOf("cargo install wasm-pack"), why = "wasm tooling isn't installed",
                confidence = 0.8)
        }
        m("(?:^|\\s)psql: (?:command )?not found")?.let {
            return Fix("db_client", "apk add --no-cache postgresql-client",
                alt = listOf("apk add --no-cache postgresql"), why = "psql isn't installed",
                confidence = 0.9)
        }
        m("(?:^|\\s)(?:jupyter|nbconvert): (?:command )?not found")?.let {
            return Fix("notebook_tool", "pip install --break-system-packages nbconvert jupyter-core ipykernel",
                why = "notebook conversion tools aren't installed — they come from pip, not apk",
                confidence = 0.9)
        }
        m("No module named 'ipykernel'|Kernel died|No such kernel named")?.let {
            return Fix("kernel_missing",
                "pip install --break-system-packages ipykernel && python3 -m ipykernel install --user",
                why = "the notebook has no Python kernel registered", confidence = 0.9)
        }
        m("(?:pip|pip3): (?:command )?not found")?.let {
            return Fix("pip_missing_tool", "apk add --no-cache py3-pip", why = "pip isn't in the guest yet",
                confidence = 0.95)
        }
        if (Regex("Bad system call|SIGSYS|exit (?:status )?159|Killed by signal 31").containsMatchIn(error))
            return Fix("seccomp", "export PROOT_NO_SECCOMP=1",
                why = "Android's seccomp filter kills PRoot's own acceleration; ptrace-only mode is the fix",
                confidence = 0.95)
        m("UNTRUSTED signature|BAD signature")?.let {
            return Fix("apk_sig", "rm -rf /var/cache/apk/* && apk update",
                alt = listOf("apk update"),
                why = "the package index cache is corrupt or the mirror rotated; rebuild it",
                confidence = 0.9)
        }
        m("CERTIFICATE_VERIFY_FAILED|certificate verify failed|SSL certificate problem")?.let {
            return Fix("cert_verify", "apk add --no-cache ca-certificates && update-ca-certificates",
                alt = listOf("pip install --trusted-host pypi.org"),
                why = "the guest has no trusted root certificates, so HTTPS installs cannot verify",
                confidence = 0.95)
        }
        if (Regex("dubious ownership in repository").containsMatchIn(error))
            return Fix("git_safe", "git config --global --add safe.directory '*'",
                why = "git refuses repos owned by another uid — the guest runs as fake-root",
                confidence = 0.95)

        m("cannot find -l(\\w+)")?.let { r ->
            val lib = r.groupValues[1]
            return Fix("link_lib", "apk add --no-cache ${LIB_PKG[lib] ?: "$lib-dev"}",
                alt = listOf("apk add --no-cache build-base"),
                why = "linker can't find lib$lib; Alpine ships it in ${LIB_PKG[lib] ?: "$lib-dev"}",
                confidence = 0.8)
        }
        m("fatal error: ([\\w./+-]+\\.(?:hpp|hxx|hh|h)): No such file")?.let { r ->
            val h = r.groupValues[1]
            val sys = setOf("openssl", "curl", "sqlite3", "zlib", "python3", "ffi", "png",
                "jpeg", "libpq", "sys", "bits")
            if (h.contains('/') && h.substringBefore('/') !in sys &&
                h.substringBefore('/') !in HEADER_PKG.map { it.key.substringBefore('/') }) {
                return Fix("cmake_include", null, cmakeInclude = h, confidence = 0.85,
                    why = "'" + h + "' is a header inside your own project — the build was never " +
                        "told where the include root is")
            }
        }
        m("fatal error: ([\\w./+-]+\\.h): No such file")?.let { r ->
            val h = r.groupValues[1].lowercase()
            val pkg = HEADER_PKG[h] ?: "${h.substringBefore('/')}-dev"
            return Fix("header", "apk add --no-cache $pkg", why = "header $h missing; Alpine package is $pkg",
                confidence = 0.8)
        }
        m("Could NOT find ([\\w-]+)").let { r ->
            if (r != null) {
                val name = r.groupValues[1]
                val pkg = CMAKE_PKG[name] ?: CMAKE_PKG.entries
                    .firstOrNull { it.key.equals(name, true) }?.value ?: "${name.lowercase()}-dev"
                return Fix("cmake_pkg", "apk add --no-cache $pkg",
                    alt = listOf("apk add --no-cache build-base"),
                    why = "CMake can't find $name; Alpine package $pkg provides it", confidence = 0.85)
            }
        }
        if (Regex("No CMAKE_CXX_COMPILER could be found|CMAKE_C_COMPILER not set").containsMatchIn(error))
            return Fix("cxx_compiler", "apk add --no-cache build-base",
                why = "CMake needs a C/C++ toolchain", confidence = 0.95)
        m("(tsc|ts-node|vite|nodemon|jest|eslint|prettier|tsx|next): (?:command )?not found")?.let { r ->
            return Fix("npm_bin", "npm install -g ${NPM_BIN[r.groupValues[1]] ?: r.groupValues[1]}",
                why = "'${r.groupValues[1]}' is a Node tool — it comes from npm", confidence = 0.92)
        }
        m("(?:^|\\s)([\\w.+-]+): (?:command )?not found")?.let { r ->
            val bin = r.groupValues[1]
            if (bin in UNSUPPORTED) return Fix("unsupported", null, unsupported = true, confidence = 1.0,
                why = "'$bin' cannot run inside a PRoot guest on Android — no kernel namespaces. " +
                      "The app itself still runs here; build containers on a PC or CI.")
            if (bin in ENV_BINARIES) return Fix("env_broken", null, env = true, confidence = 1.0,
                why = "'$bin' is the environment itself — the guest is broken, not your project")
            return Fix("cmd_missing", "apk add --no-cache ${APK_CMD[bin] ?: bin}",
                alt = listOf("apk add --no-cache build-base"),
                why = "'$bin' is not in the guest", confidence = 0.85)
        }
        m("Cannot find module '([^']+)'")?.let { r ->
            val pkg = r.groupValues[1]
            if (pkg.startsWith(".") || pkg.startsWith("/") || pkg.endsWith(".js") || pkg.contains("/"))
                return Fix("missing_build", null, needsEyes = true, confidence = 0.9,
                    why = "'$pkg' is a FILE, not a package — the project needs a build step first")
            return Fix("npm_module", "npm install --no-audit --no-fund $pkg",
                why = "'$pkg' is required but not in node_modules", confidence = 0.92)
        }
        if (Regex("ERESOLVE|unable to resolve dependency tree").containsMatchIn(error))
            return Fix("npm_peer", null, augment = "--legacy-peer-deps",
                why = "peer dependency conflict — npm 7+ refuses; legacy mode matches npm 6", confidence = 0.85)
        // A refused connection is a *service* problem — and which service is decided
        // by the port in the error, not by habit. Postgres on 5432, Redis on 6379,
        // MariaDB/MySQL on 3306; anything else falls back to the project's own port.
        val refused = Regex("ECONNREFUSED|Connection refused|could not connect to server|" +
            "Is the server running|psycopg2\\.OperationalError|connection to server on socket .*failed")
        if (refused.containsMatchIn(error)) {
            val seen = Regex("(?:127\\.0\\.0\\.1|localhost|0\\.0\\.0\\.0)[: ,]+(\\d{2,5})").find(error)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("port (\\d{2,5})").find(error)?.groupValues?.get(1)?.toIntOrNull()
            val p = seen ?: port ?: 5432
            return when (p) {
                6379 -> Fix("db_down", "apk add --no-cache redis && redis-server --daemonize yes",
                    alt = listOf("redis-server --daemonize yes --save '' --appendonly no"),
                    why = "port 6379 is Redis and nothing is listening inside the guest",
                    confidence = 0.85)
                3306 -> Fix("db_down",
                    "apk add --no-cache mariadb mariadb-client && " +
                        "mkdir -p /run/mysqld && chown -R mysql:mysql /run/mysqld /var/lib/mysql && " +
                        "(mysql_install_db --user=mysql --datadir=/var/lib/mysql || true) && " +
                        "mysqld_safe --datadir=/var/lib/mysql &",
                    why = "port 3306 is MariaDB/MySQL and the server isn't running in the guest",
                    confidence = 0.8)
                else -> Fix("db_down",
                    "mkdir -p /run/postgresql && chown postgres:postgres /run/postgresql && " +
                        "su postgres -s /bin/sh -c 'pg_ctl -D /var/lib/postgresql/data -l /tmp/pg.log -w start'",
                    alt = listOf("initdb -D /var/lib/postgresql/data --auth=trust"),
                    why = "PostgreSQL (port $p) isn't running inside the guest",
                    confidence = 0.8)
            }
        }
        if (Regex("could not find expected browser|Executable doesn't exist at|Chromium revision is not downloaded",
                RegexOption.IGNORE_CASE).containsMatchIn(error))
            return Fix("browser", "apk add --no-cache chromium",
                why = "bundled Chromium can't run on Android; use Alpine's chromium", confidence = 0.85)
        if (Regex("No usable sandbox|Running as root without --no-sandbox").containsMatchIn(error))
            return Fix("browser_flags", null, augment = "",
                why = "Chromium needs --no-sandbox inside the guest", confidence = 0.9)
        if (Regex("No rule to make target|No targets specified").containsMatchIn(error))
            return Fix("make_target", null, needsEyes = true, confidence = 0.7,
                why = "make has no such target — one that exists will be picked")
        if (Regex("package ([\\w.]+) does not exist").containsMatchIn(error))
            return Fix("java_pkg", null, needsEyes = true, confidence = 0.7,
                why = "Java import is unresolved — add the dependency to pom.xml/build.gradle")
        m("(?:^|\\s)([\\w.+-]+): (?:command )?not found") // handled above
        // ---- C/C++ headers, Java, gems, wasm targets
        m("fatal error: (bits/c\\+\\+config\\.h|iostream): No such file")?.let {
            return Fix("stdcpp", "apk add --no-cache g++",
                alt = listOf("apk add --no-cache build-base"),
                why = "C++ standard library headers are missing", confidence = 0.95)
        }
        if (Regex("JAVA_HOME is not set|no Java runtime|Unable to locate a Java Runtime").containsMatchIn(error))
            return Fix("java_home", "apk add --no-cache openjdk17 && " +
                "export JAVA_HOME=/usr/lib/jvm/java-17-openjdk",
                why = "a JDK is required and JAVA_HOME must point at it", confidence = 0.95)
        m("mkmf\\.rb:? can't find header files for ruby|Error installing ([\\w-]+):")?.let { r ->
            val gem = r.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "the gem"
            return Fix("gem_native", "apk add --no-cache ruby ruby-dev build-base && gem install $gem",
                why = "native gem '$gem' needs headers and a compiler", confidence = 0.85)
        }
        if (Regex("Requires (?:read|write|net|env|run) access to|PermissionDenied: Requires").containsMatchIn(error))
            return Fix("deno_perm", null, augment = "--allow-all",
                why = "Deno denies permissions by default; the step needs --allow-all on this device",
                confidence = 0.9)
        if (Regex("can't find crate for `std`.*wasm32|target wasm32-\\S+ not installed").containsMatchIn(error))
            return Fix("wasm_target", "rustup target add wasm32-unknown-unknown",
                alt = listOf("rustup target add wasm32-wasi"), why = "the wasm target isn't installed",
                confidence = 0.9)

        // ---- registry / permission / path failures
        m("E404|404 Not Found - GET .*/([\\w@/.-]+)")?.let { r ->
            val pkg = r.groupValues.getOrNull(1) ?: "?"
            return Fix("npm_404", null, needsEyes = true, confidence = 0.7,
                why = "package '$pkg' does not exist on the registry — the name in package.json is wrong")
        }
        if (Regex("EACCES|permission denied, open").containsMatchIn(error))
            return Fix("eacces", "npm config set prefix ~/.npm-global && export PATH=\$PATH:~/.npm-global/bin",
                alt = listOf("chmod -R u+rwX ."),
                why = "global npm installs write outside the sandbox; move the prefix into \$HOME",
                confidence = 0.75)
        m("No such file or directory: '([^']+)'|cannot access '([^']+)'")?.let { r ->
            val p = listOf(r.groupValues[1], r.groupValues[2]).firstOrNull { it.isNotBlank() } ?: "."
            return Fix("no_file", "mkdir -p \"\$(dirname '$p')\" && touch '$p'",
                why = "a path the code expects doesn't exist; create it empty so the run can continue",
                confidence = 0.7)
        }
        m("no required module provides package ([\\w./-]+)")?.let { r ->
            val mod = r.groupValues[1]
            return Fix("go_dep", "go mod tidy", alt = listOf("go get $mod"),
                why = "module $mod isn't in go.mod; tidy resolves it", confidence = 0.85)
        }
        m("error\\[E0432\\]: unresolved import `?([\\w:]+)|can't find crate for `(?!std`|core`|alloc`)([\\w-]+)`")
            ?.let { r ->
                val dep = listOf(r.groupValues[1], r.groupValues[2]).firstOrNull { it.isNotBlank() } ?: "?"
                return Fix("rust_dep", "cargo add $dep", alt = listOf("cargo build --offline"),
                    why = "crate '$dep' is not in Cargo.toml", confidence = 0.8)
            }
        if (Regex("linker `?cc`? not found|error: linker .* not found").containsMatchIn(error))
            return Fix("rust_linker", "apk add --no-cache build-base",
                why = "Rust needs a C linker; Alpine's build-base", confidence = 0.95)
        m("""Class ["']([\w\\]+)["'] not found""")?.let { r ->
            val cls = r.groupValues[1].substringAfterLast('\\')
            return Fix("php_class", "composer install", alt = listOf("composer require $cls"),
                why = "PHP class missing — composer deps aren't installed", confidence = 0.75)
        }
        if (Regex("UnicodeDecodeError|UnicodeEncodeError").containsMatchIn(error))
            return Fix("unicode", "export PYTHONUTF8=1 PYTHONIOENCODING=utf-8",
                alt = listOf("apk add --no-cache tzdata"), why = "the guest locale is POSIX; force UTF-8",
                confidence = 0.85)
        if (Regex("ENOSPC: System limit for number of file watchers|inotify watch").containsMatchIn(error))
            return Fix("inotify", "export CHOKIDAR_USEPOLLING=1 WATCHPACK_POLLING=true",
                why = "Android caps inotify watchers; dev servers must poll instead", confidence = 0.85)

        // ---- services: postgres/redis inside a real guest
        m("pg_ctl: directory \"[^\"]+\" does not exist|database system is not initialized|could not change directory to")
            ?.let {
                return Fix("db_bootstrap",
                    "mkdir -p /var/lib/postgresql/data && chown -R postgres:postgres /var/lib/postgresql && " +
                    "su postgres -s /bin/sh -c 'initdb -D /var/lib/postgresql/data --auth=trust'",
                    why = "the database cluster was never created; initialise it once", confidence = 0.9)
            }
        if (Regex("Unable to lock database|temporary error \\(try again later\\)").containsMatchIn(error))
            return Fix("apk_lock", "rm -f /lib/apk/db/lock && apk update",
                why = "a previous apk run died and left the database locked; the lock file is stale",
                confidence = 0.95)
        if (Regex("could not create lock file").containsMatchIn(error))
            return Fix("pg_lock",
                "mkdir -p /run/postgresql && chown postgres:postgres /run/postgresql",
                alt = listOf("mkdir -p /run/postgresql"),
                why = "PostgreSQL keeps its socket in /run/postgresql, which a fresh guest doesn't have",
                confidence = 0.9)

        // ---- config-file remedies (never the user's source)
        if (Regex("Unexpected token 'export'|Cannot use import statement outside a module|" +
                "ERR_REQUIRE_ESM|is not supported by CommonJS").containsMatchIn(error))
            return Fix("esm_cjs", null, pkgjson = true, confidence = 0.85,
                why = "the output is ESM but node runs it as CommonJS — set \"type\": \"module\" in package.json")
        if (Regex("TS5011|The 'rootDir' setting must be explicitly set").containsMatchIn(error))
            return Fix("ts_rootdir", null, tsjson = true, confidence = 0.9,
                why = "TypeScript refuses an outDir outside the source dir until rootDir is set in tsconfig.json")

        if (Regex("SyntaxError|IndentationError|ParseError|unexpected token").containsMatchIn(error))
            return Fix("syntax", null, needsEyes = true, confidence = 1.0,
                why = "a syntax error can't be guessed safely — it points at the line instead")
        if (Regex("Killed|exit code 137|out of memory|Cannot allocate memory").containsMatchIn(error))
            return Fix("oom", "export MAKEFLAGS=-j1", why = "the compiler ran out of RAM; single-job builds survive",
                confidence = 0.85)
        if (Regex("No space left on device|ENOSPC").containsMatchIn(error))
            return Fix("nospace", "rm -rf /var/cache/apk/* ~/.cache/pip/*", why = "package caches filled the sandbox",
                confidence = 0.8)
        if (Regex("EADDRINUSE|Address already in use|Errno 98|port \\d+ in use").containsMatchIn(error)) {
            val cur = port ?: 5000
            val next = cur + 1
            return Fix("port",
                "sed -i -E 's/(port *= *)${cur}/\\1$next/g' \$(grep -rls --include=*.py --include=*.js " +
                "--include=*.ts . | head -20) 2>/dev/null; export PORT=$next",
                why = "port $cur is taken; $next is free (a hardcoded port is rewritten in config, never logic)",
                confidence = 0.9)
        }
        if (Regex("Permission denied").containsMatchIn(error))
            return Fix("perm", "chmod +x $entry", why = "the entry file isn't executable", confidence = 0.75)
        return null
    }
}
