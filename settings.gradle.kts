pluginManagement {
    // pluginManagement repositories resolve plugins before the repo-reconfiguration plugin can
    // apply, so they must point at EngineHub mirrors directly rather than upstream URLs.
    repositories {
        maven {
            name = "EngineHub"
            url = uri("https://repo.enginehub.org/libs-release/")
            mavenContent {
                releasesOnly()
                includeGroupAndSubgroups("com.sk89q")
                includeGroupAndSubgroups("org.enginehub")
            }
        }
        maven {
            name = "EngineHub Maven Central Mirror"
            url = uri("https://repo.enginehub.org/internal/maven-central-proxy/")
            mavenContent {
                releasesOnly()
            }
        }
        maven {
            name = "EngineHub Gradle Plugin Portal Mirror"
            url = uri("https://repo.enginehub.org/internal/plugin-portal-proxy/")
            mavenContent {
                releasesOnly()
            }
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositories {
        maven {
            name = "CanvasMC"
            url = uri("https://maven.canvasmc.io/releases")
            content {
                includeGroupAndSubgroups("io.canvasmc")
            }
        }
        maven {
            name = "PaperMC"
            url = uri("https://repo.papermc.io/repository/maven-public/")
        }
        maven {
            name = "EngineHub (Non-Mirrored)"
            url = uri("https://repo.enginehub.org/libs-release/")
        }
        mavenCentral()
        maven {
            name = "Minecraft Libraries"
            url = uri("https://libraries.minecraft.net/")
        }
        maven {
            name = "EngineHub Legacy Third-Party"
            url = uri("https://maven.enginehub.org/repo/")
        }
    }
}

logger.lifecycle("""
*******************************************
 You are building WorldGuard!

 If you encounter trouble:
 1) Read COMPILING.md if you haven't yet
 2) Try running 'build' in a separate Gradle run
 3) Use gradlew and not gradle
 4) If you still need help, ask on Discord! https://discord.gg/enginehub

 Output files will be in [subproject]/build/libs
*******************************************
""")

rootProject.name = "worldguard"

includeBuild("build-logic")

include("worldguard-libs")
include("worldguard-libs:core")

listOf("bukkit", "core").forEach {
    include("worldguard-$it")
}