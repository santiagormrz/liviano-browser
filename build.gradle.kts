buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // GeckoView 160 trae kotlin-stdlib 2.4: el compilador de Kotlin tiene que ser igual o más nuevo
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    // AGP 9 trae Kotlin incorporado: ya no hace falta el plugin org.jetbrains.kotlin.android
    id("com.android.application") version "9.1.1" apply false
}
