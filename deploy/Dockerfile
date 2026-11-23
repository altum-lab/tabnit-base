# 앱의 컨테이너 이미지: 서버와 화면을 함께 담는다 (D2 AD-27, AD-277).
#
# 만드는 곳은 저장소의 맨 위다:  docker build -f deploy/Dockerfile .
# 보통은 직접 부르지 않고 bash tools/up.sh 를 쓴다.
# 시험은 여기서 돌리지 않는다 (서버 시험은 Docker 가 있어야 한다). 시험은 tools/build.sh 와 CI 가 돌린다.

# 빌드 단계 둘은 만드는 PC 의 CPU 로 돈다 ($BUILDPLATFORM): 만든 것(화면의 파일, 서버의 jar)은 CPU 를 가리지 않는다.
# 다른 CPU 용 이미지를 만들 때(tools/package.sh) 빌드를 흉내 내어 돌리지 않아도 된다. 실행하는 이미지만 대상 CPU 의 것이다.

# 1) 화면을 빌드한다. Node 의 버전은 CI 와 같다.
FROM --platform=$BUILDPLATFORM node:24 AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# 2) 서버를 빌드한다. Java 의 버전은 CI 와 같다.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS server
WORKDIR /src/server
COPY server/ ./
RUN ./gradlew :api:bootJar --no-daemon -x test \
    && cp "$(ls api/build/libs/*.jar | grep -v -- '-plain\.jar$')" /app.jar

# 3) 실행하는 이미지. 빌드 도구는 담지 않는다.
FROM eclipse-temurin:21-jre
# 관리자 권한 없이 돈다.
RUN useradd --system --uid 10001 --no-create-home tabnit
WORKDIR /app
# HTTPS 의 인증서를 두는 곳 (설치 구성이 볼륨을 여기에 붙인다). 앱의 사용자가 쓸 수 있어야 설치 스크립트가
# 자체 서명 인증서를 여기에 만들 수 있다 (D2 AD-363).
RUN mkdir -p /app/tls && chown 10001 /app/tls && chmod 700 /app/tls
COPY --from=server /app.jar /app/app.jar
COPY --from=web /src/web/dist /app/web
# 화면의 파일을 둔 곳과 Pack 을 둔 곳 (설치 설정 tabnit.web-dir, tabnit.packs-dir).
# Pack 폴더는 띄울 때 바깥의 폴더를 /app/packs 에 읽기 전용으로 붙인다.
ENV TABNIT_WEB_DIR=/app/web \
    TABNIT_PACKS_DIR=/app/packs
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
