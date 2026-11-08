plugins {
	id("net.fabricmc.fabric-loom")
}

version = "__VERSION__"

loom {
	mods {
		register("__MOD_ID__") {
			sourceSet(sourceSets["main"])
		}
	}
	runs {
		configureEach {
			runDirectory.set(rootProject.file("run"))
		}
	}
}
