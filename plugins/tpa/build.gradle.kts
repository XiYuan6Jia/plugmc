plugins {
	java
}

version = "0.1.0"

dependencies {
	// 只用到 HomeService 这个只读接口，不依赖 sethome 的实现
	compileOnly(project(":plugins:sethome"))
}
