// 纯 Kotlin 模块：配色、点阵字形、表盘排版、天气动画与网络服务。
// 不依赖 Android，可在电脑上直接跑单元测试和出预览图。
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // 预览图输出目录（./gradlew :core:test -Ppreview 时生成）
    systemProperty("dotclock.preview", project.findProperty("preview")?.let { layout.buildDirectory.dir("preview").get().asFile.path } ?: "")
}
