FROM gradle:8.10-jdk21 AS build

WORKDIR /app

COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src

RUN gradle clean bootJar --no-daemon

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

COPY --from=build --chown=app:app /app/build/libs/link2action-bot-service.jar app.jar

EXPOSE 8080

USER app

ENTRYPOINT ["java", "-jar", "app.jar"]
