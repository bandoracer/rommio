plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.swordfish.libretrodroid"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
