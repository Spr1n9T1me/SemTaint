plugins {
    application
    id("tai-e.conventions")
    id("maven-publish.conventions")
}

group = projectGroupId
description = projectArtifactId
version = projectVersion

dependencies {
    // Process options
    implementation("info.picocli:picocli:4.7.6")
    // Logger
    implementation("org.apache.logging.log4j:log4j-api:2.23.1")
    implementation("org.apache.logging.log4j:log4j-core:2.23.1")
    // Process YAML configuration files
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    // Use Soot as frontend
    implementation(files("lib/sootclasses-modified.jar"))
    "org.soot-oss:soot:4.4.1".let {
        // Disable transitive dependencies from Soot in compile classpath
        compileOnly(it) { isTransitive = false }
        testCompileOnly(it) { isTransitive = false }
        runtimeOnly(it)
    }
    // Eliminate SLF4J warning
    implementation("org.slf4j:slf4j-nop:2.0.13")
    // JSR305, for javax.annotation
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    // Use asm to read java class file
    implementation("org.ow2.asm:asm:9.4")
    implementation("org.ow2.asm:asm-commons:9.5")
    implementation("org.ow2.asm:asm-tree:9.5")
    implementation("org.ow2.asm:asm-util:9.5")
    implementation("org.eclipse.jdt:org.eclipse.jdt.core:3.28.0")

    // Dependencies for SemTaint
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")
    implementation("org.aspectj:aspectjweaver:1.9.6")
    implementation("org.apache.commons:commons-compress:1.26.0")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.platform:junit-platform-suite")
}

application {
    mainClass.set("com.semtaint.Driver")
}

task("fatJar", type = Jar::class) {
    group = "build"
    description = "Creates a single jar file including Tai-e and all dependencies"
    manifest {
        attributes["Main-Class"] = "com.semtaint.Driver"
        attributes["Tai-e-Version"] = projectVersion
        attributes["Tai-e-Commit"] = projectCommit
    }
    archiveBaseName.set("tai-e-all")
    from(
        configurations.runtimeClasspath.get().map { file ->
            if (file.isDirectory) file else zipTree(file).matching {
                exclude("META-INF/**/*.RSA")
            }
        }
    )
    from("COPYING", "COPYING.LESSER")
    destinationDirectory.set(rootProject.layout.buildDirectory)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    with(tasks["jar"] as CopySpec)
}

tasks.jar {
    from("COPYING", "COPYING.LESSER")
    destinationDirectory.set(rootProject.layout.buildDirectory)
    manifest {
        attributes["Tai-e-Version"] = projectVersion
        attributes["Tai-e-Commit"] = projectCommit
    }
}

tasks.withType<Test> {
    // Uses JUnit5
    useJUnitPlatform()
    // Increases the maximum heap memory of JUnit test process. The default is 512M.
    // (see org.gradle.process.internal.worker.DefaultWorkerProcessBuilder.build)
    maxHeapSize = "2G"
    // Sets the maximum number of test processes to start in parallel.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).takeIf { it > 0 } ?: 1
    // Sets the default classpath for test execution.
    // (see https://docs.gradle.org/current/userguide/upgrading_version_8.html#test_task_default_classpath)
    val test by testing.suites.existing(JvmTestSuite::class)
    testClassesDirs = files(test.map { it.sources.output.classesDirs })
    classpath = files(test.map { it.sources.runtimeClasspath })
}

tasks.test {
    // Excludes test suites from the default test task
    // to avoid running some tests multiple times.
    filter {
        excludeTestsMatching("*TestSuite")
    }
}

task("testTaieTestSuite", type = Test::class) {
    group = "verification"
    description = "Runs the Tai-e test suite"
    filter {
        includeTestsMatching("TaieTestSuite")
    }
}

// Automatically agree the Gradle ToS when running gradle with '--scan' option
extensions.findByName("buildScan")?.withGroovyBuilder {
    setProperty("termsOfServiceUrl", "https://gradle.com/terms-of-service")
    setProperty("termsOfServiceAgree", "yes")
}
