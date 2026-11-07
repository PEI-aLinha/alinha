// Junta o Core e TODOS os plugins num único deployable (Spring Boot + Modulith).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

// Todos os subprojetos que vivem em plugins/** (os submodules).
val pluginProjects = rootProject.subprojects.filter {
    it.projectDir.toPath().startsWith(rootDir.toPath().resolve("plugins"))
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))

    implementation(project(":core"))
    pluginProjects.forEach { implementation(it) }

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.modulith.starter.jdbc) // eventos persistidos em PostgreSQL
    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.module.kotlin)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.modulith.starter.test)
}
