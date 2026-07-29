package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.tradinglab.repository.TradingLabReportRepository;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class TradingLabReportRepositorySqlContractTest {

  @Test
  void fencedCounterMutationChecksTheLiveClaimInsideTheSameUpdate() {
    String sql = updateSql("addChunkTotalsFenced");

    assertThat(sql)
        .contains("update trading_lab.reports as report")
        .contains("exists (")
        .contains("from trading_lab.runs run")
        .contains("run.report_id = report.id")
        .contains("run.lease_key = 1")
        .contains("run.lease_owner = #{claimowner}")
        .contains("run.lease_until > clock_timestamp()");
  }

  @Test
  void fencedTerminalMutationChecksTheLiveClaimInsideTheSameUpdate() {
    String sql = updateSql("finalizeReportFenced");

    assertThat(sql)
        .contains("update trading_lab.reports as report")
        .contains("exists (")
        .contains("from trading_lab.runs run")
        .contains("run.report_id = report.id")
        .contains("run.lease_key = 1")
        .contains("run.lease_owner = #{claimowner}")
        .contains("run.lease_until > clock_timestamp()");
  }

  @Test
  void fencedMetadataInitializationRequiresEmptyCanonicalRowVersionAndLiveClaim() {
    String sql = updateSql("initializeMetadataFenced");

    assertThat(sql)
        .contains("metadata_json = cast(#{metadatajson} as jsonb)")
        .contains("version = report.version + 1")
        .contains("report.version = #{expectedversion}")
        .contains("report.metadata_json = '{}'::jsonb")
        .contains("exists (")
        .contains("from trading_lab.runs run")
        .contains("run.report_id = report.id")
        .contains("run.lease_key = 1")
        .contains("run.lease_owner = #{claimowner}")
        .contains("run.lease_until > clock_timestamp()");
  }

  @Test
  void quarantineMutationPersistsTheMarkerWithTheCounterReset() {
    assertThat(updateSql("quarantineEvidence"))
        .contains("status = 'writing'")
        .contains("model_version = '[redacted]'")
        .contains("metadata_json = '{}'::jsonb")
        .contains("uncompressed_bytes = 0")
        .contains("compressed_bytes = 0")
        .contains("chunk_count = 0")
        .contains("failure_code = 'trading_lab_report_unsafe_trace'")
        .contains("failure_message = 'trading lab report contains unsafe trace evidence'")
        .contains("version = report.version + 1")
        .contains("report.completed_at is null");
  }

  @Test
  void fencedQuarantineMutationChecksTheLiveClaimInsideTheSameUpdate() {
    String sql = updateSql("quarantineEvidenceFenced");

    assertThat(sql)
        .contains("model_version = '[redacted]'")
        .contains("metadata_json = '{}'::jsonb")
        .contains("failure_code = 'trading_lab_report_unsafe_trace'")
        .contains("failure_message = 'trading lab report contains unsafe trace evidence'")
        .contains("exists (")
        .contains("from trading_lab.runs run")
        .contains("run.report_id = report.id")
        .contains("run.lease_key = 1")
        .contains("run.lease_owner = #{claimowner}")
        .contains("run.lease_until > clock_timestamp()");
  }

  @Test
  void terminalTimestampCteUsesAnUnambiguousAliasAndQualifiedTarget() {
    assertThat(updateSql("finalizeReport"))
        .contains("select clock_timestamp() as closed_at")
        .contains("completed_at = closed.closed_at")
        .contains("where report.id = #{reportid}")
        .doesNotContain("select clock_timestamp() as completed_at");
  }

  @Test
  void permanentMutationIsVersionedAndRestrictedToClosedTerminalReports() {
    assertThat(updateSql("setPermanent"))
        .contains("set permanent = #{permanent}")
        .contains("version = version + 1")
        .contains("version = #{expectedversion}")
        .contains("completed_at is not null")
        .contains("status in ('completed', 'failed', 'cancelled')");
  }

  @Test
  void explicitDeleteIsVersionedAndRestrictedToClosedTerminalReports() {
    assertThat(deleteSql("deleteTerminalByIdAndVersion"))
        .contains("delete from trading_lab.reports")
        .contains("id = #{reportid}")
        .contains("version = #{expectedversion}")
        .contains("completed_at is not null")
        .contains("status in ('completed', 'failed', 'cancelled')");
  }

  private static String updateSql(String methodName) {
    Method method = Arrays.stream(TradingLabReportRepository.class.getMethods())
        .filter(candidate -> candidate.getName().equals(methodName))
        .findFirst()
        .orElseThrow();
    Update update = method.getAnnotation(Update.class);
    assertThat(update).isNotNull();
    return String.join("\n", update.value()).toLowerCase();
  }

  private static String deleteSql(String methodName) {
    Method method = Arrays.stream(TradingLabReportRepository.class.getMethods())
        .filter(candidate -> candidate.getName().equals(methodName))
        .findFirst()
        .orElseThrow();
    Delete delete = method.getAnnotation(Delete.class);
    assertThat(delete).isNotNull();
    return String.join("\n", delete.value()).toLowerCase();
  }
}
