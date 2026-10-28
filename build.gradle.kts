import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

plugins {
    java
}

group = "com.exigentsol"
version = "2.4.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.214-stable")
    testImplementation("io.papermc.paper:paper-api:26.3.build.214-stable")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
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
