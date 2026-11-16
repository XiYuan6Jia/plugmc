plugins {
	java
}

dependencies {
	compileOnly(project(":plugins:economy"))
	// 列车分类只是把请求转给 train 的只读/本人操作接口，没有它菜单照样能开（显示「暂时不可用」）
	compileOnly(project(":plugins:train"))
}

version = "0.4.0"
