plugins {
    id("jjt.spring-service") version "0.3.1"
}

group = "com.jjt.ingsis"
version = "0.0.1-SNAPSHOT"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("com.newrelic.agent.java:newrelic-api:9.5.0")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("com.h2database:h2")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}

tasks.test {
    mustRunAfter("bootJar")
    inputs.property("auth0LiveAcceptance", providers.environmentVariable("AUTH0_LIVE_ACCEPTANCE").orElse("false"))
    inputs.property("parserAdminTestImage", providers.environmentVariable("PARSER_ADMIN_TEST_IMAGE").orElse(""))
    inputs.property(
        "parserPrintScriptTestImage",
        providers.environmentVariable("PARSER_PRINTSCRIPT_TEST_IMAGE").orElse(""),
    )
    inputs.property("printScriptLintUrl", providers.environmentVariable("PRINTSCRIPT_LINT_URL").orElse(""))
    inputs.property("printScriptExecutionUrl", providers.environmentVariable("PRINTSCRIPT_EXECUTION_URL").orElse(""))
    inputs.files(
        "docs/creation.md",
        "docs/formatted-download.md",
        "docs/formatting-rules.md",
        "docs/linting-rules.md",
        "docs/reading.md",
        "docs/sharing.md",
        "docs/tests.md",
        "docs/update.md",
    )
    inputs.property("printScriptServiceUrl", providers.environmentVariable("PRINTSCRIPT_SERVICE_URL").orElse(""))
    inputs.property("permissionsServiceUrl", providers.environmentVariable("PERMISSIONS_SERVICE_URL").orElse(""))
}
