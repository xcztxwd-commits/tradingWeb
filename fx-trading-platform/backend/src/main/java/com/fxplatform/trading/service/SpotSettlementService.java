package com.fxplatform.trading.service;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.SymbolAssets;
import com.fxplatform.market.service.SymbolAssetResolver;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.SpotPositionEntity;
import com.fxplatform.trading.event.TradingAccountMutationEvent;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SpotSettlementService {

  private static final int SCALE = 8;
  private static final String TRADE_REFERENCE = "TRADE";
  private static final String ORDER_REFERENCE = "ORDER";

  private final WalletService walletService;
  private final SpotPositionService spotPositionService;
  private ApplicationEventPublisher accountMutationPublisher;

  public SpotSettlementService(WalletService walletService) {
    this(walletService, null);
  }

  @Autowired
  public SpotSettlementService(WalletService walletService, SpotPositionService spotPositionService) {
    this.walletService = walletService;
    this.spotPositionService = spotPositionService;
  }

  @Autowired
  void setAccountMutationPublisher(ApplicationEventPublisher accountMutationPublisher) {
    this.accountMutationPublisher = accountMutationPublisher;
  }

  @Transactional
  public void settleBuyFill(
      OrderEntity order,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account
  ) {
    settleBuyFill(order, order, fill, symbolProfile, account, ORDER_REFERENCE, order.getId());
  }

  public void settleBuyFillUsingHoldOwner(
      OrderEntity order,
      OrderEntity holdOwner,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account
  ) {
    settleBuyFill(order, holdOwner, fill, symbolProfile, account, ORDER_REFERENCE, order.getId());
  }

  @Transactional
  public void settleBuyFill(
      OrderEntity order,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account,
      UUID tradeId
  ) {
    settleBuyFill(order, order, fill, symbolProfile, account, TRADE_REFERENCE, tradeId);
  }

  public void settleBuyFill(
      OrderEntity order,
      OrderEntity holdOwner,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account,
      UUID tradeId
  ) {
    settleBuyFill(order, holdOwner, fill, symbolProfile, account, TRADE_REFERENCE, tradeId);
  }

  private void settleBuyFill(
      OrderEntity order,
      OrderEntity holdOwner,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account,
      String referenceType,
      UUID referenceId
  ) {
    SymbolAssets assets = SymbolAssetResolver.resolve(symbolProfile);
    requireFeeAsset(fill, assets.quoteAsset());
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
    BigDecimal feeQuote = scaled(explicitFee(fill));

    debitBuyQuoteAndFee(
        order,
        holdOwner,
        account,
        assets.quoteAsset(),
        grossQuote,
        feeQuote,
        referenceType,
        referenceId);
    walletService.creditAvailableWithEntryType(
        account.getId(),
        assets.baseAsset(),
        filledBase,
        referenceType,
        referenceId,
        "Spot buy base received",
        "SPOT_BUY_CREDIT");
    if (spotPositionService != null) {
      spotPositionService.applyBuy(
          account.getId(),
          assets.baseAsset(),
          assets.quoteAsset(),
          filledBase,
          grossQuote,
          feeQuote);
    }
  }

  public void settleBuyPartialFill(
      OrderEntity order,
      OrderEntity holdOwner,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account,
      UUID tradeId
  ) {
    SymbolAssets assets = SymbolAssetResolver.resolve(symbolProfile);
    requireFeeAsset(fill, assets.quoteAsset());
    BigDecimal filledBase = scaled(fillQuantity(order, fill));
    BigDecimal grossQuote = scaled(filledBase.multiply(fill.filledPrice()));
    BigDecimal feeQuote = scaled(explicitFee(fill));
    BigDecimal lockedSpend = scaled(grossQuote.add(feeQuote));
    requirePartialLockedHold(holdOwner, assets.quoteAsset(), lockedSpend, tradeId);

    walletService.debitLockedWithEntryType(
        account.getId(),
        assets.quoteAsset(),
        grossQuote,
        TRADE_REFERENCE,
        tradeId,
        "Spot buy quote spent",
        "SPOT_BUY_DEBIT");
    if (feeQuote.signum() > 0) {
      walletService.debitLockedWithEntryType(
          account.getId(),
          assets.quoteAsset(),
          feeQuote,
          TRADE_REFERENCE,
          tradeId,
          "Spot buy fee charged in USDT",
          "TRADE_FEE");
    }
    walletService.creditAvailableWithEntryType(
        account.getId(),
        assets.baseAsset(),
        filledBase,
        TRADE_REFERENCE,
        tradeId,
        "Spot buy base received",
        "SPOT_BUY_CREDIT");
    if (spotPositionService != null) {
      SpotPositionEntity position = spotPositionService.applyBuy(
          account.getId(),
          assets.baseAsset(),
          assets.quoteAsset(),
          filledBase,
          grossQuote,
          feeQuote);
      publishSpotPositionRefresh(order, account, tradeId, position, fill.filledAt());
    }
  }

  @Transactional
  public void settleSellFill(
      OrderEntity order,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account
  ) {
    settleSellFill(order, order, fill, symbolProfile, account, ORDER_REFERENCE, order.getId());
  }

  public void settleSellFillUsingHoldOwner(
      OrderEntity order,
      OrderEntity holdOwner,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account
  ) {
    settleSellFill(order, holdOwner, fill, symbolProfile, account, ORDER_REFERENCE, order.getId());
  }

  @Transactional
  public void settleSellFill(
      OrderEntity order,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account,
      UUID tradeId
  ) {
    settleSellFill(order, order, fill, symbolProfile, account, TRADE_REFERENCE, tradeId);
  }

  public void settleSellFill(
      OrderEntity order,
      OrderEntity holdOwner,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account,
      UUID tradeId
  ) {
    settleSellFill(order, holdOwner, fill, symbolProfile, account, TRADE_REFERENCE, tradeId);
  }

  private void settleSellFill(
      OrderEntity order,
      OrderEntity holdOwner,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account,
      String referenceType,
      UUID referenceId
  ) {
    SymbolAssets assets = SymbolAssetResolver.resolve(symbolProfile);
    requireFeeAsset(fill, assets.quoteAsset());
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
    BigDecimal feeQuote = scaled(explicitFee(fill));

    debitSpentAsset(
        order,
        holdOwner,
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

  private void debitBuyQuoteAndFee(
      OrderEntity order,
      OrderEntity holdOwner,
      TradingAccountEntity account,
      String quoteAsset,
      BigDecimal grossQuote,
      BigDecimal feeQuote,
      String referenceType,
      UUID referenceId
  ) {
    BigDecimal totalSpend = scaled(grossQuote.add(feeQuote));
    if (usesLockedHold(holdOwner, quoteAsset)) {
      BigDecimal ownerHold = scaled(holdOwner.getHoldAmount());
      if (totalSpend.compareTo(ownerHold) > 0) {
        throw new BusinessException(
            "LOCKED_BALANCE_NOT_ENOUGH",
            "Order hold is not enough for the filled amount and fee");
      }
      walletService.debitLockedWithEntryType(
          account.getId(),
          quoteAsset,
          grossQuote,
          referenceType,
          referenceId,
          "Spot buy quote spent",
          "SPOT_BUY_DEBIT");
      debitBuyFeeFromLocked(
          account, quoteAsset, feeQuote, referenceType, referenceId);
      releaseRemainingHold(holdOwner, account, quoteAsset, totalSpend);
      return;
    }
    rejectUnexpectedHold(order, holdOwner);
    WalletBalanceEntity quoteBalance = walletService.getOrCreateBalance(
        account.getId(), WalletType.SPOT, quoteAsset);
    if (scaled(quoteBalance.getAvailable()).compareTo(totalSpend) < 0) {
      throw new BusinessException(
          ErrorCode.INSUFFICIENT_BALANCE,
          "Available balance is not enough");
    }
    walletService.debitAvailableWithEntryType(
        account.getId(),
        quoteAsset,
        grossQuote,
        referenceType,
        referenceId,
        "Spot buy quote spent",
        "SPOT_BUY_DEBIT");
    if (feeQuote.signum() > 0) {
      walletService.debitAvailableWithEntryType(
          account.getId(),
          quoteAsset,
          feeQuote,
          referenceType,
          referenceId,
          "Spot buy fee charged in USDT",
          "TRADE_FEE");
    }
  }

  private void debitBuyFeeFromLocked(
      TradingAccountEntity account,
      String quoteAsset,
      BigDecimal feeQuote,
      String referenceType,
      UUID referenceId
  ) {
    if (feeQuote.signum() <= 0) {
      return;
    }
    walletService.debitLockedWithEntryType(
        account.getId(),
        quoteAsset,
        feeQuote,
        referenceType,
        referenceId,
        "Spot buy fee charged in USDT",
        "TRADE_FEE");
  }

  private void publishSpotPositionRefresh(
      OrderEntity order,
      TradingAccountEntity account,
      UUID tradeId,
      SpotPositionEntity position,
      Instant occurredAt
  ) {
    if (accountMutationPublisher == null
        || order == null
        || order.getUserId() == null
        || account == null
        || account.getId() == null
        || tradeId == null
        || position == null
        || position.getId() == null
        || occurredAt == null) {
      return;
    }
    accountMutationPublisher.publishEvent(new TradingAccountMutationEvent(
        order.getUserId(),
        account.getId(),
        "POSITION_UPDATED",
        "POSITION",
        position.getId(),
        tradeId,
        null,
        occurredAt));
  }

  public void settleSellPartialFill(
      OrderEntity order,
      OrderEntity holdOwner,
      ExecutionResult fill,
      SymbolEntity symbolProfile,
      TradingAccountEntity account,
      UUID tradeId
  ) {
    SymbolAssets assets = SymbolAssetResolver.resolve(symbolProfile);
    requireFeeAsset(fill, assets.quoteAsset());
    BigDecimal soldBase = scaled(fillQuantity(order, fill));
    BigDecimal grossQuote = scaled(soldBase.multiply(fill.filledPrice()));
    BigDecimal feeQuote = scaled(explicitFee(fill));
    requirePartialLockedHold(holdOwner, assets.baseAsset(), soldBase, tradeId);

    walletService.debitLockedWithEntryType(
        account.getId(),
        assets.baseAsset(),
        soldBase,
        TRADE_REFERENCE,
        tradeId,
        "Spot sell base spent",
        "SPOT_SELL_DEBIT");
    walletService.creditAvailableWithEntryType(
        account.getId(),
        assets.quoteAsset(),
        grossQuote,
        TRADE_REFERENCE,
        tradeId,
        "Spot sell quote received",
        "SPOT_SELL_CREDIT");
    if (feeQuote.signum() > 0) {
      walletService.debitAvailableWithEntryType(
          account.getId(),
          assets.quoteAsset(),
          feeQuote,
          TRADE_REFERENCE,
          tradeId,
          "Spot sell fee charged in quote asset",
          "TRADE_FEE");
    }
    if (spotPositionService != null) {
      SpotPositionEntity position = spotPositionService.applySell(
          account.getId(),
          assets.baseAsset(),
          assets.quoteAsset(),
          soldBase,
          grossQuote,
          feeQuote);
      publishSpotPositionRefresh(order, account, tradeId, position, fill.filledAt());
    }
  }

  private void requirePartialLockedHold(
      OrderEntity holdOwner,
      String expectedAsset,
      BigDecimal lockedSpend,
      UUID tradeId
  ) {
    if (holdOwner == null || tradeId == null || !usesLockedHold(holdOwner, expectedAsset)) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Spot partial fill requires one positive locked hold in the spent asset");
    }
    if (scaled(holdOwner.getHoldAmount()).compareTo(scaled(lockedSpend)) < 0) {
      throw new BusinessException(
          "LOCKED_BALANCE_NOT_ENOUGH",
          "Order hold is not enough for the partial fill");
    }
  }

  private void debitSpentAsset(
      OrderEntity order,
      OrderEntity holdOwner,
      TradingAccountEntity account,
      String asset,
      BigDecimal amount,
      String referenceType,
      UUID referenceId,
      String description,
    String entryType
  ) {
    if (usesLockedHold(holdOwner, asset)) {
      BigDecimal spentAmount = scaled(amount);
      BigDecimal ownerHold = scaled(holdOwner.getHoldAmount());
      if (spentAmount.compareTo(ownerHold) > 0) {
        throw new BusinessException(
            "LOCKED_BALANCE_NOT_ENOUGH",
            "Order hold is not enough for the filled amount");
      }
      walletService.debitLockedWithEntryType(
          account.getId(),
          asset,
          spentAmount,
          referenceType,
          referenceId,
          description,
          entryType);
      releaseRemainingHold(holdOwner, account, asset, spentAmount);
      return;
    }
    rejectUnexpectedHold(order, holdOwner);
    walletService.debitAvailableWithEntryType(
        account.getId(),
        asset,
        amount,
        referenceType,
        referenceId,
        description,
        entryType);
  }

  private void rejectUnexpectedHold(OrderEntity order, OrderEntity holdOwner) {
    if (holdOwner.getHoldAmount() != null
        && holdOwner.getHoldAmount().compareTo(BigDecimal.ZERO) > 0) {
      boolean oco = holdOwner != order
          || order.getContingencyGroupId() != null
          || holdOwner.getContingencyGroupId() != null;
      throw new BusinessException(
          oco ? "OCO_GROUP_INCOMPLETE" : com.fxplatform.common.exception.ErrorCode.ORDER_HOLD_INVALID,
          "Pending settlement hold currency does not match the spent asset");
    }
    if (holdOwner != order
        || order.getContingencyGroupId() != null
        || holdOwner.getContingencyGroupId() != null) {
      throw new BusinessException(
          "OCO_GROUP_INCOMPLETE",
          "OCO settlement requires one positive hold in the spent asset");
    }
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

  private static BigDecimal explicitFee(ExecutionResult fill) {
    return fill.fee() == null ? BigDecimal.ZERO : fill.fee();
  }

  private static void requireFeeAsset(ExecutionResult fill, String expectedAsset) {
    BigDecimal fee = explicitFee(fill);
    if (fee.compareTo(BigDecimal.ZERO) < 0) {
      throw new BusinessException(
          "INVALID_FEE_AMOUNT",
          "Spot fee amount cannot be negative");
    }
    if (fee.compareTo(BigDecimal.ZERO) > 0
        && (fill.feeAsset() == null
        || fill.feeAsset().isBlank()
        || !expectedAsset.equalsIgnoreCase(fill.feeAsset()))) {
      throw new BusinessException(
          "INVALID_FEE_ASSET",
          "Spot fee asset must match the quote asset");
    }
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
