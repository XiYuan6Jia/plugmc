plugins {
	id("net.fabricmc.fabric-loom")
}

version = "0.1.0"

dependencies {
	// 只编译期依赖宿主，Minecraft / Fabric API 由根构建脚本提供
	compileOnly(project(":mods:plugmc"))
}
