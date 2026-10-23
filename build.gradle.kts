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
tasks.register<Copy>("deploy") {
    dependsOn(tasks.jar)
    from(tasks.jar)
    into("/opt/minecraft/pluginDev/plugins")
}
