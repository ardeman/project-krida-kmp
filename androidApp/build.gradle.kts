plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ardeman.krida"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ardeman.krida"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Small install size: strip unused code and resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Ship as an App Bundle: Play delivers only the device's ABI and density.
    bundle {
        abi { enableSplit = true }
        density { enableSplit = true }
        language { enableSplit = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)

    // 3D voxel rendering. Meshes are generated in code as glTF and drawn with gltfio's
    // built-in materials, so no material compiler (matc) and no filament-utils are needed.
    implementation(libs.filament.android)
    implementation(libs.filament.gltfio)

    // Ads (AdMob + UMP consent) and the "Hapus iklan" purchase that turns them off.
    implementation(libs.play.services.ads)
    implementation(libs.ump)
    implementation(libs.billing)
    // play-services-ads pulls in an old Fragment that breaks registerForActivityResult.
    implementation(libs.fragment)
}
