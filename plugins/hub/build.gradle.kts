plugins {
	java
}

version = "0.1.1"

dependencies {
	val paperApiVersion = providers.gradleProperty("paper_api_version").get()
	testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
	testImplementation(platform("org.junit:junit-bom:5.11.4"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
	useJUnitPlatform()
}
