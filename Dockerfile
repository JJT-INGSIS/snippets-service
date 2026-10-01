# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY src ./src

RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=secret,id=github_actor,required=true \
    --mount=type=secret,id=github_token,required=true \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor)" \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)" \
    sh ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
