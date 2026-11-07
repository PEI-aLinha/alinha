// Contract between the core and the plugins. Does not depend on the core.
// Changes here affect EVERY plugin: see README > "Changing the plugin-api".
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(platform(libs.spring.modulith.bom))
    api(libs.spring.modulith.api) // @NamedInterface, @ApplicationModule
}
