// Top-level build file
buildscript {
    extra["kotlin_version"] = "1.9.22"
    extra["chaquopy_version"] = "15.0.1"

    repositories {
        google()
        mavenCentral()
        maven(url = "https://chaquo.com/maven/")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${extra["kotlin_version"]}")
        classpath("com.chaquo.python:gradle:${extra["chaquopy_version"]}")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://chaquo.com/maven/")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
