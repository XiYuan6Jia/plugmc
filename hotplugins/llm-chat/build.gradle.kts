plugins {
	id("net.fabricmc.fabric-loom")
}

version = "0.1.0"

dependencies {
	compileOnly(project(":mods:plugmc"))
}
