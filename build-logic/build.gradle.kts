plugins {
    `kotlin-dsl`
}

dependencies {
    // AGP instrumentation API (AsmClassVisitorFactory, AndroidComponentsExtension, ...).
    // compileOnly is correct for an included-build convention plugin: the consuming module
    // (app) already applies AGP, so its copy is available at runtime when the plugin is applied.
    compileOnly("com.android.tools.build:gradle-api:9.2.1")
    // ASM is used to rewrite the offending bytecode at compile time.
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-commons:9.7")
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        create("removeFirstLastFix") {
            id = "buildlogic.removefirstlast-fix"
            implementationClass = "buildlogic.RemoveFirstLastFixPlugin"
        }
        create("moduleBoundaries") {
            id = "buildlogic.module-boundaries"
            implementationClass = "buildlogic.ModuleBoundariesPlugin"
        }
        create("kotlinSourceSize") {
            id = "buildlogic.kotlin-source-size"
            implementationClass = "buildlogic.KotlinSourceSizePlugin"
        }
    }
}
