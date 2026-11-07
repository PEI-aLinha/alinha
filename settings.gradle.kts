pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "alinha"

// ---- Core (vive neste repo) -------------------------------------------------
include(":plugin-api", ":core", ":app")
project(":plugin-api").projectDir = file("backend/plugin-api")
project(":core").projectDir = file("backend/core")
project(":app").projectDir = file("backend/app")

// ---- Plugins (submodules em plugins/<tipo>/<id>) ----------------------------
// Cada submodule com backend/build.gradle.kts entra automaticamente no build
// como o projeto ":<id>". Adicionar um plugin = adicionar o submodule; não é
// preciso mexer neste ficheiro.
val pluginsDir = file("plugins")
pluginsDir.listFiles { f -> f.isDirectory }.orEmpty()
    .flatMap { kind -> kind.listFiles { f -> f.isDirectory }.orEmpty().toList() }
    .filter { File(it, "backend/build.gradle.kts").exists() }
    .sortedBy { it.name }
    .forEach { dir ->
        include(":${dir.name}")
        project(":${dir.name}").projectDir = File(dir, "backend")
    }
