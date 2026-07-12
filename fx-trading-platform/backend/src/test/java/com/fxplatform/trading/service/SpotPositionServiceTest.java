package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.trading.entity.SpotPositionEntity;
import com.fxplatform.trading.repository.SpotPositionRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SpotPositionServiceTest {

  @Mock
  private SpotPositionRepository spotPositionRepository;

  private final Map<String, SpotPositionEntity> positions = new HashMap<>();

  @BeforeEach
  void setUpRepository() {
    org.mockito.Mockito.lenient().when(spotPositionRepository.findBySlotForUpdate(
        any(UUID.class),
        any(String.class),
        any(String.class),
        any(String.class)))
        .thenAnswer(invocation -> Optional.ofNullable(positions.get(key(
            invocation.getArgument(0),
            invocation.getArgument(1),
            invocation.getArgument(2),
            invocation.getArgument(3)))));
    org.mockito.Mockito.lenient().when(spotPositionRepository.save(any(SpotPositionEntity.class))).thenAnswer(invocation -> {
      SpotPositionEntity position = invocation.getArgument(0);
      if (position.getId() == null) {
        position.setId(UUID.randomUUID());
      }
      positions.put(key(position.getAccountId(), position.getWalletType(), position.getAsset(), position.getCostAsset()), position);
      return position;
    });
  }

  @Test
  void applyBuyAndSellMaintainsWeightedAverageCostAndRealizedPnl() {
    UUID accountId = UUID.randomUUID();
    SpotPositionService service = new SpotPositionService(spotPositionRepository);

    service.applyBuy(accountId, "btc", "usdt", new BigDecimal("1.00000000"), new BigDecimal("100.00000000"), new BigDecimal("0.50000000"));
    SpotPositionEntity position = service.applyBuy(
        accountId,
        "BTC",
        "USDT",
        new BigDecimal("2.00000000"),
        new BigDecimal("220.00000000"),
        new BigDecimal("1.00000000"));

    assertThat(position.getQuantity()).isEqualByComparingTo("3.00000000");
    assertThat(position.getAverageCost()).isEqualByComparingTo("106.66666667");
    assertThat(position.getFeeCost()).isEqualByComparingTo("1.50000000");

    position = service.applySell(
        accountId,
        "BTC",
        "USDT",
        new BigDecimal("1.00000000"),
        new BigDecimal("120.00000000"),
        new BigDecimal("2.00000000"));

    assertThat(position.getQuantity()).isEqualByComparingTo("2.00000000");
    assertThat(position.getAverageCost()).isEqualByComparingTo("106.66666667");
    assertThat(position.getRealizedPnl()).isEqualByComparingTo("11.33333333");
    assertThat(position.getFeeCost()).isEqualByComparingTo("3.50000000");
  }

  @Test
  void lockExistingLocksOnlyExistingAccountRowsWithoutCreatingAZeroPosition() {
    UUID accountId = UUID.randomUUID();
    when(spotPositionRepository.findByAccountIdForUpdate(accountId)).thenReturn(List.of());

    List<SpotPositionEntity> locked = new SpotPositionService(spotPositionRepository)
        .lockExisting(accountId);

    assertThat(locked).isEmpty();
    verify(spotPositionRepository).findByAccountIdForUpdate(accountId);
    verify(spotPositionRepository, never()).save(any());
    verify(spotPositionRepository, never())
        .findBySlotForUpdate(any(), any(), any(), any());
  }

  private static String key(UUID accountId, String walletType, String asset, String costAsset) {
    return accountId + ":" + walletType + ":" + asset + ":" + costAsset;
  }
}
