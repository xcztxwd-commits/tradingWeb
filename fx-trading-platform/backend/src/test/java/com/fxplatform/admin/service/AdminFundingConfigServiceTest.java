package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminFundingConfigRequest;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.entity.SymbolAdminEventEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.PriceAdjustmentRepository;
import com.fxplatform.market.repository.SymbolAdminEventRepository;
import com.fxplatform.market.repository.SymbolCategoryRepository;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.trading.entity.FundingRateEntity;
import com.fxplatform.trading.repository.FundingRateRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminFundingConfigServiceTest {

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private SymbolAdminEventRepository symbolAdminEventRepository;

  @Mock
  private SymbolCategoryRepository symbolCategoryRepository;

  @Mock
  private PriceAdjustmentRepository priceAdjustmentRepository;

  @Mock
  private FundingRateRepository fundingRateRepository;

  @Mock
  private AuditLogService auditLogService;

  @Test
  void getReturnsConfigurationAndMapsLatestPhysicalProviderToActualSource() {
    SymbolEntity symbol = perpetualSymbol(UUID.randomUUID());
    FundingRateEntity latest = new FundingRateEntity();
    latest.setSymbol(symbol.getSymbol());
    latest.setProviderCode("okx-swap");
    latest.setSourceMode("PUBLIC_EXTERNAL");
    latest.setAsOf(Instant.parse("2026-07-12T07:59:59Z"));
    latest.setNextFundingTime(Instant.parse("2026-07-12T08:00:00Z"));
    when(symbolRepository.findById(symbol.getId())).thenReturn(Optional.of(symbol));
    when(fundingRateRepository.findLatestBySymbol(symbol.getSymbol())).thenReturn(Optional.of(latest));
    AdminMarketQueryService service = new AdminMarketQueryService(
        symbolRepository,
        symbolCategoryRepository,
        priceAdjustmentRepository,
        fundingRateRepository);

    var response = service.fundingConfig(symbol.getId());

    assertThat(response.symbolId()).isEqualTo(symbol.getId());
    assertThat(response.symbol()).isEqualTo("BTCUSDT-PERP");
    assertThat(response.fundingSourcePriority()).containsExactly("BINANCE", "OKX", "FIXED");
    assertThat(response.fixedFundingRate()).isEqualByComparingTo("0.0001");
    assertThat(response.fixedFundingIntervalMinutes()).isEqualTo(480);
    assertThat(response.fundingStaleSeconds()).isEqualTo(900);
    assertThat(response.actualSource()).isEqualTo("OKX");
    assertThat(response.sourceMode()).isEqualTo("PUBLIC_EXTERNAL");
    assertThat(response.asOf()).isEqualTo(Instant.parse("2026-07-12T07:59:59Z"));
    assertThat(response.nextFundingTime()).isEqualTo(Instant.parse("2026-07-12T08:00:00Z"));
  }

  @Test
  void updatePersistsCompleteReorderedPriorityAndWritesEventAndAudit() {
    UUID actorUserId = UUID.randomUUID();
    SymbolEntity symbol = perpetualSymbol(UUID.randomUUID());
    when(symbolRepository.findById(symbol.getId())).thenReturn(Optional.of(symbol));
    when(symbolRepository.save(symbol)).thenReturn(symbol);
    AdminMarketCommandService service = commandService();
    AdminFundingConfigRequest request = request(
        List.of(" okx ", "binance", "fixed"),
        "-0.0002",
        240,
        120);

    var response = service.updateFundingConfig(actorUserId, symbol.getId(), request);

    assertThat(symbol.getFundingSourcePriority()).containsExactly("OKX", "BINANCE", "FIXED");
    assertThat(symbol.getFixedFundingRate()).isEqualByComparingTo("-0.0002");
    assertThat(symbol.getFixedFundingIntervalMinutes()).isEqualTo(240);
    assertThat(symbol.getFundingStaleSeconds()).isEqualTo(120);
    assertThat(response.fundingSourcePriority()).containsExactly("OKX", "BINANCE", "FIXED");
    assertThat(response.fixedFundingRate()).isEqualByComparingTo("-0.0002");
    assertThat(response.fixedFundingIntervalMinutes()).isEqualTo(240);
    assertThat(response.fundingStaleSeconds()).isEqualTo(120);
    verify(symbolRepository).save(symbol);
    ArgumentCaptor<SymbolAdminEventEntity> eventCaptor = ArgumentCaptor.forClass(SymbolAdminEventEntity.class);
    verify(symbolAdminEventRepository).save(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getEventType()).isEqualTo("FUNDING_CONFIG_UPDATE");
    assertThat(eventCaptor.getValue().getBeforeValue())
        .contains("BINANCE", "OKX", "FIXED", "0.0001", "480", "900");
    assertThat(eventCaptor.getValue().getAfterValue())
        .contains("OKX", "BINANCE", "FIXED", "-0.0002", "240", "120");
    assertThat(eventCaptor.getValue().getReason()).isEqualTo("funding priority change");
    verify(auditLogService).record(
        eq(actorUserId),
        eq("ADMIN_FUNDING_CONFIG_UPDATE"),
        eq("SYMBOL"),
        eq(symbol.getId().toString()),
        contains("funding priority change"));
  }

  @Test
  void updateRejectsAnyPriorityThatIsNotTheCompleteUniquePermutationWithFixedLast() {
    SymbolEntity symbol = perpetualSymbol(UUID.randomUUID());
    lenient().when(symbolRepository.findById(symbol.getId())).thenReturn(Optional.of(symbol));
    AdminMarketCommandService service = commandService();

    assertInvalidPriority(service, symbol, List.of("BINANCE", "FIXED"));
    assertInvalidPriority(service, symbol, List.of("BINANCE", "binance", "FIXED"));
    assertInvalidPriority(service, symbol, List.of("BINANCE", "KRAKEN", "FIXED"));
    assertInvalidPriority(service, symbol, List.of("FIXED", "BINANCE", "OKX"));

    verify(symbolRepository, never()).save(any(SymbolEntity.class));
    verifyNoInteractions(symbolAdminEventRepository, auditLogService);
  }

  @Test
  void updateRejectsFundingConfigurationForNonLinearPerpetualSymbol() {
    SymbolEntity symbol = perpetualSymbol(UUID.randomUUID());
    symbol.setProductType(ProductType.CRYPTO_SPOT);
    when(symbolRepository.findById(symbol.getId())).thenReturn(Optional.of(symbol));
    AdminMarketCommandService service = commandService();

    assertThatThrownBy(() -> service.updateFundingConfig(
            UUID.randomUUID(),
            symbol.getId(),
            request(List.of("BINANCE", "OKX", "FIXED"), "0.0001", 480, 900)))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("FUNDING_CONFIG_PRODUCT_TYPE_UNSUPPORTED");

    verify(symbolRepository, never()).save(any(SymbolEntity.class));
    verifyNoInteractions(symbolAdminEventRepository, auditLogService);
  }

  @Test
  void getRejectsFundingConfigurationForNonLinearPerpetualSymbol() {
    SymbolEntity symbol = perpetualSymbol(UUID.randomUUID());
    symbol.setProductType(ProductType.CRYPTO_SPOT);
    when(symbolRepository.findById(symbol.getId())).thenReturn(Optional.of(symbol));
    AdminMarketQueryService service = new AdminMarketQueryService(
        symbolRepository,
        symbolCategoryRepository,
        priceAdjustmentRepository,
        fundingRateRepository);

    assertThatThrownBy(() -> service.fundingConfig(symbol.getId()))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("FUNDING_CONFIG_PRODUCT_TYPE_UNSUPPORTED");

    verifyNoInteractions(fundingRateRepository);
  }

  @Test
  void updateRejectsFixedRateThatExceedsV47Scale() {
    SymbolEntity symbol = perpetualSymbol(UUID.randomUUID());
    lenient().when(symbolRepository.findById(symbol.getId())).thenReturn(Optional.of(symbol));
    AdminMarketCommandService service = commandService();

    assertThatThrownBy(() -> service.updateFundingConfig(
            UUID.randomUUID(),
            symbol.getId(),
            request(List.of("BINANCE", "OKX", "FIXED"), "0.00000000001", 480, 900)))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("FUNDING_CONFIG_RATE_INVALID");

    verify(symbolRepository, never()).save(any(SymbolEntity.class));
    verifyNoInteractions(symbolAdminEventRepository, auditLogService);
  }

  @Test
  void updateRejectsNonPositiveFixedInterval() {
    SymbolEntity symbol = perpetualSymbol(UUID.randomUUID());
    lenient().when(symbolRepository.findById(symbol.getId())).thenReturn(Optional.of(symbol));
    AdminMarketCommandService service = commandService();

    assertThatThrownBy(() -> service.updateFundingConfig(
            UUID.randomUUID(),
            symbol.getId(),
            request(List.of("BINANCE", "OKX", "FIXED"), "0.0001", 0, 900)))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("FUNDING_CONFIG_INTERVAL_INVALID");

    verify(symbolRepository, never()).save(any(SymbolEntity.class));
    verifyNoInteractions(symbolAdminEventRepository, auditLogService);
  }

  @Test
  void updateRejectsNonPositiveProviderStalenessThreshold() {
    SymbolEntity symbol = perpetualSymbol(UUID.randomUUID());
    lenient().when(symbolRepository.findById(symbol.getId())).thenReturn(Optional.of(symbol));
    AdminMarketCommandService service = commandService();

    assertThatThrownBy(() -> service.updateFundingConfig(
            UUID.randomUUID(),
            symbol.getId(),
            request(List.of("BINANCE", "OKX", "FIXED"), "0.0001", 480, 0)))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("FUNDING_CONFIG_STALENESS_INVALID");

    verify(symbolRepository, never()).save(any(SymbolEntity.class));
    verifyNoInteractions(symbolAdminEventRepository, auditLogService);
  }

  private void assertInvalidPriority(
      AdminMarketCommandService service,
      SymbolEntity symbol,
      List<String> priority
  ) {
    assertThatThrownBy(() -> service.updateFundingConfig(
            UUID.randomUUID(),
            symbol.getId(),
            request(priority, "0.0001", 480, 900)))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("FUNDING_CONFIG_PRIORITY_INVALID");
  }

  private AdminMarketCommandService commandService() {
    return new AdminMarketCommandService(
        symbolRepository,
        symbolAdminEventRepository,
        symbolCategoryRepository,
        priceAdjustmentRepository,
        auditLogService);
  }

  private AdminFundingConfigRequest request(
      List<String> priority,
      String rate,
      int intervalMinutes,
      int staleSeconds
  ) {
    return new AdminFundingConfigRequest(
        priority,
        new BigDecimal(rate),
        intervalMinutes,
        staleSeconds,
        "funding priority change");
  }

  private SymbolEntity perpetualSymbol(UUID id) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(id);
    symbol.setSymbol("BTCUSDT-PERP");
    symbol.setProductType(ProductType.LINEAR_PERP);
    symbol.setFundingSourcePriority(List.of("BINANCE", "OKX", "FIXED"));
    symbol.setFixedFundingRate(new BigDecimal("0.0001"));
    symbol.setFixedFundingIntervalMinutes(480);
    symbol.setFundingStaleSeconds(900);
    return symbol;
  }
}
