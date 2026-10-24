import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

plugins {
    java
}

group = "com.exigentsol"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.214-stable")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
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
        println("Deployed ${src.fileName}. Restart with: sudo systemctl restart mcd")
    }
}
