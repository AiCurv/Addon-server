// Top-level build file
buildscript {
    val kotlinVersion by extra("1.9.22")
    val chaquopyVersion by extra("15.0.1")

    repositories {
        google()
        mavenCentral()
        maven("https://chaquo.com/maven/")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("com.chaquo.python:gradle:$chaquopyVersion")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://chaquo.com/maven/")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
