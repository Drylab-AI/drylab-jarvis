// JVM harness that compiles the app's OpenWakeWord.kt unchanged and runs it on
// the PC with desktop ONNX Runtime, so its scores can be diffed against the
// Python reference (../oww_reference.py). Run:
//   ./gradlew -p tools/oww/kt-check run --args="<models dir> <clip.raw> ..."
plugins {
    kotlin("jvm") version "2.0.21"
    application
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    main {
        kotlin {
            // Harness sources + the exact OpenWakeWord.kt shipped in the app
            // (the app source dir, filtered down to that one file).
            srcDir("src/main/kotlin")
            srcDir("../../../android-app/app/src/main/java")
            include("Check.kt", "com/jarvis/voicerec/OpenWakeWord.kt")
        }
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime:1.31.0")
}

application {
    mainClass.set("CheckKt")
}
