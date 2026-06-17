package com.fxplatform.trading.service;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.SymbolAssets;
import com.fxplatform.market.service.SymbolAssetResolver;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SpotSettlementService {

  private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.0010");
  private static final int SCALE = 8;
  private static final String TRADE_REFERENCE = "TRADE";
  private static final String ORDER_REFERENCE = "ORDER";

  private final WalletService walletService;
  private final SpotPositionService spotPositionService;

  public SpotSettlementService(WalletService walletService) {
    this(walletService, null);
  }

  @Autowired
  public SpotSettlementService(WalletService walletService, SpotPositionService spotPositionService) {
    this.walletService = walletService;
    this.spotPositionService = spotPositionService;
  }

  @Transactional
  public void settleBuyFill(
      OrderEntity order,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account
  ) {
    settleBuyFill(order, fill, symbolProfile, account, ORDER_REFERENCE, order.getId());
  }

  @Transactional
  public void settleBuyFill(
      OrderEntity order,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account,
      UUID tradeId
  ) {
    settleBuyFill(order, fill, symbolProfile, account, TRADE_REFERENCE, tradeId);
  }

  private void settleBuyFill(
      OrderEntity order,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account,
      String referenceType,
      UUID referenceId
  ) {
    SymbolAssets assets = SymbolAssetResolver.resolve(symbolProfile);
    if (walletService.hasBusinessOperation(
        account.getId(),
        WalletType.SPOT,
        assets.baseAsset(),
        referenceType,
        referenceId,
        "SPOT_BUY_CREDIT")) {
      return;
    }
    BigDecimal filledBase = scaled(fillQuantity(order, fill));
    BigDecimal grossQuote = scaled(filledBase.multiply(fill.filledPrice()));
    BigDecimal feeBase = scaled(filledBase.multiply(feeRate(fill, grossQuote)));
    BigDecimal netBase = scaled(filledBase.subtract(feeBase));

    debitSpentAsset(
        order,
        account,
        assets.quoteAsset(),
        grossQuote,
        referenceType,
        referenceId,
        "Spot buy quote spent",
        "SPOT_BUY_DEBIT");
    walletService.creditAvailableWithEntryType(
        account.getId(),
        assets.baseAsset(),
        filledBase,
        referenceType,
        referenceId,
        "Spot buy base received",
        "SPOT_BUY_CREDIT");
    walletService.debitAvailableWithEntryType(
        account.getId(),
        assets.baseAsset(),
        feeBase,
        referenceType,
        referenceId,
        "Spot buy fee charged in base asset",
        "TRADE_FEE");
    if (spotPositionService != null) {
      spotPositionService.applyBuy(
          account.getId(),
          assets.baseAsset(),
          assets.quoteAsset(),
          netBase,
          grossQuote,
          scaled(feeBase.multiply(fill.filledPrice())));
    }
  }

  @Transactional
  public void settleSellFill(
      OrderEntity order,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account
  ) {
    settleSellFill(order, fill, symbolProfile, account, ORDER_REFERENCE, order.getId());
  }

  @Transactional
  public void settleSellFill(
      OrderEntity order,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account,
      UUID tradeId
  ) {
    settleSellFill(order, fill, symbolProfile, account, TRADE_REFERENCE, tradeId);
  }

  private void settleSellFill(
      OrderEntity order,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account,
      String referenceType,
      UUID referenceId
  ) {
    SymbolAssets assets = SymbolAssetResolver.resolve(symbolProfile);
    if (walletService.hasBusinessOperation(
        account.getId(),
        WalletType.SPOT,
        assets.quoteAsset(),
        referenceType,
        referenceId,
        "SPOT_SELL_CREDIT")) {
      return;
    }
    BigDecimal soldBase = scaled(fillQuantity(order, fill));
    BigDecimal grossQuote = scaled(soldBase.multiply(fill.filledPrice()));
    BigDecimal feeQuote = scaled(grossQuote.multiply(feeRate(fill, grossQuote)));

    debitSpentAsset(
        order,
        account,
        assets.baseAsset(),
        soldBase,
        referenceType,
        referenceId,
        "Spot sell base spent",
        "SPOT_SELL_DEBIT");
    walletService.creditAvailableWithEntryType(
        account.getId(),
        assets.quoteAsset(),
        grossQuote,
        referenceType,
        referenceId,
        "Spot sell quote received",
        "SPOT_SELL_CREDIT");
    walletService.debitAvailableWithEntryType(
        account.getId(),
        assets.quoteAsset(),
        feeQuote,
        referenceType,
        referenceId,
        "Spot sell fee charged in quote asset",
        "TRADE_FEE");
    if (spotPositionService != null) {
      spotPositionService.applySell(
          account.getId(),
          assets.baseAsset(),
          assets.quoteAsset(),
          soldBase,
          grossQuote,
          feeQuote);
    }
  }

  private void debitSpentAsset(
      OrderEntity order,
      TradingAccountEntity account,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description,
      String entryType
  ) {
    if (usesLockedHold(order, asset)) {
      walletService.debitLockedWithEntryType(
          account.getId(),
          asset,
          amount,
          referenceType,
          referenceId,
          description,
          entryType);
      releaseRemainingHold(order, account, asset, amount);
      return;
    }
    walletService.debitAvailableWithEntryType(
        account.getId(),
        asset,
        amount,
        referenceType,
        referenceId,
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
        ORDER_REFERENCE,
        order.getId(),
        "Spot pending order remaining hold released",
        "ORDER_RELEASE");
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
