plugins {
	id("net.fabricmc.fabric-loom")
}

version = "0.1.0"

loom {
	mods {
		register("plugmc") {
			sourceSet(sourceSets["main"])
		}
	}
	runs {
		configureEach {
			runDirectory.set(rootProject.file("run"))
		}
	}
}
