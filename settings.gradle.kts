pluginManagement {
	repositories {
		maven {
			name = "Fabric"
			url = uri("https://maven.fabricmc.net/")
		}
		mavenCentral()
		gradlePluginPortal()
	}
	plugins {
		// 全仓库唯一的 Loom 版本出口；各模块只 apply id，不写版本
		id("net.fabricmc.fabric-loom") version "1.19-SNAPSHOT"
	}
}

plugins {
	// 允许 Gradle 自动下载缺失的 JDK（Minecraft 26.3 需要 Java 25）
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "plugmc"

// mods/<id>/ 下存在构建脚本即视为一个插件工程，新增目录无需改本文件
file("mods").listFiles()
	?.filter { it.isDirectory && it.resolve("build.gradle.kts").isFile }
	?.sortedBy { it.name }
	?.forEach { include(":mods:${it.name}") }

// 数据包聚合工程：每个数据包仍是 datapacks/<id>/ 下的纯文件夹
include(":datapacks")

// 联调工程：把 mods/ 下的全部插件装进同一个运行环境
include(":dev")
