plugins {
    alias(libs.plugins.jmh)
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(project(":kdiff-annotations"))
    implementation(project(":kdiff-runtime"))
    ksp(project(":kdiff-processor"))
}

// Compiled by `check`, never run by it. A JMH run is minutes and `check` is the definition of done for
// every task here, so running belongs on demand — but compiling is a second, and without it a runtime
// API change can leave this module broken with CI green, discovered only by whoever next benchmarks.
tasks.named("check") { dependsOn("jmhClasses") }

// Run with `./gradlew :kdiff-benchmarks:jmh`, adding `-Pjmh.profilers=gc` for allocation rate, which is
// what this module was added to measure, or `-Pjmh.includes=<pattern>` for a single case.
jmh {
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(2)

    // One second an iteration rather than JMH's ten. These benchmarks compare a runtime against
    // itself across a change, and the metric that decides the change is allocation rate, which is a
    // counter and stable in a short run. Ten-second iterations would put a full sweep past twenty
    // minutes and buy precision the comparison does not need.
    warmupForks.set(0)
    timeOnIteration.set("1s")
    warmup.set("1s")

    project.findProperty("jmh.profilers")?.let { profilers.add(it.toString()) }
    project.findProperty("jmh.includes")?.let { includes.add(it.toString()) }
}
