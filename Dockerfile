# 1단계(builder): 소스를 받아 실행 가능한 jar를 만든다.
FROM gradle:8.10-jdk21 AS build
WORKDIR /app
COPY settings.gradle build.gradle ./
COPY src ./src
# 테스트는 CI에서 이미 실행하므로 이미지 빌드에서는 제외한다(Docker 안에 DB가 없어 통합 테스트가 불가).
RUN gradle bootJar --no-daemon -x test

# 2단계(runtime): 실행에 필요한 것만 담는다. Gradle과 소스는 포함되지 않는다.
FROM eclipse-temurin:21-jre
WORKDIR /app
# root로 돌리지 않기 위한 전용 계정
RUN useradd --system --uid 1001 appuser
COPY --from=build /app/build/libs/*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
