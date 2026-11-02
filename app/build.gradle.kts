plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.devstudio.ide"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.devstudio.ide"
        minSdk = 24
        targetSdk = 34
        versionCode = 23
        versionName = "0.20.0"
        resourceConfigurations += listOf("en")

        // Universal APK on purpose: 0.12.0 shipped arm64-v8a only and was refused
        // by 32-bit phones ("package appears to be invalid" — no matching ABI).
    }

    // The release keystore is deliberately not in git. When it is missing (CI,
    // a fresh clone) the release build still runs and produces an unsigned APK,
    // so every change is verified by the pipeline instead of "works on my machine".
    val keystoreFile = file("keystore/devstudio.jks")
    signingConfigs {
        if (keystoreFile.exists()) {
            create("linuxdesk") {
                storeFile = keystoreFile
                storePassword = "devstudio"
                keyAlias = "devstudio"
                keyPassword = "devstudio"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (keystoreFile.exists()) signingConfigs.getByName("linuxdesk") else null
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin", "src/main/java")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        }
    }
}

// The Termux engine scripts live in /engine and are shipped inside the APK
// so that the app can push them into Termux at runtime.
val engineAssets = layout.buildDirectory.dir("generated/engineAssets")
val syncEngineAssets = tasks.register<Sync>("syncEngineAssets") {
    from(rootProject.layout.projectDirectory.dir("engine")) {
        include("linuxdesk", "linuxdeskd")
    }
    into(engineAssets)
}
android.sourceSets.getByName("main").assets.srcDir(engineAssets)
tasks.named("preBuild").configure { dependsOn(syncEngineAssets) }

dependencies {
    // no VNC viewer in the IDE — the engine only needs shell access

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-process:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // on-device OCR for "read a project tree from a screenshot" — bundled model,
    // no Play Services download at runtime, and nothing leaves the phone
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // JVM unit tests. The engine (Analyzer / Planner / KnowledgeBase / TreeReader)
    // is pure Kotlin, so it is tested without an emulator.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.24")
}
