plugins {
    id("jjt.spring-service") version "0.2.0"
}

group = "com.jjt.ingsis"
version = "0.0.1-SNAPSHOT"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("tools.jackson.module:jackson-module-kotlin")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("com.h2database:h2")
}

tasks.test {
    inputs.property("printScriptServiceUrl", providers.environmentVariable("PRINTSCRIPT_SERVICE_URL").orElse(""))
}
