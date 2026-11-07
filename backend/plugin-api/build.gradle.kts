// Contrato entre o Core e os plugins. Não depende do Core.
// Mudanças aqui afetam TODOS os plugins: ver README > "Mudar a plugin-api".
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(platform(libs.spring.modulith.bom))
    api(libs.spring.modulith.api) // @NamedInterface, @ApplicationModule
}
