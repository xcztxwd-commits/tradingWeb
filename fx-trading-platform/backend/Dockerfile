FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY src ./src

RUN --mount=type=cache,id=fx-trading-platform-maven-repository,target=/root/.m2/repository,sharing=locked \
    set -eu; \
    for attempt in 1 2 3; do \
      if mvn -B -U \
        -Daether.connector.http.retryHandler.name=standard \
        -Daether.connector.http.retryHandler.count=5 \
        -Daether.connector.http.retryHandler.requestSentEnabled=false \
        -DskipTests clean package; then \
        exit 0; \
      fi; \
      if [ "$attempt" -eq 3 ]; then \
        exit 1; \
      fi; \
      sleep "$((attempt * 2))"; \
    done

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S app && adduser -S -D -H -G app app

WORKDIR /app

COPY --from=build --chown=app:app /workspace/target/fx-platform-backend-0.1.0.jar /app/app.jar

USER app

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
