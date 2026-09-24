plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    implementation(project(":shared"))
    implementation("org.apache.xmlgraphics:batik-transcoder:1.19")
    implementation("org.apache.xmlgraphics:batik-codec:1.19")
    testImplementation("junit:junit:4.13.2")
}

tasks.processResources {
    from("../app/src/main/assets/fonts") { into("fonts") }
}

application {
    mainClass.set("com.xnotes.desktop.MainKt")
}

tasks.test {
    // Canvas tests drive Swing components without a display.
    systemProperty("java.awt.headless", "true")
}
