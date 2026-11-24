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
    api(project(":pack"))
    api(project(":core"))
    implementation("org.springframework:spring-jdbc")
    // 엑셀(xlsx) 파일을 읽는다. link 안에서만 쓴다 (D2 AD-93). Spring Boot 가 버전을 관리하지 않아 여기 한 곳에 적는다
    // (D2 AD-126, AD-373).
    // XlsxTableReader 는 POI 를 따라온 commons-compress 의 ZipFile 도 직접 쓴다 (POI 가 압축 파일을 그 형으로 받는다).
    // POI 를 올릴 때 함께 본다.
    implementation("org.apache.poi:poi-ooxml:6.0.0")
    testImplementation("org.junit.jupiter:junit-jupiter")
    // 시험은 실제 PostgreSQL 에 api 모듈의 스키마 변경 파일을 적용해 돌린다 (D2 AD-10, AD-46).
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.springframework:spring-jdbc")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 소스에 한글이 있다. 빌드하는 곳의 기본 글자 인코딩에 결과가 달라지지 않도록 UTF-8 로 고정한다 (D2 AD-172).
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
}
