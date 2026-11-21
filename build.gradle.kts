import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.kover)
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

// Coverage (RNF19) for the modules that live in this repo. `./gradlew koverXmlReport` writes
// build/reports/kover/report.xml, which the CI uploads to SonarCloud. Plugins measure their own coverage.
repositories { mavenCentral() } // the root resolves the Kover reporter
val coreModules = listOf(":plugin-api", ":core", ":app", ":bff")
coreModules.forEach { path ->
    project(path).pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        project(path).pluginManager.apply("org.jetbrains.kotlinx.kover")
    }
}
dependencies { coreModules.forEach { kover(project(it)) } }
kover { reports { verify { rule { minBound(70) } } } } // RNF19, same bar as Vitest and pytest
