plugins {
    java
    id("org.springframework.boot") apply false
    id("io.spring.dependency-management")
}

group = "com.altumlab.tabnit"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    // 실행기는 제품을 띄워 HTTP 로만 묻는다 (D2 AD-111, AD-279). api 는 띄우려고 기댄다. 그 내부 클래스는 쓰지 않는다.
    implementation(project(":api"))
    // 띄운 제품에 첫 관리자를 만들 때 common 의 공개 API 를 쓴다.
    implementation(project(":common"))
    // 영향 범위 계산만 따로 잴 때 core 의 공개 API 를 부른다 (A-07)
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot")
    // 질문과 정답은 YAML 이다 (D2 AD-107). Spring Boot 에 들어 있는 것을 쓴다.
    implementation("org.yaml:snakeyaml")
    // 질문을 돌릴 때마다 빈 PostgreSQL 을 띄운다 (D2 AD-110).
    implementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// 검증 질문을 돌린다. bash verify/run.sh 가 부른다. Docker 가 켜져 있어야 한다.
tasks.register<JavaExec>("verifyQuestions") {
    group = "verification"
    description = "모든 Pack 의 검증 질문을 돌려 질문별 통과와 실패를 낸다"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.altumlab.tabnit.verify.VerifyMain")
    // 저장소 맨 위의 packs/ 와, 보고를 둘 곳.
    args(rootProject.file("../packs").absolutePath, layout.buildDirectory.file("verify-report.txt").get().asFile.absolutePath)
}

// 큰 시험 자료를 만들고 제품에 넣어 수를 견준다 (보드 T-07). bash verify/large.sh 가 부른다.
// 만든 것은 빌드 폴더 아래에 둔다: 저장소에 올리지 않는다.
tasks.register<JavaExec>("largePack") {
    group = "verification"
    description = "개체 수와 관계 수를 정해 지어낸 Pack 을 만들고, 제품에 넣은 뒤의 수가 셈한 수와 같은지 본다"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.altumlab.tabnit.verify.LargePackMain")
    args(layout.buildDirectory.dir("large").get().asFile.absolutePath,
            (findProperty("entities") ?: "1000").toString(), (findProperty("relations") ?: "3000").toString(),
            (findProperty("layers") ?: "3").toString(), (findProperty("changes") ?: "10").toString(),
            (findProperty("mode") ?: "run").toString())
}
