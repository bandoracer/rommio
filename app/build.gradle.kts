plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

fun loadLocalEnv(file: File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .mapNotNull { rawLine ->
            val line = rawLine.trim()
            when {
                line.isBlank() -> null
                line.startsWith("#") -> null
                !line.contains("=") -> null
                else -> {
                    val key = line.substringBefore("=").trim()
                    val value = line.substringAfter("=").trim()
                    key to value
                }
            }
        }
        .toMap()
}

fun buildConfigString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

val localTestEnv = loadLocalEnv(rootProject.file(".env.test.local"))
val localReleaseEnv = loadLocalEnv(rootProject.file(".env.release.local"))
val releaseKeystorePath = providers.environmentVariable("ROMMIO_RELEASE_KEYSTORE_PATH").orNull
    ?: localReleaseEnv["ROMMIO_RELEASE_KEYSTORE_PATH"]
val releaseStorePassword = providers.environmentVariable("ROMMIO_RELEASE_STORE_PASSWORD").orNull
    ?: localReleaseEnv["ROMMIO_RELEASE_STORE_PASSWORD"]
val releaseKeyAlias = providers.environmentVariable("ROMMIO_RELEASE_KEY_ALIAS").orNull
    ?: localReleaseEnv["ROMMIO_RELEASE_KEY_ALIAS"]
val releaseKeyPassword = providers.environmentVariable("ROMMIO_RELEASE_KEY_PASSWORD").orNull
    ?: localReleaseEnv["ROMMIO_RELEASE_KEY_PASSWORD"]
val hasReleaseSigning = !releaseKeystorePath.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "io.github.bandoracer.rommio"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_static" }
        }
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        buildConfigField("String", "LEGACY_PACKAGE_ID", "\"io.github.mattsays.rommnative\"")
        buildConfigField("boolean", "IS_LEGACY_BRIDGE", "false")
        buildConfigField("String", "DEBUG_TEST_BASE_URL", "\"\"")
        buildConfigField("String", "DEBUG_TEST_CLIENT_ID", "\"\"")
        buildConfigField("String", "DEBUG_TEST_CLIENT_SECRET", "\"\"")
        buildConfigField("String", "DEBUG_TEST_USERNAME", "\"\"")
        buildConfigField("String", "DEBUG_TEST_PASSWORD", "\"\"")
        // One-touch setup (setup-service/README.md): the device API and the approval page's origin.
        // Release builds use the production service; debug builds point at a local mock (below).
        buildConfigField("String", "SETUP_SERVICE_URL", "\"https://rommio-setup.ryan-personal-fa9.workers.dev/\"")
        buildConfigField("String", "SETUP_APPROVAL_ORIGIN", "\"https://rommio-setup.borchetta.xyz\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }


    flavorDimensions += "packageId"

    productFlavors {
        create("standard") {
            dimension = "packageId"
            applicationId = "io.github.bandoracer.rommio"
            buildConfigField("boolean", "IS_LEGACY_BRIDGE", "false")
        }

        create("legacyBridge") {
            dimension = "packageId"
            applicationId = "io.github.mattsays.rommnative"
            buildConfigField("boolean", "IS_LEGACY_BRIDGE", "true")
            resValue("string", "app_name", "Rommio Legacy")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            externalNativeBuild { cmake { arguments += "-DROMMIO_BUILD_TEST_CORE=ON" } }
            buildConfigField("String", "DEBUG_TEST_BASE_URL", buildConfigString(localTestEnv["ROMM_TEST_BASE_URL"].orEmpty()))
            buildConfigField("String", "DEBUG_TEST_CLIENT_ID", buildConfigString(localTestEnv["ROMM_TEST_CLIENT_ID"].orEmpty()))
            buildConfigField("String", "DEBUG_TEST_CLIENT_SECRET", buildConfigString(localTestEnv["ROMM_TEST_CLIENT_SECRET"].orEmpty()))
            buildConfigField("String", "DEBUG_TEST_USERNAME", buildConfigString(localTestEnv["ROMM_TEST_USERNAME"].orEmpty()))
            buildConfigField("String", "DEBUG_TEST_PASSWORD", buildConfigString(localTestEnv["ROMM_TEST_PASSWORD"].orEmpty()))
            // Debug builds never reach the production setup service: a local mock (the emulator's
            // host loopback) unless .env.test.local names another test endpoint.
            buildConfigField("String", "SETUP_SERVICE_URL", buildConfigString(localTestEnv["ROMMIO_SETUP_URL"] ?: "http://10.0.2.2:8787/"))
            buildConfigField("String", "SETUP_APPROVAL_ORIGIN", buildConfigString(localTestEnv["ROMMIO_SETUP_APPROVAL_ORIGIN"] ?: "http://10.0.2.2:8787"))
        }

        release {
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    sourceSets {
        getByName("test").java.srcDir("src/testShared/java")
        getByName("androidTest").java.srcDir("src/testShared/java")
        getByName("test").resources.srcDir(rootProject.file("tests/fixtures/progress-v2"))
        getByName("androidTest").assets.srcDir(rootProject.file("tests/fixtures/progress-v2"))
        // Optional, explicitly staged native cores for isolated real-runtime qualification.
        providers.gradleProperty("nativePlayerFixtures").orNull?.let {
            getByName("androidTest").assets.srcDir(file(it))
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    // Setup code: the approval link as a QR code (Apache-2.0), opened in a Custom Tab.
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.browser:browser:1.8.0")
    implementation(project(":libretrodroid"))

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

// Reuse the production validator against explicitly staged artifacts; no native code is loaded.
tasks.register<JavaExec>("verifyCoreArtifacts") {
    dependsOn("testStandardDebugUnitTest")
    mainClass.set("io.github.bandoracer.rommio.domain.player.CoreArtifactProbe")
    doFirst {
        classpath = tasks.named<Test>("testStandardDebugUnitTest").get().classpath
        args(providers.gradleProperty("coreArtifactList").get())
    }
}

// Native core bytes and their exact manifest are produced together from vendored source.
val pinnedCoreAssets = layout.buildDirectory.dir("generated/pinned-core-assets")
val buildPinnedCores by tasks.registering(Exec::class) {
    inputs.dir(rootProject.file("third_party/fceumm"))
    inputs.file(rootProject.file("scripts/android/build_pinned_cores.py"))
    outputs.dir(pinnedCoreAssets)
    commandLine("python3", rootProject.file("scripts/android/build_pinned_cores.py"),
        "--ndk", File(android.sdkDirectory, "ndk/${android.ndkVersion}"),
        "--output", pinnedCoreAssets.get().asFile)
}
android.sourceSets.getByName("main").assets.srcDir(pinnedCoreAssets)
tasks.named("preBuild") { dependsOn(buildPinnedCores) }

val cartridgeCoreAssets = layout.buildDirectory.dir("generated/cartridge-core-assets")
val buildCartridgeCores by tasks.registering(Exec::class) {
    inputs.dir(rootProject.file("scripts/cores"))
    outputs.dir(cartridgeCoreAssets)
    commandLine("python3", rootProject.file("scripts/cores/package_android.py"),
        "--ndk", File(android.sdkDirectory, "ndk/${android.ndkVersion}"),
        "--output", cartridgeCoreAssets.get().asFile)
}
android.sourceSets.getByName("main").assets.srcDir(cartridgeCoreAssets)
tasks.named("preBuild") { dependsOn(buildCartridgeCores) }
