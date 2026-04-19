import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

val xcf = XCFramework("shared")

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions { jvmTarget = "11" }
        }
    }

    // iOS targets
    val iosX64 = iosX64()
    val iosArm64 = iosArm64()
    val iosSimulatorArm64 = iosSimulatorArm64()

    listOf(iosX64, iosArm64, iosSimulatorArm64).forEach { target ->
        target.binaries.framework {
            baseName = "shared"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.androidx.work.runtime.ktx)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.hermitech.hermivision.shared"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

sqldelight {
    databases {
        create("AppDatabase") {
            packageName = "com.hermitech.hermivision.db"
        }
    }
}
