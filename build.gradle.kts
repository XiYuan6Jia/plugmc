plugins {
	// Loom 版本固定在构建脚本中，其余版本见 gradle.properties
	id("net.fabricmc.fabric-loom") version "1.19-SNAPSHOT"
}

val minecraftVersion: String by project
val loaderVersion: String by project
val fabricApiVersion: String by project

dependencies {
	// Loom 会自动配置 Minecraft 仓库与 mappings
	minecraft("com.mojang:minecraft:$minecraftVersion")
	implementation("net.fabricmc:fabric-loader:$loaderVersion")
	implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
}

loom {
	mods {
		"plugmc" {
			sourceSet(sourceSets["main"])
		}
	}
}

processResources {
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
