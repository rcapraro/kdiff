plugins {
    alias(libs.plugins.ksp)
    application
}

dependencies {
    implementation(project(":kdiff-annotations"))
    implementation(project(":kdiff-runtime"))
    ksp(project(":kdiff-processor"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

application {
    mainClass.set("tutorial.app.MainKt")
}
