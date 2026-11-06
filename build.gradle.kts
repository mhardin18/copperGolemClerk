import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

plugins {
    java
}

group = "com.exigentsol"
version = "3.2.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// The newest Paper API to build and test against, and the oldest Paper build the plugin supports
// (PaperSupport.MIN_BUILD: Paper added the golem sorting event in 26.3 build 190).
val paperApi = "26.3.build.244-stable"
val minPaperApi = "26.3.build.190-stable"

// The oldest supported API, used only to check the plugin still compiles against it.
val minPaper: Configuration by configurations.creating

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApi")
    testImplementation("io.papermc.paper:paper-api:$paperApi")
    minPaper("io.papermc.paper:paper-api:$minPaperApi")
    // A fake Paper server for unit tests of the code that calls the Paper API.
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.3:4.118.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // MockBukkit reports Paper API it hasn't implemented as a skipped test: fail instead of
    // letting a whole test class go quietly green.
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null && result.skippedTestCount > 0) {
                throw GradleException("${result.skippedTestCount} unit test(s) were skipped (MockBukkit lacks some API?)."
                    + " See build/reports/tests/test/index.html")
            }
        }
    })
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    // Rebuild plugin.yml when the version changes (otherwise Gradle reuses the old one).
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal"))
}

// "Requires Paper 26.3 build 190 or newer" stays true only if the plugin uses nothing Paper
// added later. Compile the plugin against build 190's API as part of `check` (and so `build`):
// anything newer fails here instead of on someone's server.
val compileAgainstMinPaper by tasks.registering(JavaCompile::class) {
    description = "Compiles the plugin against the oldest supported Paper API ($minPaperApi)."
    group = "verification"
    source = sourceSets.main.get().java
    classpath = minPaper
    destinationDirectory.set(layout.buildDirectory.dir("min-paper-classes"))
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}

tasks.check {
    dependsOn(compileAgainstMinPaper)
}

// Copy the built jar into the pluginDev test server. Then: sudo systemctl restart mcd
// Writes a temp file and renames it over the old jar, so the running server keeps
// its open copy intact until the restart.
tasks.register("deploy") {
    dependsOn(tasks.jar)
    val jarFile = tasks.jar.flatMap { it.archiveFile }
    doLast {
        val src = jarFile.get().asFile.toPath()
        val dir = Path.of("/opt/minecraft/pluginDev/plugins")
        val tmp = Files.createTempFile(dir, ".deploy-", ".tmp")
        Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING)
        // Readable by the minecraft group (the server runs as user minecraft).
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-rw-r--"))
        Files.move(tmp, dir.resolve(src.fileName),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        // Remove other versions' jars, or the server would load the plugin twice.
        // SmartCopperGolems-*.jar is this plugin under its old name.
        Files.newDirectoryStream(dir, "{CopperGolemClerk,SmartCopperGolems}-*.jar").use { jars ->
            for (old in jars) {
                if (old.fileName != src.fileName) {
                    Files.delete(old)
                    println("Removed old ${old.fileName}")
                }
            }
        }
        println("Deployed ${src.fileName}. Restart with: sudo systemctl restart mcd")
    }
}
