import org.gradle.api.artifacts.dsl.LockMode
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
// build/reports/kover/report.xml, which the CI uploads to SonarCloud. Plugins measure their own coverage:
// they get Kover too, but stay out of the merged report. Their CI (.github/workflows/plugin-ci.yml) runs
// `./gradlew :<id>:koverXmlReport` and sends <plugin>/backend/build/reports/kover/report.xml to the
// plugin's own SonarCloud project.
repositories { mavenCentral() } // the root resolves the Kover reporter
val coreModules = listOf(":plugin-api", ":core", ":app", ":bff")
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { pluginManager.apply("org.jetbrains.kotlinx.kover") }
}
dependencies { coreModules.forEach { kover(project(it)) } }
kover { reports { verify { rule { minBound(70) } } } } // RNF19, same bar as Vitest and pytest

// Dependency locking: each core module and the root keep a gradle.lockfile with the resolved versions.
// After changing a dependency or libs.versions.toml, refresh them with
//   ./gradlew dependencies :plugin-api:dependencies :core:dependencies :app:dependencies :bff:dependencies --write-locks
// :app is lenient because it also pulls in the plugins, whose dependencies change in their own repos.
dependencyLocking { lockAllConfigurations() }
coreModules.forEach { path ->
    project(path).dependencyLocking {
        lockAllConfigurations()
        if (path == ":app") lockMode = LockMode.LENIENT
    }
}
