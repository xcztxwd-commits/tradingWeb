package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.admin.dto.request.AdminPriceAdjustmentCancelRequest;
import com.fxplatform.admin.dto.request.AdminSymbolCategoryRequest;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.market.entity.PriceAdjustmentEntity;
import com.fxplatform.market.entity.SymbolCategoryEntity;
import com.fxplatform.market.repository.PriceAdjustmentRepository;
import com.fxplatform.market.repository.SymbolAdminEventRepository;
import com.fxplatform.market.repository.SymbolCategoryRepository;
import com.fxplatform.market.repository.SymbolRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AdminMarketManagementServiceTest {

  private final SymbolRepository symbolRepository = Mockito.mock(SymbolRepository.class);
  private final SymbolAdminEventRepository symbolAdminEventRepository = Mockito.mock(SymbolAdminEventRepository.class);
  private final SymbolCategoryRepository categoryRepository = Mockito.mock(SymbolCategoryRepository.class);
  private final PriceAdjustmentRepository priceAdjustmentRepository = Mockito.mock(PriceAdjustmentRepository.class);
  private final AuditLogService auditLogService = Mockito.mock(AuditLogService.class);

  @Test
  void managesCategoriesAndCancelsPriceAdjustment() {
    UUID actorUserId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    UUID adjustmentId = UUID.randomUUID();

    when(categoryRepository.save(any(SymbolCategoryEntity.class))).thenAnswer(invocation -> {
      SymbolCategoryEntity category = invocation.getArgument(0);
      if (category.getId() == null) {
        category.setId(categoryId);
      }
      return category;
    });
    when(categoryRepository.findById(categoryId)).thenAnswer(invocation -> {
      SymbolCategoryEntity category = new SymbolCategoryEntity();
      category.setId(categoryId);
      category.setName("Forex");
      category.setCode("FOREX");
      category.setSortOrder(1);
      category.setEnabled(true);
      return Optional.of(category);
    });

    PriceAdjustmentEntity adjustment = new PriceAdjustmentEntity();
    adjustment.setId(adjustmentId);
    adjustment.setSymbolId(UUID.randomUUID());
    adjustment.setSymbol("EURUSD");
    adjustment.setMode("PRICE_REPAIR");
    adjustment.setAdjustmentType("SET_MID_PRICE");
    adjustment.setTargetPrice(new BigDecimal("1.09000"));
    adjustment.setStatus("SCHEDULED");
    adjustment.setReason("repair");
    adjustment.setCreatedAt(Instant.parse("2026-06-10T00:00:00Z"));
    when(priceAdjustmentRepository.findById(adjustmentId)).thenReturn(Optional.of(adjustment));
    when(priceAdjustmentRepository.save(any(PriceAdjustmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(priceAdjustmentRepository.findRecent(null, 20)).thenReturn(List.of(adjustment));

    AdminMarketCommandService commandService = new AdminMarketCommandService(
        symbolRepository,
        symbolAdminEventRepository,
        categoryRepository,
        priceAdjustmentRepository,
        auditLogService);
    AdminMarketQueryService queryService = new AdminMarketQueryService(
        symbolRepository,
        categoryRepository,
        priceAdjustmentRepository);

    var created = commandService.createCategory(actorUserId, new AdminSymbolCategoryRequest(
        "Forex",
        "FOREX",
        1,
        true));
    var updated = commandService.updateCategory(actorUserId, categoryId, new AdminSymbolCategoryRequest(
        "Forex Majors",
        "FOREX_MAJOR",
        2,
        true));
    var canceled = commandService.cancelPriceAdjustment(actorUserId, adjustmentId,
        new AdminPriceAdjustmentCancelRequest("bad quote"));
    var adjustments = queryService.priceAdjustments(null, 20);

    assertThat(created.id()).isEqualTo(categoryId);
    assertThat(updated.name()).isEqualTo("Forex Majors");
    assertThat(canceled.status()).isEqualTo("CANCELED");
    assertThat(adjustments).hasSize(1);
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_SYMBOL_CATEGORY_CREATE"), eq("SYMBOL_CATEGORY"), eq(categoryId.toString()), contains("FOREX"));
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_PRICE_ADJUSTMENT_CANCEL"), eq("PRICE_ADJUSTMENT"), eq(adjustmentId.toString()), contains("bad quote"));
  }

  @Test
  void categoryQueryReturnsPagedResponse() {
    SymbolCategoryEntity category = new SymbolCategoryEntity();
    category.setId(UUID.randomUUID());
    category.setName("Metals");
    category.setCode("METALS");
    category.setSortOrder(3);
    category.setEnabled(true);

    Page<SymbolCategoryEntity> page = Page.of(1, 20);
    page.setRecords(List.of(category));
    page.setTotal(1);
    when(categoryRepository.findAll(any(), eq(Map.of("sortOrder", "sort_order")), eq("sortOrder"), eq(true)))
        .thenReturn(page);

    AdminMarketQueryService queryService = new AdminMarketQueryService(
        symbolRepository,
        categoryRepository,
        priceAdjustmentRepository);

    var categories = queryService.categories(0, 20);

    assertThat(categories.items()).hasSize(1);
    assertThat(categories.items().get(0).code()).isEqualTo("METALS");
  }
}
