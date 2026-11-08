plugins {
	id("net.fabricmc.fabric-loom")
}

extensions.configure<org.gradle.api.plugins.BasePluginExtension> {
	archivesName.set("plugmc-dev")
}

// 联调工程本身不含代码，插件依赖由根构建脚本自动聚合
loom {
	runs {
		configureEach {
			runDirectory.set(rootProject.file("run"))
		}
	}
}
