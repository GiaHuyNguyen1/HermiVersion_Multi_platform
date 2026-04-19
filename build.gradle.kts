// Top-level build file for Kotlin Multiplatform project
plugins {
    alias(libs.plugins.kotlin.multiplatform)  apply false
    alias(libs.plugins.android.application)   apply false
    alias(libs.plugins.android.library)       apply false
    alias(libs.plugins.kotlin.compose)        apply false
    alias(libs.plugins.devtools.ksp)          apply false
    alias(libs.plugins.sqldelight)            apply false
}

val tempBuildRoot = file("${System.getProperty("java.io.tmpdir")}/HermiVision-build")
val requestedTasks = gradle.startParameter.taskNames
val androidFocusedBuild = requestedTasks.any { taskName ->
    taskName.contains("androidApp", ignoreCase = true) ||
        taskName.contains("assemble", ignoreCase = true) ||
        taskName.contains("bundle", ignoreCase = true) ||
        taskName.contains("install", ignoreCase = true) ||
        taskName.contains("lint", ignoreCase = true) ||
        taskName.contains("connected", ignoreCase = true)
}

project(":androidApp") {
    // The Android app module writes resource intermediates that pick up AppleDouble
    // sidecars on the external volume. Keeping only this module's build outputs in /tmp
    // avoids AGP parsing them as Android resources while preserving shared/build for iOS.
    layout.buildDirectory.set(tempBuildRoot.resolve(name))
}

project(":shared") {
    if (androidFocusedBuild) {
        // Android builds consume :shared as a Gradle dependency, so its intermediates can
        // also be redirected to /tmp to avoid AppleDouble sidecars being packed into classes.jar.
        // We leave the default shared/build path untouched for iOS-focused builds because the
        // Xcode project references shared/build/XCFrameworks directly.
        layout.buildDirectory.set(tempBuildRoot.resolve("${name}-android"))
    }
}
