// AGP 9 has built-in Kotlin support, so no org.jetbrains.kotlin.android plugin.
plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
