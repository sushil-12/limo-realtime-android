# limo-realtime-android

Shared Android client for 1800LIMO's realtime service (limo-rt, contract v1), used by both
`limodriverapp` and `limouserapp`. One copy of the protocol logic, so the two apps can't drift.

Contents: WebSocket client (hello/welcome, subscribe with `last_seq`, replay + dedupe, ack,
20 s ping / 30 s dead, jittered reconnect), contract v1 models, REST API, durable outbox,
config flag, and (Phase 4) the passenger playback interpolator.
Driver-only code (foreground location service, FusedLocation) stays in the driver app.

## Rules
- **No Hilt, no annotation processing** (one app uses kapt, the other ksp). Each app wires
  the library with its own Hilt module.
- Builds with the **oldest** toolchain of the two apps: AGP 8.6.1, Kotlin 2.0, Java 11 target.

## Using it from an app
```bash
git submodule add <repo-url> limo-realtime-android
```
`settings.gradle.kts`:
```kotlin
include(":limo-realtime")
project(":limo-realtime").projectDir = file("limo-realtime-android/limo-realtime")
```
`app/build.gradle.kts`: `implementation(project(":limo-realtime"))`

The module is source-included (not a composite build), so it compiles with the app's own
Android Gradle plugin and Kotlin version.

## Developing
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :limo-realtime:testDebugUnitTest
```
