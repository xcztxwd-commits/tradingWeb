package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.finance.entity.AdminPaymentMethodEntity;
import com.fxplatform.finance.repository.AdminPaymentMethodRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminPaymentMethodQueryServiceTest {

  @Mock
  private AdminPaymentMethodRepository paymentMethodRepository;

  @Test
  void paymentMethodsReturnPagedAdminDtoSortedByDisplayOrder() {
    AdminPaymentMethodEntity method = new AdminPaymentMethodEntity();
    method.setId(UUID.randomUUID());
    method.setName("Bank Transfer");
    method.setMethodType("BANK_TRANSFER");
    method.setCurrency("USD");
    method.setEnabled(true);
    method.setDisplayOrder(10);
    method.setInstructions("Upload bank slip");
    when(paymentMethodRepository.findAll(any(), eq(Map.of("displayOrder", "display_order")), eq("displayOrder"), eq(true)))
        .thenReturn(page(method));

    var page = new AdminPaymentMethodQueryService(paymentMethodRepository).paymentMethods(0, 20);

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).name()).isEqualTo("Bank Transfer");
    assertThat(page.items().get(0).methodType()).isEqualTo("BANK_TRANSFER");
    assertThat(page.total()).isEqualTo(1);
  }

  @Test
  void paymentMethodsSupportBackendFiltersAndSorting() {
    AdminPaymentMethodEntity method = new AdminPaymentMethodEntity();
    method.setId(UUID.randomUUID());
    method.setName("USDT TRC20");
    method.setMethodType("CRYPTO");
    method.setCurrency("USDT");
    method.setEnabled(true);
    method.setDisplayOrder(1);
    when(paymentMethodRepository.selectPage(any(Page.class), any(QueryWrapper.class))).thenAnswer(invocation -> {
      Page<AdminPaymentMethodEntity> requestedPage = invocation.getArgument(0);
      requestedPage.setRecords(List.of(method));
      requestedPage.setTotal(1);
      return requestedPage;
    });

    var response = new AdminPaymentMethodQueryService(paymentMethodRepository).paymentMethods(AdminFeaturePageQuery.from(
        1,
        15,
        "name",
        "desc",
        Map.of("filter.name", "USDT", "filter.enabled", "true")));

    assertThat(response.page()).isEqualTo(1);
    assertThat(response.items()).singleElement().satisfies(row -> {
      assertThat(row.name()).isEqualTo("USDT TRC20");
      assertThat(row.methodType()).isEqualTo("CRYPTO");
    });

    ArgumentCaptor<QueryWrapper<AdminPaymentMethodEntity>> queryCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
    verify(paymentMethodRepository).selectPage(any(Page.class), queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment())
        .contains("name")
        .contains("enabled")
        .contains("ORDER BY name DESC");
  }

  private static <T> Page<T> page(T item) {
    Page<T> page = Page.of(1, 20);
    page.setRecords(List.of(item));
    page.setTotal(1);
    return page;
  }
}
