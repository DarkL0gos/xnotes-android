plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// libxnotes.so: the core behind the C API in include/xnotes.h, for native hosts (the Qt app).
kotlin {
    linuxX64 {
        compilations.getByName("main") {
            cinterops {
                // The public header, so Kotlin sees the host's callback tables as C structs.
                create("xnotes") {
                    definitionFile.set(project.file("src/nativeInterop/cinterop/xnotes.def"))
                    includeDirs(project.file("include"))
                }
            }
        }
        binaries {
            sharedLib("xnotes") {
                baseName = "xnotes"
            }
        }
    }

    sourceSets {
        linuxX64Main.dependencies {
            implementation(project(":shared"))
            implementation("com.squareup.okio:okio:3.10.2")
        }
    }
}

// The C API exercised from C alone, as the Qt host will use it: gcc + the release library.
val cTestDir = layout.buildDirectory.dir("ctest")
val releaseLib = layout.buildDirectory.dir("bin/linuxX64/xnotesReleaseShared")

val cTestCompile by tasks.registering(Exec::class) {
    description = "Compiles capi/test/capi_test.c against libxnotes.so."
    dependsOn("linkXnotesReleaseSharedLinuxX64")
    inputs.files(fileTree("include"), file("test/capi_test.c"))
    outputs.dir(cTestDir)
    doFirst { cTestDir.get().asFile.mkdirs() }
    val lib = releaseLib.get().asFile.absolutePath
    commandLine(
        "gcc", "-std=c11", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
        "-I", file("include").absolutePath, file("test/capi_test.c").absolutePath,
        "-L", lib, "-lxnotes", "-Wl,-rpath,$lib", "-o", cTestDir.get().asFile.resolve("capi_test").absolutePath,
    )
}

val cTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the C API test program."
    dependsOn(cTestCompile)
    val dir = cTestDir.get().asFile
    commandLine(dir.resolve("capi_test").absolutePath, dir.absolutePath)
}
