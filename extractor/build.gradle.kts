plugins {
    kotlin("jvm") version "2.1.20"
    application
}
repositories { mavenCentral() }
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.20")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
}
kotlin { jvmToolchain(17) }
application { mainClass.set("ExtractorKt") }
