pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "alinha"

// ---- Core (lives in this repo) ----------------------------------------------
include(":plugin-api", ":core", ":app")
project(":plugin-api").projectDir = file("backend/plugin-api")
project(":core").projectDir = file("backend/core")
project(":app").projectDir = file("backend/app")

// ---- Plugins (submodules under plugins/<kind>/<id>) -------------------------
// Every submodule with backend/build.gradle.kts joins the build automatically
// as project ":<id>". Adding a plugin = adding the submodule; this file never
// needs to change (RNF17).
val pluginsDir = file("plugins")
pluginsDir.listFiles { f -> f.isDirectory }.orEmpty()
    .flatMap { kind -> kind.listFiles { f -> f.isDirectory }.orEmpty().toList() }
    .filter { File(it, "backend/build.gradle.kts").exists() }
    .sortedBy { it.name }
    .forEach { dir ->
        include(":${dir.name}")
        project(":${dir.name}").projectDir = File(dir, "backend")
    }
