plugins {
	java
}

version = "0.1.0"

dependencies {
	// 只用到 economy 的 API（TradeBoard / EconomyService / 三个 Listing 记录），
	// 运行期由 economy 插件提供，因此是 compileOnly + softdepend。
	compileOnly(project(":plugins:economy"))
}
