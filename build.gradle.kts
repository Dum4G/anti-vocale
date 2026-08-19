// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // AGP 8.10 ships R8 that only reads Kotlin metadata 2.1; Kotlin 2.4
        // classes (incl. our own stdlib) need R8 9.1.29+ (compatible with
        // AGP 8.5.2+, see developer.android.com/studio/build/kotlin-d8-r8-versions).
        classpath("com.android.tools:r8:9.1.43")
    }
}

plugins {
    id("com.android.application") version "8.10.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
    id("com.google.gms.google-services") version "4.4.4" apply false
    id("com.google.firebase.crashlytics") version "3.0.6" apply false
    id("com.google.dagger.hilt.android") version "2.58" apply false
}
