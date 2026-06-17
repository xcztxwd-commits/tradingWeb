package com.fxplatform.trading.service;

import com.fxplatform.trading.entity.SpotPositionEntity;
import com.fxplatform.trading.repository.SpotPositionRepository;
import com.fxplatform.wallet.enums.WalletType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SpotPositionService {

  private static final int SCALE = 8;

  private final SpotPositionRepository spotPositionRepository;

  @Transactional
  public SpotPositionEntity applyBuy(
      UUID accountId,
      String asset,
      String costAsset,
      BigDecimal quantity,
      BigDecimal cost,
      BigDecimal feeCost
  ) {
    SpotPositionEntity position = position(accountId, asset, costAsset);
    BigDecimal oldQuantity = scale(position.getQuantity());
    BigDecimal buyQuantity = scale(quantity);
    BigDecimal newQuantity = oldQuantity.add(buyQuantity);
    BigDecimal oldCost = oldQuantity.multiply(scale(position.getAverageCost()));
    BigDecimal newCost = oldCost.add(scale(cost));
    position.setQuantity(scale(newQuantity));
    position.setAverageCost(newQuantity.signum() == 0
        ? zero()
        : newCost.divide(newQuantity, SCALE, RoundingMode.HALF_UP));
    position.setFeeCost(scale(position.getFeeCost()).add(scale(feeCost)));
    return spotPositionRepository.save(position);
  }

  @Transactional
  public SpotPositionEntity applySell(
      UUID accountId,
      String asset,
      String costAsset,
      BigDecimal quantity,
      BigDecimal proceeds,
      BigDecimal feeCost
  ) {
    SpotPositionEntity position = position(accountId, asset, costAsset);
    BigDecimal sellQuantity = scale(quantity);
    BigDecimal trackedQuantity = scale(position.getQuantity());
    BigDecimal costQuantity = trackedQuantity.min(sellQuantity);
    BigDecimal costBasis = costQuantity.multiply(scale(position.getAverageCost()));
    BigDecimal realized = scale(proceeds).subtract(costBasis).subtract(scale(feeCost));
    BigDecimal remaining = trackedQuantity.subtract(sellQuantity).max(BigDecimal.ZERO);
    position.setQuantity(scale(remaining));
    if (remaining.signum() == 0) {
      position.setAverageCost(zero());
      position.setUnrealizedPnl(zero());
    }
    position.setRealizedPnl(scale(position.getRealizedPnl()).add(scale(realized)));
    position.setFeeCost(scale(position.getFeeCost()).add(scale(feeCost)));
    return spotPositionRepository.save(position);
  }

  private SpotPositionEntity position(UUID accountId, String asset, String costAsset) {
    String normalizedAsset = normalize(asset);
    String normalizedCostAsset = normalize(costAsset);
    return spotPositionRepository.findByAccountIdAndWalletTypeAndAssetAndCostAsset(
            accountId,
            WalletType.SPOT.code(),
            normalizedAsset,
            normalizedCostAsset)
        .orElseGet(() -> newPosition(accountId, normalizedAsset, normalizedCostAsset));
  }

  private SpotPositionEntity newPosition(UUID accountId, String asset, String costAsset) {
    SpotPositionEntity position = new SpotPositionEntity();
    position.setAccountId(accountId);
    position.setWalletType(WalletType.SPOT.code());
    position.setAsset(asset);
    position.setCostAsset(costAsset);
    position.setQuantity(zero());
    position.setAverageCost(zero());
    position.setRealizedPnl(zero());
    position.setUnrealizedPnl(zero());
    position.setFeeCost(zero());
    return position;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private static BigDecimal scale(BigDecimal value) {
    return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
  }
}
