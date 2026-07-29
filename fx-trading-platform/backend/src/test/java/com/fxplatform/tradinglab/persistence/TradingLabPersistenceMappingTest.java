package com.fxplatform.tradinglab.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fxplatform.common.mybatis.JsonbStringTypeHandler;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TradingLabPersistenceMappingTest {

  private static final String ENTITY_PACKAGE = "com.fxplatform.tradinglab.entity.";
  private static final String REPOSITORY_PACKAGE = "com.fxplatform.tradinglab.repository.";

  private static final List<Mapping> MAPPINGS = List.of(
      new Mapping(
          "TradingLabScenarioEntity",
          "TradingLabScenarioRepository",
          "trading_lab.scenarios",
          List.of("scenarioJson", "configSnapshotJson"),
          null),
      new Mapping(
          "TradingLabReportEntity",
          "TradingLabReportRepository",
          "trading_lab.reports",
          List.of("metadataJson"),
          null),
      new Mapping(
          "TradingLabRunEntity",
          "TradingLabRunRepository",
          "trading_lab.runs",
          List.of("scenarioSnapshotJson", "configSnapshotJson"),
          null),
      new Mapping(
          "TradingLabRunTransitionEntity",
          "TradingLabRunTransitionRepository",
          "trading_lab.run_transitions",
          List.of("detailsJson"),
          null),
      new Mapping(
          "TradingLabRunEventEntity",
          "TradingLabRunEventRepository",
          "trading_lab.run_events",
          List.of("payloadJson"),
          null),
      new Mapping(
          "TradingLabReportChunkEntity",
          "TradingLabReportChunkRepository",
          "trading_lab.report_chunks",
          List.of(),
          "payload"),
      new Mapping(
          "TradingLabAuditEventEntity",
          "TradingLabAuditEventRepository",
          "trading_lab.audit_events",
          List.of("detailsJson"),
          null));

  @Test
  void allSevenTablesHaveAnEntityAndMyBatisPlusRepository() throws Exception {
    for (Mapping mapping : MAPPINGS) {
      Class<?> entity = Class.forName(ENTITY_PACKAGE + mapping.entity());
      Class<?> repository = Class.forName(REPOSITORY_PACKAGE + mapping.repository());

      TableName tableName = entity.getAnnotation(TableName.class);
      assertThat(tableName).as("@TableName on %s", mapping.entity()).isNotNull();
      assertThat(tableName.value()).isEqualTo(mapping.table());
      assertThat(repository).isInterface();
      assertThat(BaseMapper.class).isAssignableFrom(repository);
      assertThat(repositoryMapsEntity(repository, entity))
          .as("%s generic entity", mapping.repository())
          .isTrue();
    }
  }

  @Test
  void everyJsonbFieldUsesAutoResultMapAndTheJsonbStringTypeHandler() throws Exception {
    for (Mapping mapping : MAPPINGS) {
      if (mapping.jsonFields().isEmpty()) {
        continue;
      }
      Class<?> entity = Class.forName(ENTITY_PACKAGE + mapping.entity());
      TableName tableName = entity.getAnnotation(TableName.class);

      assertThat(tableName).as("@TableName on %s", mapping.entity()).isNotNull();
      assertThat(tableName.autoResultMap())
          .as("%s must enable autoResultMap for JSONB", mapping.entity())
          .isTrue();

      for (String fieldName : mapping.jsonFields()) {
        Field field = entity.getDeclaredField(fieldName);
        assertThat(field.getType()).as("%s.%s type", mapping.entity(), fieldName)
            .isEqualTo(String.class);
        TableField tableField = field.getAnnotation(TableField.class);
        assertThat(tableField).as("@TableField on %s.%s", mapping.entity(), fieldName)
            .isNotNull();
        assertThat(tableField.typeHandler())
            .as("%s.%s JSONB handler", mapping.entity(), fieldName)
            .isEqualTo(JsonbStringTypeHandler.class);
      }
    }
  }

  @Test
  void reportChunkPayloadIsMappedAsRawBytes() throws Exception {
    Mapping chunk = MAPPINGS.stream()
        .filter(mapping -> mapping.payloadField() != null)
        .findFirst()
        .orElseThrow();
    Class<?> entity = Class.forName(ENTITY_PACKAGE + chunk.entity());

    assertThat(entity.getDeclaredField(chunk.payloadField()).getType()).isEqualTo(byte[].class);
  }

  @Test
  void runMapsTheUpdatedAtColumnRequiredByStateCompareAndSet() throws Exception {
    Class<?> run = Class.forName(ENTITY_PACKAGE + "TradingLabRunEntity");

    assertThat(run.getDeclaredField("updatedAt").getType()).isEqualTo(Instant.class);
  }

  private static boolean repositoryMapsEntity(Class<?> repository, Class<?> entity) {
    for (Type genericInterface : repository.getGenericInterfaces()) {
      if (genericInterface instanceof ParameterizedType parameterizedType
          && parameterizedType.getActualTypeArguments().length == 1
          && parameterizedType.getActualTypeArguments()[0].equals(entity)) {
        return true;
      }
      if (genericInterface instanceof Class<?> parent
          && repositoryMapsEntity(parent, entity)) {
        return true;
      }
    }
    return false;
  }

  private record Mapping(
      String entity,
      String repository,
      String table,
      List<String> jsonFields,
      String payloadField) {
  }
}
