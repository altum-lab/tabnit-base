plugins {
    `java-library`
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
    api(project(":common"))
    api(project(":core"))
    implementation("org.springframework:spring-jdbc")
    // Pack 의 정의 파일(Turtle)을 읽는다. 이 모듈 안에서만 쓴다 (D2 AD-15, AD-87).
    // Spring Boot 가 버전을 관리하지 않아 여기 한 곳에 직접 적는다 (D2 AD-126).
    implementation("org.apache.jena:jena-arq:6.2.0")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    // 시험은 실제 PostgreSQL 에 api 모듈의 스키마 변경 파일을 적용해 돌린다 (D2 AD-10, AD-46).
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.springframework:spring-jdbc")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
