package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.tradinglab.repository.TradingLabReportRepository;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TradingLabReportRetentionServiceTest {

  @Mock private TradingLabReportRepository reportRepository;

  private TradingLabReportRetentionService retentionService;

  @BeforeEach
  void setUp() {
    retentionService = new TradingLabReportRetentionService(reportRepository);
  }

  @Test
  void delegatesAValidBoundedBatchAndReturnsTheDeletedCount() {
    when(reportRepository.deleteExpiredTerminalReports(23)).thenReturn(7);

    int deleted = retentionService.deleteExpiredTerminalReports(23);

    assertThat(deleted).isEqualTo(7);
    verify(reportRepository).deleteExpiredTerminalReports(23);
  }

  @Test
  void rejectsNonPositiveBatchSizesBeforeReachingTheDatabase() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> retentionService.deleteExpiredTerminalReports(0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> retentionService.deleteExpiredTerminalReports(-1));
    verifyNoInteractions(reportRepository);
  }

  @Test
  void rejectsAnOperationallyUnboundedBatchBeforeReachingTheDatabase() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> retentionService.deleteExpiredTerminalReports(
            TradingLabReportProperties.MAX_CLEANUP_BATCH_SIZE + 1));
    verifyNoInteractions(reportRepository);
  }

  @Test
  void exposesTheFrozenPublicFinalServiceContract() throws Exception {
    assertThat(Modifier.isPublic(TradingLabReportRetentionService.class.getModifiers())).isTrue();
    assertThat(Modifier.isFinal(TradingLabReportRetentionService.class.getModifiers())).isTrue();
    assertThat(TradingLabReportRetentionService.class.getMethod(
        "deleteExpiredTerminalReports", int.class).getReturnType()).isEqualTo(int.class);
  }
}
