plugins {
    alias(libs.plugins.fabric.loom)
}

val archivesBaseName = providers.gradleProperty("archives_base_name").get()
val mavenGroup = providers.gradleProperty("maven_group").get()

base {
    archivesName = archivesBaseName
    version = libs.versions.mod.version.get()
    group = mavenGroup
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        filter {
            includeGroup("maven.modrinth")
        }
    }
}

dependencies {
    // Fabric
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)

    // Meteor
    implementation(libs.meteor.client)

    // Optional integrations, present at runtime only if the player installed them
    compileOnly(libs.xaero.minimap)
    compileOnly(libs.baritone)

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
    }
}

fun toMinecraftCompat(version: String): String {
    val stable = Regex("""^(\d{2})\.([1-9]\d*)(?:\.(\d+))?$""")

    stable.matchEntire(version)?.let {
        val (year, drop, _) = it.destructured
        return "~$year.$drop"
    }

    val pre = Regex("""^(\d{2})\.([1-9]\d*)-pre[-.](\d+)$""")
    pre.matchEntire(version)?.let {
        return version.replace("-pre-", "-pre.")
    }

    val rc = Regex("""^(\d{2})\.([1-9]\d*)-rc[-.](\d+)$""")
    rc.matchEntire(version)?.let {
        return version.replace("-rc-", "-rc.")
    }

    return version
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "minecraft_version" to toMinecraftCompat(libs.versions.minecraft.get()),
            "jdk_version" to libs.versions.jdk.get(),
            "meteor_build" to libs.versions.meteor.get(),
        )

        inputs.properties(propertyMap)
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
        filesMatching("comet-scaffold-build.properties") { expand(propertyMap) }
    }

    jar {
        inputs.property("archivesName", archivesBaseName)

        from("LICENSE")
        from("LICENSE-CC0")
        from("LICENSE-NoTextureRotations")
        from("THIRD_PARTY_NOTICES.md")
    }

    withType<org.gradle.api.tasks.bundling.AbstractArchiveTask>().configureEach {
        archiveVersion.set("${project.version}-mc${libs.versions.minecraft.get()}")
    }

    withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(
            listOf(
                "-Xlint:deprecation",
                "-Xlint:unchecked"
            )
        )
    }
}

tasks.test { useJUnitPlatform() }

// Distribute this beside the JAR: GPL-covered adaptations need their modified source
// and the build inputs, not only a link to the original upstream project.
val sourceDistribution = tasks.register<Zip>("sourceDistribution") {
    group = "build"
    description = "Packages Comet's corresponding source and Gradle build inputs."
    archiveClassifier.set("source")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from("src") { into("src") }
    from("gradle") { into("gradle") }
    from("scripts") {
        into("scripts")
        exclude("**/__pycache__/**", "**/*.pyc")
    }
    from("docs") {
        into("docs")
        exclude("superpowers/**")
    }
    from("gradlew", "gradlew.bat", "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
        "LICENSE", "LICENSE-CC0", "LICENSE-NoTextureRotations", "THIRD_PARTY_NOTICES.md", "README.md", "CHANGELOG.md", "CONTRIBUTING.md")
}

tasks.named("build") { dependsOn(sourceDistribution) }
