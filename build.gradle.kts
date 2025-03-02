plugins {
    kotlin("jvm") version "1.9.10"
    application
}

repositories {
    mavenCentral()  // ✅ Ensures Ktor modules are fetched from Maven
}

dependencies {
    // Ktor Server dependencies

    implementation("com.google.auth:google-auth-library-oauth2-http:1.22.0")

    implementation("io.ktor:ktor-server-netty:2.3.6")
    implementation("io.ktor:ktor-server-core:2.3.6")
    implementation("io.ktor:ktor-server-call-logging:2.3.6")  // ✅ Correct for CallLogging
    implementation("io.ktor:ktor-server-default-headers:2.3.6")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.6")
    implementation("io.ktor:ktor-server-resources:2.3.6")
    implementation("io.ktor:ktor-server-cors:2.3.6") // ✅ If needed for CORS handling

    // Logging for Ktor
    implementation("ch.qos.logback:logback-classic:1.4.12")

    // Ktor Client for API requests
    implementation("io.ktor:ktor-client-core:2.3.6")
    implementation("io.ktor:ktor-client-cio:2.3.6")  // ✅ Required for `HttpClient(CIO)`
    implementation("io.ktor:ktor-client-content-negotiation:2.3.6")  // ✅ Required for `ContentNegotiation`
    implementation("io.ktor:ktor-serialization-gson:2.3.6")  // ✅ Required for `gson()`

    // Kotlin Coroutines (Fix missing CoroutineScope issue)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

application {
    mainClass.set("MainKt")  // ✅ Ensures correct entry point for JVM console app
}
tasks.jar {
    archiveBaseName.set("eder")
    archiveVersion.set("")  // Removes version suffix for cleaner output
    manifest {
        attributes["Main-Class"] = "com.nfarima.eder.MainKt"
    }
    from(
        configurations.runtimeClasspath.get().map {
            if (it.isDirectory) it else zipTree(it)
        }
    )
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register("packageApp") {
    dependsOn("jar")
    doLast {
        val jarFile = file("build/libs/eder.jar")
        val shFile = file("dist/run.command")

        shFile.writeText(
            """
            #!/bin/bash
            SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
            java -jar "${'$'}SCRIPT_DIR/eder.jar" ${'$'}SCRIPT_DIR
            """.trimIndent()
        )
        shFile.setExecutable(true)

        copy {
            from(jarFile)
            into("dist")
        }

        println("✅ Packaged: dist/eder.jar + dist/run.sh")
    }
}
