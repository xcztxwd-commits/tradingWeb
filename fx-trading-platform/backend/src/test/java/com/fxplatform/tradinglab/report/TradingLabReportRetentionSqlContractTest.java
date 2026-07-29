package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.tradinglab.repository.TradingLabReportRepository;
import java.lang.reflect.Method;
import java.util.Locale;
import org.apache.ibatis.annotations.Delete;
import org.junit.jupiter.api.Test;

class TradingLabReportRetentionSqlContractTest {

  @Test
  void cleanupSqlFreezesTheBoundedPostgresLockingAndEligibilityContract() throws Exception {
    Method cleanup = TradingLabReportRepository.class.getMethod(
        "deleteExpiredTerminalReports", int.class);
    Delete delete = cleanup.getAnnotation(Delete.class);

    assertThat(delete).isNotNull();
    String sql = String.join("\n", delete.value())
        .replaceAll("\\s+", " ")
        .trim()
        .toLowerCase(Locale.ROOT);

    assertThat(sql)
        .contains("delete from trading_lab.reports")
        .contains("report.completed_at is not null")
        .contains("report.status in ('completed', 'failed', 'cancelled')")
        .contains("report.permanent = false")
        .contains("report.retained_until < clock_timestamp()")
        .contains("run.report_id = report.id")
        .contains("run.state not in ('completed', 'failed', 'cancelled')")
        .contains("order by report.retained_until, report.id")
        .contains("limit #{batchsize}")
        .contains("for update skip locked");
  }
}
