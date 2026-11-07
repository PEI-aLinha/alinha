plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))

    api(project(":plugin-api"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.module.kotlin)

    // TODO sprint 0: segurança (Keycloak) — resource server JWT + RBAC
    // implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")

    testImplementation(libs.spring.boot.starter.test)
}
