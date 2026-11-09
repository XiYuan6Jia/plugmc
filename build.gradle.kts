import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.language.jvm.tasks.ProcessResources

// 共享版本：插件与联调工程统一从 gradle.properties 读取
val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val loaderVersion = providers.gradleProperty("loader_version").get()
val fabricApiVersion = providers.gradleProperty("fabric_api_version").get()
val paperMinecraftVersion = providers.gradleProperty("paper_minecraft_version").get()
val paperApiVersion = providers.gradleProperty("paper_api_version").get()
val baseGroup = providers.gradleProperty("group").get()
val baseVersion = providers.gradleProperty("version").get()

subprojects {
	group = baseGroup
	version = baseVersion

	repositories {
		maven("https://repo.papermc.io/repository/maven-public/")
		mavenCentral()
	}

	// mods/ 与 dev/ 下的 Fabric 工程共享同一套依赖与打包约定
	plugins.withId("net.fabricmc.fabric-loom") {
		dependencies {
			add("minecraft", "com.mojang:minecraft:$minecraftVersion")
			add("implementation", "net.fabricmc:fabric-loader:$loaderVersion")
			add("implementation", "net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
		}

		// fabric.mod.json 里的 ${version} 等占位符统一由构建展开
		val modVersion = version.toString()
		val expandProperties = mapOf(
			"version" to modVersion,
			"minecraft_version" to minecraftVersion,
			"loader_version" to loaderVersion,
		)
		tasks.named<ProcessResources>("processResources") {
			// 资源与占位符里的中文必须按 UTF-8 处理，否则 Windows 默认 GBK 会损坏 JSON
			filteringCharset = "UTF-8"
			inputs.property("version", modVersion)
			inputs.property("minecraft_version", minecraftVersion)
			inputs.property("loader_version", loaderVersion)
			filesMatching("fabric.mod.json") {
				expand(expandProperties)
			}
		}

		// 启动开发服前先把数据包同步进世界目录
		tasks.matching { it.name == "runServer" }.configureEach {
			dependsOn(":datapacks:syncToRun")
		}
	}

	plugins.withId("java") {
		extensions.configure<JavaPluginExtension> {
			toolchain.languageVersion.set(JavaLanguageVersion.of(25))
			if (path.startsWith(":mods:") || path.startsWith(":plugins:")) {
				withSourcesJar()
			}
		}

		tasks.withType<JavaCompile>().configureEach {
			options.release.set(25)
			options.encoding = "UTF-8"
		}

		// Paper 插件：只编译期依赖 paper-api，运行期由服务端提供
		if (path.startsWith(":plugins:")) {
			dependencies {
				add("compileOnly", "io.papermc.paper:paper-api:$paperApiVersion")
			}

			// plugin.yml 里的 ${version} / ${api_version} 统一由构建展开
			val pluginVersion = version.toString()
			val expandProperties = mapOf(
				"version" to pluginVersion,
				"api_version" to paperMinecraftVersion,
			)
			tasks.named<ProcessResources>("processResources") {
				filteringCharset = "UTF-8"
				inputs.property("version", pluginVersion)
				inputs.property("api_version", paperMinecraftVersion)
				filesMatching("plugin.yml") {
					expand(expandProperties)
				}
			}
		}
	}
}

// 联调工程自动聚合 mods/ 下的全部插件
project(":dev") {
	plugins.withId("net.fabricmc.fabric-loom") {
		dependencies {
			rootProject.subprojects
				.filter { it.path.startsWith(":mods:") }
				.sortedBy { it.path }
				.forEach { add("implementation", it) }
		}
	}
}
