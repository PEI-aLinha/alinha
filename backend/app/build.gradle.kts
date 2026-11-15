// Assembles the core and ALL plugins into a single deployable (Spring Boot + Modulith).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

// Every subproject living under plugins/** (the submodules).
val pluginProjects = rootProject.subprojects.filter {
    it.projectDir.toPath().startsWith(rootDir.toPath().resolve("plugins"))
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))
    implementation(platform(libs.spring.cloud.bom))

    implementation(project(":core"))
    pluginProjects.forEach { implementation(it) }

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.modulith.starter.jdbc) // events persisted in PostgreSQL
    implementation(libs.spring.cloud.starter.vault.config) // secrets from OpenBao (NFR09)
    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.module.kotlin)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.modulith.starter.test)
}

// Tests (ModularityTest, CI) run without OpenBao: no secrets are needed to verify the modules.
tasks.test {
    systemProperty("spring.cloud.vault.enabled", "false")
    // With Vault off, db.* is not resolved. Take it from the environment (no credentials in code);
    // context tests should use Testcontainers (@ServiceConnection / @DynamicPropertySource), which overrides these.
    systemProperty("db.username", System.getenv("DB_USER") ?: "")
    systemProperty("db.password", System.getenv("DB_PASSWORD") ?: "")
}
