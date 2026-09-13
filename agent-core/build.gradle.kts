plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

group = "com.deepseekbuddy"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

/**
 * 评测 runner。
 *
 * 用 test 源码集而不是 main：runner 依赖测试替身（内存 store、回放客户端），
 * 那些东西不该被打进发布出去的库里。
 *
 *   ./gradlew runEval                                   # 空跑，验证流水线
 *   ./gradlew runEval --args="--replay ../evals/replay/xxx.json --baseline ../evals/reports/latest.json"
 *   DEEPSEEK_API_KEY=sk-xxx ./gradlew runEval --args="--live --record ../evals/replay/baseline.json"
 */
tasks.register<JavaExec>("runEval") {
    group = "verification"
    description = "跑泡泡 Agent 评测集，产出指标、失败归因表与回归对比"
    mainClass.set("com.deepseekbuddy.agent.eval.EvalRunnerKt")
    classpath = sourceSets["test"].runtimeClasspath
    // 相对路径以 agent-core/ 为基准，所以 ../evals/cases 正好是 paopao/evals/cases
    workingDir = projectDir
}
