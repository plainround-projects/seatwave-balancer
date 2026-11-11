plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}

group = "io.plainround"
version = "1.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(libs.result4j)
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    implementation(libs.hikari)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.awaitility)
    // H2 in its PostgreSQL mode runs the same db/schema.sql as the real database, so the hold rule
    // is tested in ./gradlew check without Docker; the stack tests run it on PostgreSQL itself.
    testImplementation(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// The tests tagged "stack" need the running Compose stack; ./gradlew check never runs them, so a
// reader without Docker can still build and test everything else.
tasks.test {
    useJUnitPlatform {
        excludeTags("stack")
    }
    systemProperty("seatwave.schema", file("db/schema.sql").absolutePath)
    systemProperty("seatwave.haproxy", file("haproxy/haproxy.cfg").absolutePath)
    // Every file a test reads is an input, so a change to any of them runs the tests again
    // (the configuration rules read compose.yaml, both Dockerfiles and the settings files).
    inputs.file("db/schema.sql")
    inputs.file("haproxy/haproxy.cfg")
    inputs.file("compose.yaml")
    inputs.file("Dockerfile")
    inputs.dir("keepalived")
    inputs.dir("config")
    testLogging {
        events("passed", "failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("stackTest") {
    group = "verification"
    description = "Runs the tests against the running stack; scripts/stack.sh test starts and stops it."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("stack")
    }
    // The stack is outside Gradle's view, so these tests are never up to date.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// The XML report gives the exact covered and missed line counts that the README quotes.
tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                // The floor for the whole project; the command-line entry points are the untested remainder.
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// The jars the container image copies: this project's jar and its runtime libraries, in one folder.
tasks.register<Sync>("stackFiles") {
    group = "build"
    description = "Collects the jar and its runtime libraries into build/stack/lib for the Dockerfile."
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("stack/lib"))
}

// One command per example: each entry point gets its own task, so a reader never assembles a classpath.
// SeatWave itself has none: it needs the shared database, so it runs only in the Compose stack.
val entryPoints = mapOf(
    "load" to "io.plainround.seatwave.balancer.harness.HarnessCli",
    "deploy" to "io.plainround.seatwave.balancer.deploy.DeployCli",
)

entryPoints.forEach { (taskName, entryPoint) ->
    tasks.register<JavaExec>(taskName) {
        group = "application"
        description = "Runs $entryPoint."
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = entryPoint
        // Paths in --args are read from the project folder, wherever Gradle was started.
        workingDir = rootDir
        // The deploy reads its confirmation from the terminal.
        standardInput = System.`in`
    }
}
