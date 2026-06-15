package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminPriceAdjustmentRequest;
import com.fxplatform.admin.dto.request.AdminSymbolRequest;
import com.fxplatform.admin.dto.request.AdminSymbolStatusRequest;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.entity.PriceAdjustmentEntity;
import com.fxplatform.market.entity.SymbolAdminEventEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.PriceAdjustmentRepository;
import com.fxplatform.market.repository.SymbolAdminEventRepository;
import com.fxplatform.market.repository.SymbolCategoryRepository;
import com.fxplatform.market.repository.SymbolRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminMarketCommandServiceTest {

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private SymbolAdminEventRepository symbolAdminEventRepository;

  @Mock
  private SymbolCategoryRepository symbolCategoryRepository;

  @Mock
  private PriceAdjustmentRepository priceAdjustmentRepository;

  @Mock
  private AuditLogService auditLogService;

  @Test
  void updatesSymbolStatusWithEventAndAudit() {
    UUID actorUserId = UUID.randomUUID();
    SymbolEntity symbol = symbol(UUID.randomUUID());
    when(symbolRepository.findById(symbol.getId())).thenReturn(Optional.of(symbol));

    AdminMarketCommandService service = new AdminMarketCommandService(
        symbolRepository,
        symbolAdminEventRepository,
        symbolCategoryRepository,
        priceAdjustmentRepository,
        auditLogService);

    var response = service.updateStatus(actorUserId, symbol.getId(), new AdminSymbolStatusRequest(false, "maintenance"));

    assertThat(response.enabled()).isFalse();
    verify(symbolRepository).save(symbol);
    ArgumentCaptor<SymbolAdminEventEntity> eventCaptor = ArgumentCaptor.forClass(SymbolAdminEventEntity.class);
    verify(symbolAdminEventRepository).save(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getEventType()).isEqualTo("SYMBOL_STATUS_UPDATE");
    assertThat(eventCaptor.getValue().getBeforeValue()).isEqualTo("true");
    assertThat(eventCaptor.getValue().getAfterValue()).isEqualTo("false");
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_SYMBOL_STATUS_UPDATE"), eq("SYMBOL"), eq(symbol.getId().toString()), contains("maintenance"));
  }

  @Test
  void createsExplicitPriceAdjustmentAndRejectsHiddenControlMode() {
    UUID actorUserId = UUID.randomUUID();
    SymbolEntity symbol = symbol(UUID.randomUUID());
    when(symbolRepository.findById(symbol.getId())).thenReturn(Optional.of(symbol));
    when(priceAdjustmentRepository.save(any(PriceAdjustmentEntity.class))).thenAnswer(invocation -> {
      PriceAdjustmentEntity adjustment = invocation.getArgument(0);
      adjustment.setId(UUID.randomUUID());
      return adjustment;
    });
    AdminMarketCommandService service = new AdminMarketCommandService(
        symbolRepository,
        symbolAdminEventRepository,
        symbolCategoryRepository,
        priceAdjustmentRepository,
        auditLogService);

    var request = new AdminPriceAdjustmentRequest(
        "DEMO_SCENARIO",
        "SET_MID_PRICE",
        new BigDecimal("1.09000"),
        Instant.parse("2026-06-08T00:00:00Z"),
        Instant.parse("2026-06-08T00:05:00Z"),
        "demo candle repair");

    var response = service.createPriceAdjustment(actorUserId, symbol.getId(), request);

    assertThat(response.symbol()).isEqualTo("EURUSD");
    assertThat(response.status()).isEqualTo("SCHEDULED");
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_PRICE_ADJUSTMENT_CREATE"), eq("SYMBOL"), eq(symbol.getId().toString()), contains("demo candle repair"));

    assertThatThrownBy(() -> service.createPriceAdjustment(
        actorUserId,
        symbol.getId(),
        new AdminPriceAdjustmentRequest("HIDDEN_CONTROL", "SET_MID_PRICE", BigDecimal.ONE, null, null, "bad")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Hidden price control is not allowed");
  }

  @Test
  void createsUpdatesAndDisablesSymbolWithAuditEvents() {
    UUID actorUserId = UUID.randomUUID();
    UUID symbolId = UUID.randomUUID();
    when(symbolRepository.findBySymbol("GBPUSD")).thenReturn(Optional.empty());
    when(symbolRepository.save(any(SymbolEntity.class))).thenAnswer(invocation -> {
      SymbolEntity saved = invocation.getArgument(0);
      if (saved.getId() == null) {
        saved.setId(symbolId);
      }
      return saved;
    });
    when(symbolRepository.findById(symbolId)).thenReturn(Optional.of(symbol(symbolId)));
    AdminMarketCommandService service = new AdminMarketCommandService(
        symbolRepository,
        symbolAdminEventRepository,
        symbolCategoryRepository,
        priceAdjustmentRepository,
        auditLogService);

    var request = new AdminSymbolRequest(
        "GBPUSD",
        "British Pound / US Dollar",
        "massive",
        "GBPUSD",
        "FOREX",
        "GBP",
        "USD",
        new BigDecimal("0.0001"),
        new BigDecimal("0.00001"),
        new BigDecimal("100000"),
        new BigDecimal("0.01"),
        new BigDecimal("100"),
        100,
        new BigDecimal("0.00002"),
        true,
        null,
        true,
        true,
        true,
        true,
        true,
        false,
        "majors",
        10);

    var created = service.createSymbol(actorUserId, request);
    var updated = service.updateSymbol(actorUserId, symbolId, request);
    service.deleteSymbol(actorUserId, symbolId, "后台下架产品");

    assertThat(created.id()).isEqualTo(symbolId);
    assertThat(updated.symbol()).isEqualTo("GBPUSD");
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_SYMBOL_CREATE"), eq("SYMBOL"), eq(symbolId.toString()), contains("GBPUSD"));
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_SYMBOL_UPDATE"), eq("SYMBOL"), eq(symbolId.toString()), contains("GBPUSD"));
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_SYMBOL_DELETE"), eq("SYMBOL"), eq(symbolId.toString()), contains("后台下架产品"));
  }

  private SymbolEntity symbol(UUID id) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(id);
    symbol.setSymbol("EURUSD");
    symbol.setDisplayName("Euro / US Dollar");
    symbol.setProvider("massive");
    symbol.setProviderSymbol("EURUSD");
    symbol.setAssetClass("FOREX");
    symbol.setBaseCurrency("EUR");
    symbol.setQuoteCurrency("USD");
    symbol.setPipSize(new BigDecimal("0.0001"));
    symbol.setTickSize(new BigDecimal("0.00001"));
    symbol.setLotSize(new BigDecimal("100000"));
    symbol.setMinLot(new BigDecimal("0.01"));
    symbol.setMaxLot(new BigDecimal("100"));
    symbol.setLeverage(100);
    symbol.setSpreadMarkup(new BigDecimal("0.00002"));
    symbol.setEnabled(true);
    return symbol;
  }
}
