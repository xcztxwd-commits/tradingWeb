package com.fxplatform.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ValidationComposeContractTest {

  private static final Path COMPOSE_FILE = Path.of("../infra/docker-compose.validation.yml");
  private static final Path ENV_EXAMPLE = Path.of("../infra/.env.validation.example");

  @Test
  void definesTheExactProjectServicesAndInternalNetwork() throws Exception {
    Map<String, Object> compose = loadCompose();
    Map<String, Object> services = map(compose.get("services"));
    Map<String, Object> networks = map(compose.get("networks"));

    assertThat(compose.get("name")).isEqualTo("fx-trading-validation");
    assertThat(services.keySet()).containsExactlyInAnyOrder(
        "validation-backend", "validation-postgres", "validation-redis");
    assertThat(networks.keySet()).containsExactly("validation-internal");
    assertThat(map(networks.get("validation-internal")))
        .containsEntry("internal", true);

    services.values().forEach(service -> assertThat(list(map(service).get("networks")))
        .containsExactly("validation-internal"));
  }

  @Test
  void leavesAllContainersUnpublishedForTheHostLoopbackRelay() throws Exception {
    Map<String, Object> services = services();
    Map<String, Object> backend = map(services.get("validation-backend"));
    Map<String, Object> postgres = map(services.get("validation-postgres"));
    Map<String, Object> redis = map(services.get("validation-redis"));

    assertThat(backend).doesNotContainKey("ports");
    assertThat(postgres).doesNotContainKey("ports");
    assertThat(redis).doesNotContainKey("ports");

    assertThat(Files.readString(Path.of("../scripts/validation-loopback-relay.mjs")))
        .contains("export const RELAY_HOST = '127.0.0.1'")
        .contains("export const RELAY_PORT = 18087")
        .contains("'/bin/busybox'")
        .contains("'nc'")
        .doesNotContain("0.0.0.0");
  }

  @Test
  void usesIndependentVolumesAndHealthyDependencies() throws Exception {
    Map<String, Object> compose = loadCompose();
    Map<String, Object> services = map(compose.get("services"));
    Map<String, Object> volumes = map(compose.get("volumes"));
    Map<String, Object> backend = map(services.get("validation-backend"));

    assertThat(volumes.keySet()).containsExactlyInAnyOrder(
        "validation-postgres-data", "validation-redis-data");
    assertThat(list(map(services.get("validation-postgres")).get("volumes")))
        .containsExactly("validation-postgres-data:/var/lib/postgresql/data");
    assertThat(list(map(services.get("validation-redis")).get("volumes")))
        .containsExactly("validation-redis-data:/data");

    assertThat(map(backend.get("depends_on")))
        .containsKeys("validation-postgres", "validation-redis");
    assertThat(map(map(backend.get("depends_on")).get("validation-postgres")))
        .containsEntry("condition", "service_healthy");
    assertThat(map(map(backend.get("depends_on")).get("validation-redis")))
        .containsEntry("condition", "service_healthy");
    services.values().forEach(service -> assertThat(map(service)).containsKey("healthcheck"));
  }

  @Test
  void pinsValidationProfileDemoModeAndRequiredSecretInputs() throws Exception {
    Map<String, Object> environment = map(map(services().get("validation-backend")).get("environment"));

    assertThat(environment)
        .containsEntry("SPRING_PROFILES_ACTIVE", "validation")
        .containsEntry("EXECUTION_MODE", "demo");
    assertRequiredInterpolation(environment, "VALIDATION_DATABASE_PASSWORD");
    assertRequiredInterpolation(environment, "VALIDATION_REDIS_PASSWORD");
    assertRequiredInterpolation(environment, "VALIDATION_JWT_SECRET");
    assertRequiredInterpolation(environment, "VALIDATION_CONFIG_ENCRYPTION_KEY");
    assertRequiredInterpolation(environment, "VALIDATION_INTERNAL_SECRET");

    String source = Files.readString(COMPOSE_FILE);
    assertThat(source)
        .doesNotContain("jdbc:postgresql://localhost")
        .doesNotContain("/fx_platform")
        .doesNotContain("REDIS_HOST: localhost");
    assertThat(Files.readString(ENV_EXAMPLE)).contains("Local-only example values");
  }

  @Test
  void dockerfileUsesJava21MultiStageBuildAndARealHealthClient() throws Exception {
    String dockerfile = Files.readString(Path.of("Dockerfile"));
    String dockerignore = Files.readString(Path.of(".dockerignore"));

    assertThat(dockerfile)
        .contains("FROM maven:3.9.9-eclipse-temurin-21 AS build")
        .contains("FROM eclipse-temurin:21-jre-alpine AS runtime")
        .contains("RUN addgroup -S app && adduser -S -D -H -G app app")
        .contains("COPY --from=build")
        .contains("USER app")
        .contains("ENTRYPOINT [\"java\", \"-jar\", \"/app/app.jar\"]")
        .doesNotContain("apt-get", "apk add");
    assertThat(dockerignore).contains("target/", "*.dump", "*.dumpstream");

    Map<String, Object> healthcheck = map(map(services().get("validation-backend"))
        .get("healthcheck"));
    assertThat(list(healthcheck.get("test")))
        .containsExactly("CMD", "wget", "-q", "-T", "3", "-O", "/dev/null",
            "http://127.0.0.1:8080/actuator/health");
  }

  @Test
  void dockerfileCachesMavenDownloadsAndBoundsTransientHttpRetries() throws Exception {
    String dockerfile = Files.readString(Path.of("Dockerfile"));
    int retryInstructionStart = dockerfile.indexOf("RUN --mount=type=cache");
    int runtimeStageStart = dockerfile.indexOf(
        "FROM eclipse-temurin:21-jre-alpine AS runtime");

    assertThat(retryInstructionStart).isGreaterThanOrEqualTo(0);
    assertThat(runtimeStageStart).isGreaterThan(retryInstructionStart);
    String retryInstruction = dockerfile
        .substring(retryInstructionStart, runtimeStageStart)
        .replace("\\\r\n", " ")
        .replace("\\\n", " ")
        .replaceAll("\\s+", " ")
        .trim();

    assertThat(dockerfile).doesNotStartWith("# syntax=");
    assertThat(retryInstruction).isEqualTo(
        "RUN --mount=type=cache,id=fx-trading-platform-maven-repository,"
            + "target=/root/.m2/repository,sharing=locked "
            + "set -eu; "
            + "for attempt in 1 2 3; do "
            + "if mvn -B -U "
            + "-Daether.connector.http.retryHandler.name=standard "
            + "-Daether.connector.http.retryHandler.count=5 "
            + "-Daether.connector.http.retryHandler.requestSentEnabled=false "
            + "-DskipTests clean package; then "
            + "exit 0; "
            + "fi; "
            + "if [ \"$attempt\" -eq 3 ]; then "
            + "exit 1; "
            + "fi; "
            + "sleep \"$((attempt * 2))\"; "
            + "done");
    assertThat(retryInstruction).doesNotContain("while true");
  }

  private static void assertRequiredInterpolation(Map<String, Object> environment, String key) {
    assertThat(environment.get(key).toString()).startsWith("${" + key + ":?");
  }

  private static Map<String, Object> services() throws Exception {
    return map(loadCompose().get("services"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadCompose() throws Exception {
    assertThat(Files.isRegularFile(COMPOSE_FILE)).as("validation Compose file").isTrue();
    Object loaded = new Yaml().load(Files.readString(COMPOSE_FILE));
    assertThat(loaded).isInstanceOf(Map.class);
    return (Map<String, Object>) loaded;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    assertThat(value).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    assertThat(value).isInstanceOf(List.class);
    return (List<Object>) value;
  }
}
