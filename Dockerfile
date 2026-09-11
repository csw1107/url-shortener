# 1단계 — 빌드용. 소스를 컴파일해서 실행 파일(jar)을 만든다
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew bootJar --no-daemon

# 2단계 — 실행용. 빌드 결과물만 가벼운 이미지에 옮겨 담는다
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]