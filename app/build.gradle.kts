plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val releaseStoreFile = System.getenv("CYBERVAULT_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("CYBERVAULT_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("CYBERVAULT_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("CYBERVAULT_RELEASE_KEY_PASSWORD")
val releaseSigningValues = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }
val cybervaultVersionCode = providers.gradleProperty("cybervaultVersionCode").orNull?.toIntOrNull() ?: 1000
val cybervaultVersionName = providers.gradleProperty("cybervaultVersionName").orNull ?: "0.1.0"
if (releaseSigningValues.any { !it.isNullOrBlank() } && !releaseSigningConfigured) {
    throw GradleException("Release signing requires all CYBERVAULT_RELEASE_* environment variables.")
}
android {
    namespace = "dev.cybercore.cybervault"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "dev.cybercore.cybervault"
        minSdk = 30
        targetSdk = 36
        versionCode = cybervaultVersionCode
        versionName = cybervaultVersionName
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        buildConfigField("boolean", "ALLOW_SCREENSHOTS", "false")
    }
    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Debug builds only, so the emulator UI harness can capture
            // screens. Release builds always set FLAG_SECURE.
            buildConfigField("boolean", "ALLOW_SCREENSHOTS", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { jniLibs { useLegacyPackaging = false } }
    lint {
        warningsAsErrors = true
        informational += setOf("OldTargetApi", "AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// ---- Rust core (CyberVault vault format + Keysmith generators) ----------
// `cargo ndk` builds the uniffi bridge for each ABI into jniLibs, then
// uniffi-bindgen reads the compiled library and writes the Kotlin bindings.
// Both run on every build so CI and F-Droid need no manual step.
val rustDir = rootProject.file("rust")
val rustTargetDir = rustDir.resolve("target")
val jniLibsDir = file("src/main/jniLibs")
val uniffiDir = layout.buildDirectory.dir("generated/uniffi")
val ndkDir = providers.environmentVariable("ANDROID_NDK_HOME")
    .orElse(provider { android.sdkDirectory.resolve("ndk/${android.ndkVersion}").absolutePath })

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Build libcybervault_mobile.so for every ABI with cargo-ndk."
    workingDir = rustDir
    inputs.dir(rustDir.resolve("src"))
    inputs.files(rustDir.resolve("Cargo.toml"), rustDir.resolve("Cargo.lock"), rustDir.resolve("uniffi.toml"))
    outputs.dir(jniLibsDir)
    doFirst {
        environment("ANDROID_NDK_HOME", ndkDir.get())
        environment("CARGO_TARGET_DIR", rustTargetDir.absolutePath)
    }
    commandLine(
        "cargo", "ndk", "-t", "arm64-v8a", "-t", "x86_64", "--platform", "30",
        "-o", jniLibsDir.absolutePath, "build", "--release", "--locked",
    )
}

// The shipped .so files are stripped, which also removes the metadata
// uniffi-bindgen reads, so bindings come from an unstripped host build of
// the same source.
val uniffiBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generate the Kotlin bindings from a host build of the bridge."
    dependsOn(cargoNdkBuild)
    workingDir = rustDir
    inputs.dir(rustDir.resolve("src"))
    inputs.files(rustDir.resolve("Cargo.toml"), rustDir.resolve("Cargo.lock"), rustDir.resolve("uniffi.toml"))
    outputs.dir(uniffiDir)
    doFirst {
        environment("CARGO_TARGET_DIR", rustTargetDir.absolutePath)
        delete(uniffiDir)
    }
    commandLine(
        "sh", "-c",
        "cargo build --locked --quiet --lib && " +
            "cargo run --locked --quiet --features bindgen --bin uniffi-bindgen -- generate " +
            "--library \"\$1/debug/libcybervault_mobile.so\" --language kotlin --no-format --out-dir \"\$2\"",
        "uniffi", rustTargetDir.absolutePath, uniffiDir.get().asFile.absolutePath,
    )
}

kotlin.sourceSets.named("main") { kotlin.srcDir(uniffiDir) }
tasks.named("preBuild") { dependsOn(uniffiBindings) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    testImplementation("junit:junit:4.13.2")
}
