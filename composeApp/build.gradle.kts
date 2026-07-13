import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":vfs-api"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.decompose)
            implementation(libs.decompose.compose)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(project(":vfs-archive"))
            implementation(project(":vfs-local"))
            implementation(project(":vfs-smb"))
            implementation(project(":vfs-webdav"))
            implementation(project(":vfs-s3"))
            implementation(compose.desktop.currentOs) {
                exclude(group = "org.jetbrains.compose.material")
            }
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.coil.compose)
            implementation(libs.sevenzipjbinding)
            implementation(libs.sevenzipjbinding.all.platforms)
            implementation(libs.jewel.ui)
            implementation(libs.jewel.int.ui.standalone)
            implementation(libs.jewel.int.ui.decorated.window)
            implementation(libs.intellij.platform.icons)
            implementation(libs.jna)
            implementation(libs.jna.platform)
            implementation(libs.zoomimage.compose)
            implementation(libs.slf4j.simple)
            implementation(libs.exposed.core)
            implementation(libs.exposed.jdbc)
            implementation(libs.sqlite.jdbc)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}


val appVersion: String = project.property("app.version") as String

compose.desktop {
    application {
        mainClass = "com.oruke.onyx.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            // Exposed/SQLite 通过反射加载 JDBC，jlink 无法从静态依赖自动识别 java.sql。
            modules("java.sql")
            packageName = "Onyx"
            packageVersion = appVersion
            description = "Professional dual-pane file manager"
            vendor = "oruke"

            windows {
                iconFile.set(project.file("src/jvmMain/resources/onyx.ico"))
                menuGroup = "Onyx"
                shortcut = true
                dirChooser = true
                perUserInstall = true
            }

            linux {
                shortcut = true
            }
        }
        buildTypes.release.proguard {
            configurationFiles.from(project.file("compose-desktop.pro"))
        }
    }
}

// 生成 BuildConfig.kt，供运行时读取版本号（兼容 Configuration Cache）
val generateBuildConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/buildconfig/com/oruke/onyx")
    val version = appVersion // 在配置阶段捕获值，避免序列化 script 对象
    outputs.dir(outputDir)
    inputs.property("appVersion", version)
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        dir.resolve("BuildConfig.kt").writeText(
            buildString {
                appendLine("package com.oruke.onyx")
                appendLine()
                appendLine("object BuildConfig {")
                appendLine("    const val VERSION = \"$version\"")
                appendLine("}")
            }
        )
    }
}

kotlin.sourceSets.named("jvmMain") {
    kotlin.srcDir(generateBuildConfig.map { layout.buildDirectory.dir("generated/buildconfig") })
}

tasks.named("compileKotlinJvm") {
    dependsOn(generateBuildConfig)
}
