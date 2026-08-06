import net.minecrell.pluginyml.bukkit.BukkitPluginDescription

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
    maven("https://repo.erethon.de/snapshots/")
    maven("https://jitpack.io")
    mavenCentral()
}
plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("com.gradleup.shadow") version "9.3.1"
    id("net.minecrell.plugin-yml.bukkit") version "0.5.1"
}

group = "cat.maki.makiscreen"
version = "2.3.3"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    paperweight.paperDevBundle("26.2.build.+")
    compileOnly("org.geysermc.geyser:api:2.10.0-SNAPSHOT")
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
    implementation("de.erethon:bedrock:1.5.18") { isTransitive = false }
    // FFmpeg
    implementation("org.bytedeco:javacv:1.5.10")
    implementation("org.bytedeco:ffmpeg:6.1.1-1.5.10:windows-x86_64")
    implementation("org.bytedeco:ffmpeg:6.1.1-1.5.10:linux-x86_64")
    implementation("org.bytedeco:javacpp:1.5.10")
    implementation("org.bytedeco:javacpp:1.5.10:windows-x86_64")
    implementation("org.bytedeco:javacpp:1.5.10:linux-x86_64")
    //  yt-dlp output parsing
    implementation("com.alibaba:fastjson:1.2.83")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

paperweight {
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks {
    assemble {
        dependsOn(shadowJar)
    }

    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    test {
        useJUnitPlatform()
    }

    runServer {
        minecraftVersion("26.2")
    }

    reobfJar {
        outputJar.set(layout.buildDirectory.file("libs/MCCinema-${project.version}.jar"))
    }

    shadowJar {
        archiveClassifier.set("all")

        dependencies {
            include(dependency("de.erethon:bedrock:1.5.18"))
            include(dependency("org.bytedeco:javacv:.*"))
            include(dependency("org.bytedeco:ffmpeg:.*"))
            include(dependency("org.bytedeco:javacpp:.*"))
            include(dependency("com.alibaba:fastjson:.*"))
        }

        relocate("de.erethon.bedrock", "de.erethon.mccinema.bedrock")
        mergeServiceFiles()
    }

    bukkit {
        load = BukkitPluginDescription.PluginLoadOrder.POSTWORLD
        main = "de.erethon.mccinema.MCCinema"
        apiVersion = "26.2"
        authors = listOf("Maki", "Malfrador")
        name = "MCCinema"
        version = project.version.toString()
        softDepend = listOf("Geyser-Spigot", "floodgate")

        permissions {
            register("mccinema.create") {
                description = "Allows creating screens"
                default = BukkitPluginDescription.Permission.Default.OP
            }
            register("mccinema.delete") {
                description = "Allows deleting screens"
                default = BukkitPluginDescription.Permission.Default.OP
            }
            register("mccinema.play") {
                description = "Allows playing videos"
                default = BukkitPluginDescription.Permission.Default.OP
            }
            register("mccinema.control") {
                description = "Allows pause/resume/stop/seek"
                default = BukkitPluginDescription.Permission.Default.OP
            }
            register("mccinema.download") {
                description = "Allows downloading videos from YouTube"
                default = BukkitPluginDescription.Permission.Default.OP
            }
            register("mccinema.list") {
                description = "Allows listing screens and videos"
                default = BukkitPluginDescription.Permission.Default.TRUE
            }
            register("mccinema.info") {
                description = "Allows viewing screen info"
                default = BukkitPluginDescription.Permission.Default.TRUE
            }
            register("mccinema.reload") {
                description = "Allows reloading MCCinema config"
                default = BukkitPluginDescription.Permission.Default.OP
            }
            register("mccinema.help") {
                description = "Allows viewing help"
                default = BukkitPluginDescription.Permission.Default.TRUE
            }
            register("mccinema.bedrockdebug") {
                description = "Allows viewing per-player Bedrock compatibility diagnostics"
                default = BukkitPluginDescription.Permission.Default.OP
            }
        }
    }
}
