plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension>("detekt") {
        toolVersion = "1.23.8"
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        baseline = rootProject.file("config/detekt/baselines/${project.name}.xml")
        source.setFrom(
            files(
                "src/commonMain",
                "src/jvmMain",
            ),
        )
        parallel = true
        basePath = rootProject.projectDir.absolutePath
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        exclude("**/build/**")
        exclude("**/generated/**")
        exclude("**/composeResources/**")
        reports {
            xml.required.set(true)
            html.required.set(true)
            md.required.set(true)
            sarif.required.set(false)
        }
    }

    if (project.name == "composeApp" || project.name == "vfs-archive") {
        tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
            val isolatedTempDirectory = layout.buildDirectory.dir("tmp/$name/process")
            doFirst {
                val directory = isolatedTempDirectory.get().asFile
                directory.mkdirs()
                // 7-Zip-JBinding 会把固定名称的 native 解压到 java.io.tmpdir，并行测试必须隔离目录。
                systemProperty("java.io.tmpdir", directory.absolutePath)
            }
        }
    }
}
