plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))
    implementation(platform(libs.spring.cloud.bom))
    testImplementation(platform(libs.spring.boot.bom))

    api(project(":plugin-api"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.spring.vault.core) // TenantSecretStore on OpenBao (KV v2)

    // TODO sprint 0: security (Keycloak) — JWT resource server + RBAC
    // implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.vault)
    testImplementation(libs.testcontainers.junit.jupiter)
}

// OpenBaoTenantSecretStoreTest runs with the real app policy from deploy/openbao.
tasks.test {
    systemProperty("alinha.openbao.policy-dir", rootProject.file("deploy/openbao").absolutePath)
}
