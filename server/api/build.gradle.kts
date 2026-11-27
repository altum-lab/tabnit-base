plugins {
	java
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.cyclonedx.bom") version "3.4.0"
}

group = "com.altumlab.tabnit"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation(project(":common"))
	implementation(project(":core"))
	implementation(project(":pack"))
	implementation(project(":link"))
	implementation(project(":rules"))
	// 검증 질문을 읽고 견주는 부분. 화면에서 돌릴 때 쓴다 (카드 E-23, D2 AD-422). 제품의 모듈에 기대지 않는 모듈이다.
	implementation(project(":verify-core"))
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 소스에 한글이 있다. 빌드하는 곳의 기본 글자 인코딩에 결과가 달라지지 않도록 UTF-8 로 고정한다 (D2 AD-172).
tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// 서버에 든 구성요소의 명세 (E-11, D2 AD-364). 표준 형식(CycloneDX)으로 낸다. bash tools/components.sh 가 부른다.
// 실행할 때 쓰이는 것만 담는다 (시험과 빌드에만 쓰이는 것은 배포물에 들어가지 않는다).
// 돌릴 때마다 달라지는 일련번호는 넣지 않는다.
tasks.cyclonedxDirectBom {
	includeConfigs.set(listOf("runtimeClasspath"))
	includeBomSerialNumber.set(false)
	includeLicenseText.set(false)
	includeBuildSystem.set(false)
	jsonOutput.set(layout.buildDirectory.file("components/server-bom.json"))
}
