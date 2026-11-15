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
    implementation(libs.spring.boot.starter.data.redis) // job outbox relay -> Redis Streams (RNF02)
    implementation(libs.json.schema.validator) // job envelopes checked against contracts/jobs

    implementation(libs.spring.boot.starter.security.oauth2.resource.server) // Keycloak JWTs + RBAC

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.oauth2.resource.server.test)
    testImplementation(libs.spring.boot.starter.actuator)
    testImplementation(libs.testcontainers.vault)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.spring.boot.starter.flyway) // audit tests run the real core migrations
    testRuntimeOnly(libs.flyway.postgresql)
    testRuntimeOnly(libs.postgresql)

}

// contracts/jobs is the single source of the job formats (Kotlin and Python): ship it in the jar.
tasks.processResources {
    from(rootProject.file("contracts/jobs")) {
        include("*.schema.json")
        into("contracts/jobs")
    }
}

// OpenBaoTenantSecretStoreTest runs with the real app policy from deploy/openbao.
tasks.test {
    systemProperty("alinha.openbao.policy-dir", rootProject.file("deploy/openbao").absolutePath)
}
