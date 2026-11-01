plugins {
    alias(libs.plugins.fabric.loom)
}

val archivesBaseName = providers.gradleProperty("archives_base_name").get()
val mavenGroup = providers.gradleProperty("maven_group").get()

// Tester builds are opt-in: normal `./gradlew build` keeps the release version. Passing
// `-PtesterSuffix=tester.1` appends the suffix to the build version (and therefore to the JAR,
// source archive and embedded build stamp) without touching any dependency version.
val testerSuffix = providers.gradleProperty("testerSuffix").orNull?.trim().orEmpty()
if (testerSuffix.isNotEmpty() && !testerSuffix.matches(Regex("[A-Za-z0-9._-]+"))) {
    throw GradleException("testerSuffix may only contain letters, digits, dots, dashes and underscores")
}
val buildVersion = libs.versions.mod.version.get() +
    if (testerSuffix.isEmpty()) "" else "-$testerSuffix"

fun gitOutput(vararg args: String): String = try {
    providers.exec {
        commandLine("git", *args)
        workingDir = projectDir
    }.standardOutput.asText.get().trim()
} catch (_: Exception) {
    ""
}

val gitCommit = gitOutput("rev-parse", "--short", "HEAD").ifEmpty { "unknown" }
val gitDirty = gitOutput("status", "--porcelain").isNotEmpty()
val gitCommitLabel = if (gitDirty) "$gitCommit-dirty" else gitCommit

base {
    archivesName = archivesBaseName
    version = buildVersion
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

// The Phase 0.4/0.5 harness is a separate, test-only Fabric mod. Loom creates the
// `gametest` source set and the `runClientGameTest` run configuration; nothing here
// is part of the shipped JAR or the normal `runClient` classpath.
fabricApi {
    configureTests {
        createSourceSet.set(true)
        modId.set("comet-gametest")
        enableGameTests.set(false)
        enableClientGameTests.set(true)
        eula.set(true)
        clearRunDirectory.set(true)
        username.set("HarnessClient")
    }
}

dependencies {
    // Fabric
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)

    // Meteor
    implementation(libs.meteor.client)

    // Test-only Fabric API client game-test module (see gradle/libs.versions.toml).
    // The source set is created by Loom's configureTests above, so no Kotlin DSL accessor exists.
    add("gametestImplementation", libs.fabric.client.gametest)
    // MinecraftServer's class file names a Fabric resource-loader interface. It is already
    // present at runtime through Meteor; compileOnly makes that interface visible to the harness.
    add("gametestCompileOnly", "net.fabricmc.fabric-api:fabric-resource-loader-v1:2.0.13+9edec1269e")

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
            "build_version" to buildVersion,
            "git_commit" to gitCommitLabel,
            "git_dirty" to gitDirty.toString(),
            "tester_suffix" to testerSuffix,
        )

        inputs.properties(propertyMap)
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
        filesMatching("comet-scaffold-build.properties") { expand(propertyMap) }
        filesMatching("comet-build.properties") { expand(propertyMap) }
    }

    jar {
        inputs.property("archivesName", archivesBaseName)

        // Tester builds are self-describing even when the file is renamed: the manifest carries the
        // exact build version, the short HEAD (marked -dirty for an unclean tree) and the tester marker.
        manifest {
            attributes(
                mapOf(
                    "Implementation-Title" to archivesBaseName,
                    "Implementation-Version" to buildVersion,
                    "Implementation-Vendor" to "Boyonabike",
                    "Comet-Version" to buildVersion,
                    "Comet-Git-Commit" to gitCommitLabel,
                    "Comet-Minecraft-Version" to libs.versions.minecraft.get(),
                    "Comet-Meteor-Build" to libs.versions.meteor.get(),
                    "Comet-Tester-Build" to testerSuffix.isNotEmpty(),
                    "Comet-Tester-Suffix" to testerSuffix,
                )
            )
        }

        from("LICENSE")
        from("LICENSE-CC0")
        from("LICENSE-NoTextureRotations")
        from("LICENSE-Inter")
        from("LICENSE-Lucide")
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

// Phase 1.1 purity guard. The combat core must compile and test without Minecraft or Meteor on the
// classpath, so only core/combat/mc may name their types. This runs before tests and fails the build.
val verifyCombatPurity = tasks.register("verifyCombatPurity") {
    group = "verification"
    description = "Fails if a pure core/combat source or test names Minecraft or Meteor types."
    val main = fileTree("src/main/java/dev/tntcoating/core/combat") {
        include("**/*.java")
        exclude("mc/**")
    }
    val test = fileTree("src/test/java/dev/tntcoating/core/combat") {
        include("**/*.java")
        exclude("mc/**")
    }
    val sources = main + test
    inputs.files(sources)
    doLast {
        val forbidden = listOf("net.minecraft", "meteordevelopment")
        val violations = mutableListOf<String>()
        sources.files.sortedBy { it.path }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                forbidden.filter { line.contains(it) }.forEach { token ->
                    violations += "${file.path}:${index + 1} names $token"
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw org.gradle.api.GradleException(
                "core/combat must stay Minecraft-free:\n" + violations.joinToString("\n"))
        }
    }
}

tasks.named("check") { dependsOn(verifyCombatPurity) }

// Minecraft prefers X11 unless MC_DEBUG_PREFER_WAYLAND is set. This Flatpak session
// exposes Wayland only, so the harness must opt in before GLFW initializes.
tasks.named<org.gradle.api.tasks.JavaExec>("runClientGameTest") {
    systemProperty("MC_DEBUG_ENABLED", "true")
    systemProperty("MC_DEBUG_PREFER_WAYLAND", "true")
    listOf("scenarios", "delays", "tps", "repeats", "jitter", "seed").forEach { key ->
        providers.gradleProperty("combat.$key").orNull?.let { value ->
            systemProperty("comet.harness.$key", value)
        }
    }
    val requestedOutput = providers.gradleProperty("combat.output").orNull
    systemProperty("comet.harness.output",
        requestedOutput?.let { file(it).absolutePath }
            ?: layout.buildDirectory.dir("combat-harness").get().asFile.absolutePath)
}

tasks.register("combatHarness") {
    group = "verification"
    description = "Runs the scripted CrystalCombat client/server harness."
    dependsOn("runClientGameTest")
}

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
    from("relay") { into("relay") }
    from("gradlew", "gradlew.bat", "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
        "LICENSE", "LICENSE-CC0", "LICENSE-NoTextureRotations", "LICENSE-Inter", "LICENSE-Lucide", "THIRD_PARTY_NOTICES.md", "README.md", "CHANGELOG.md", "CONTRIBUTING.md")
}

tasks.named("build") { dependsOn(sourceDistribution) }
