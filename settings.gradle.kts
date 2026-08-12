pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://chaquo.com/maven/")
    }
}

rootProject.name = "AddonServer"
include(":app")
