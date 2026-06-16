package com.fxplatform.trading.service;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.SymbolAssets;
import com.fxplatform.market.service.SymbolAssetResolver;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SpotSettlementService {

  private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.0010");
  private static final int SCALE = 8;

  private final WalletService walletService;

  @Transactional
  public void settleBuyFill(
      OrderEntity order,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account
  ) {
    SymbolAssets assets = SymbolAssetResolver.resolve(symbolProfile);
    BigDecimal filledBase = scaled(fillQuantity(order, fill));
    BigDecimal grossQuote = scaled(filledBase.multiply(fill.filledPrice()));
    BigDecimal feeBase = scaled(filledBase.multiply(feeRate(fill, grossQuote)));

    debitSpentAsset(order, account, assets.quoteAsset(), grossQuote, "Spot buy quote spent", "SPOT_BUY_QUOTE_OUT");
    walletService.creditAvailableWithEntryType(
        account.getId(),
        assets.baseAsset(),
        filledBase,
        "ORDER",
        order.getId(),
        "Spot buy base received",
        "SPOT_BUY_BASE_IN");
    walletService.debitAvailableWithEntryType(
        account.getId(),
        assets.baseAsset(),
        feeBase,
        "ORDER",
        order.getId(),
        "Spot buy fee charged in base asset",
        "SPOT_FEE_BASE");
  }

  @Transactional
  public void settleSellFill(
      OrderEntity order,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account
  ) {
    SymbolAssets assets = SymbolAssetResolver.resolve(symbolProfile);
    BigDecimal soldBase = scaled(fillQuantity(order, fill));
    BigDecimal grossQuote = scaled(soldBase.multiply(fill.filledPrice()));
    BigDecimal feeQuote = scaled(grossQuote.multiply(feeRate(fill, grossQuote)));

    debitSpentAsset(order, account, assets.baseAsset(), soldBase, "Spot sell base spent", "SPOT_SELL_BASE_OUT");
    walletService.creditAvailableWithEntryType(
        account.getId(),
        assets.quoteAsset(),
        grossQuote,
        "ORDER",
        order.getId(),
        "Spot sell quote received",
        "SPOT_SELL_QUOTE_IN");
    walletService.debitAvailableWithEntryType(
        account.getId(),
        assets.quoteAsset(),
        feeQuote,
        "ORDER",
        order.getId(),
        "Spot sell fee charged in quote asset",
        "SPOT_FEE_QUOTE");
  }

  private void debitSpentAsset(
      OrderEntity order,
      TradingAccountEntity account,
      String asset,
      BigDecimal amount,
      String description,
      String entryType
  ) {
    if (usesLockedHold(order, asset)) {
      walletService.debitLockedWithEntryType(
          account.getId(),
          asset,
          amount,
          "ORDER",
          order.getId(),
          description,
          entryType);
      releaseRemainingHold(order, account, asset, amount);
      return;
    }
    walletService.debitAvailableWithEntryType(
        account.getId(),
        asset,
        amount,
        "ORDER",
        order.getId(),
        description,
        entryType);
  }

  private void releaseRemainingHold(
      OrderEntity order,
      TradingAccountEntity account,
      String asset,
      BigDecimal spentAmount
  ) {
    BigDecimal remainingHold = scaled(order.getHoldAmount().subtract(spentAmount));
    if (remainingHold.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    walletService.releaseLockedWithEntryType(
        account.getId(),
        asset,
        remainingHold,
        "ORDER",
        order.getId(),
        "Spot pending order remaining hold released",
        "SPOT_ORDER_RELEASE");
  }

  private boolean usesLockedHold(OrderEntity order, String asset) {
    return order.getHoldAmount() != null
        && order.getHoldAmount().compareTo(BigDecimal.ZERO) > 0
        && order.getHoldCurrency() != null
        && order.getHoldCurrency().equalsIgnoreCase(asset);
  }

  private static BigDecimal feeRate(ExecutionResult fill, BigDecimal grossQuote) {
    BigDecimal fee = fill.fee();
    if (fee == null || fee.compareTo(BigDecimal.ZERO) <= 0 || grossQuote.compareTo(BigDecimal.ZERO) <= 0) {
      return DEFAULT_FEE_RATE;
    }
    return fee.divide(grossQuote, 10, RoundingMode.HALF_UP);
  }

  private static BigDecimal scaled(BigDecimal value) {
    return value.setScale(SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal fillQuantity(OrderEntity order, ExecutionResult fill) {
    if (fill.filledQuantity() != null) {
      return fill.filledQuantity();
    }
    if (order.getQuantity() != null) {
      return order.getQuantity();
    }
    return order.getLots();
  }
}
