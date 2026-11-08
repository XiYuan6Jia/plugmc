plugins {
	// Loom 版本固定在构建脚本中，其余版本见 gradle.properties
	id("net.fabricmc.fabric-loom") version "1.19-SNAPSHOT"
}

val minecraftVersion = property("minecraft_version") as String
val loaderVersion = property("loader_version") as String
val fabricApiVersion = property("fabric_api_version") as String

dependencies {
	// Loom 会自动配置 Minecraft 仓库与 mappings
	minecraft("com.mojang:minecraft:$minecraftVersion")
	implementation("net.fabricmc:fabric-loader:$loaderVersion")
	implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
}

loom {
	mods {
		register("plugmc") {
			sourceSet(sourceSets["main"])
		}
	}
}

tasks.processResources {
	val modVersion = project.version.toString()
	inputs.property("version", modVersion)

	filesMatching("fabric.mod.json") {
		expand("version" to modVersion)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(25)
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
	withSourcesJar()
}
