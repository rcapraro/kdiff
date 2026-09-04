// kctfork exposes the compiler's plugin API, which is opt-in. Scoped to tests so the processor's
// own sources stay free of it.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
}

dependencies {
    implementation(project(":kdiff-annotations"))
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet.ksp)

    // kdiff-runtime is a test-only dependency: the processor emits references to its types by
    // name and never links against them, but compile-testing needs them on the test classpath.
    testImplementation(project(":kdiff-runtime"))
    testImplementation(libs.kctfork.ksp)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}
