plugins {
	java
}

version = "0.1.5"

dependencies {
	val paperApiVersion = providers.gradleProperty("paper_api_version").get()
	// 周期结算要发奖金：只用到 EconomyService 这个公开接口，不依赖实现
	compileOnly(project(":plugins:economy"))
	testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
	testImplementation(platform("org.junit:junit-bom:5.11.4"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
	useJUnitPlatform()
}
