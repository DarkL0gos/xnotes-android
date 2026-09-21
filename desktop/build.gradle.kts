plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.xnotes.desktop.MainKt")
}
