import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.spring.boot) apply false
}

subprojects {
    group = "pt.alinha"
    version = "0.1.0-SNAPSHOT"

    repositories { mavenCentral() }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") }
        }
        // Gradle's bundled launcher does not match JUnit 6 from the Boot BOM; align it explicitly.
        dependencies {
            "testRuntimeOnly"(platform(libs.spring.boot.bom))
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        }
    }

    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
