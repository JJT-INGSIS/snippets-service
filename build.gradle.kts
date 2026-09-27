plugins {
    id("jjt.spring-service") version "0.2.0"
}

group = "com.jjt.ingsis"
version = "0.0.1-SNAPSHOT"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("tools.jackson.module:jackson-module-kotlin")
}
