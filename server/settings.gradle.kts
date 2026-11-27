pluginManagement {
    plugins {
        id("org.springframework.boot") version "4.1.2"
        id("io.spring.dependency-management") version "1.1.7"
    }
}

rootProject.name = "tabnit-server"

include("common", "core", "pack", "link", "rules", "api")

// 검증 질문을 읽고 견주는 부분. 제품의 모듈에 기대지 않는다. api 와 명령줄 실행기(verify)가 함께 쓴다 (D2 AD-422).
include("verify-core")

// 검증 질문 실행기는 저장소 맨 위의 verify/ 폴더에 있다 (D2 AD-16, AD-280). 빌드는 서버의 것을 함께 쓴다.
// 서버만 떼어 빌드하는 곳(컨테이너 이미지, deploy/Dockerfile)에는 그 폴더가 없다. 있을 때만 잇는다.
if (file("../verify/build.gradle.kts").isFile) {
    include("verify")
    project(":verify").projectDir = file("../verify")
}
