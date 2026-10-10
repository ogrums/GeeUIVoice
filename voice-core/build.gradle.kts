plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":emotion-core"))
    testImplementation(kotlin("test"))
}
