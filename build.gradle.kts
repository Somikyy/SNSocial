plugins {
    java
}

group = "network.somikyy"
version = "26.8.1"
description = "Rewards players for subscribing to your Telegram channel and VK group"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    // Both dependencies are compile-only: the server supplies paper-api, and the
    // PlaceholderAPI classes are touched only when the PAPI plugin is installed.
    // Compiling against 1.20.1 rather than a newer api is deliberate: it is the oldest
    // supported server, so anything that compiles here exists everywhere we claim to run.
    // paper-api 1.20.1 already bundles Adventure and MiniMessage, and 1.20.1 is published
    // for Java 17, so no toolchain gymnastics are needed.
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")

    // Java 17 bytecode: SNSocial has to run on the 1.20.1 servers whose admins the
    // competitors dropped. Nothing in a subscription checker needs a newer language level.
    options.release.set(17)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "SNSocial",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Somikyy Network",
        )
    }
    archiveFileName.set("SNSocial-${project.version}.jar")
}

// Convenience: gradle selftest  (needs bash; runs the dependency-free offline suite)
tasks.register<Exec>("selftest") {
    group = "verification"
    description = "Builds offline against stubs and runs the core self-test"
    commandLine("bash", "tools/offline/verify.sh")
}
