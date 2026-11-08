plugins {
	base
}

// 每个含 pack.mcmeta 的子目录就是一个数据包；新增目录无需改本文件
val packDirs = file(".").listFiles()
	?.filter { it.isDirectory && it.resolve("pack.mcmeta").isFile }
	?.sortedBy { it.name }
	.orEmpty()

val packZips = packDirs.map { dir ->
	val packName = dir.name
	tasks.register<Zip>("zip-$packName") {
		group = "datapack"
		description = "打包数据包 $packName"
		from(dir)
		archiveFileName.set("$packName.zip")
		destinationDirectory.set(layout.buildDirectory.dir("libs"))
	}
}

tasks.register("buildDatapacks") {
	group = "datapack"
	description = "打包 datapacks/ 下的全部数据包"
	dependsOn(packZips)
}

tasks.named("build") {
	dependsOn(packZips)
}

tasks.register<Copy>("syncToRun") {
	group = "datapack"
	description = "把数据包同步到 run/world/datapacks（启动开发服前会自动执行）"
	packDirs.forEach { dir ->
		from(dir) {
			into(dir.name)
		}
	}
	into(rootProject.layout.projectDirectory.dir("run/world/datapacks"))
}
