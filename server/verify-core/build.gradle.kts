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

// 검증 질문을 읽고 정답과 견주는 부분 (D2 AD-422). 제품의 어느 모듈에도 기대지 않는다: 묻는 길은 부르는 쪽이 준다.
// 명령줄 실행기(verify)와 api (화면에서 돌리기, 카드 E-23)가 함께 쓴다. 견주는 코드가 하나라 두 결과가 갈리지 않는다.
dependencies {
    // 질문과 정답은 YAML 이다 (D2 AD-107). Spring Boot 가 관리하는 버전을 쓴다.
    implementation("org.yaml:snakeyaml")
}

// 소스에 한글이 있다. 빌드하는 곳의 기본 글자 인코딩에 결과가 달라지지 않도록 UTF-8 로 고정한다 (D2 AD-172).
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
