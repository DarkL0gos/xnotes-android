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
    linuxX64()

    sourceSets {
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
