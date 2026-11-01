// Oldest toolchain of the two apps (limodriverapp: AGP 8.6.1, Kotlin 2.0.21), so code that
// builds here builds in both. Loaded on the buildscript classpath so the subproject can
// apply both plugins by id, exactly as it does inside an app build.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.6.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    }
}
