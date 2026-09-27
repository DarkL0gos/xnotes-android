plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    // Compiling for a native target keeps commonMain honest: JVM-only API there fails the build.
    linuxX64 {
        // An optimized test binary too (linuxX64ReleaseTest), for measurements like the pen benchmark.
        binaries.test(listOf(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE))
    }

    sourceSets {
        // File IO, streams and hashing for common code (java.io / java.nio have no native twin).
        commonMain.dependencies {
            implementation("com.squareup.okio:okio:3.10.2")
        }
        nativeTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation("junit:junit:4.13.2")
            implementation("org.json:json:20240303")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.named<Test>("jvmTest") {
    useJUnit()
}
