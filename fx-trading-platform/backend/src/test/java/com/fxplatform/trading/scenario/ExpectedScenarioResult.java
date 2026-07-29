package com.fxplatform.trading.scenario;

import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ExpectedScenarioResult(
    String caseId,
    List<Checkpoint> checkpoints,
    List<String> calculationTrace
) {

  public ExpectedScenarioResult {
    Objects.requireNonNull(caseId, "caseId");
    checkpoints = List.copyOf(Objects.requireNonNull(checkpoints, "checkpoints"));
    calculationTrace = List.copyOf(
        Objects.requireNonNull(calculationTrace, "calculationTrace"));
  }

  public Checkpoint finalCheckpoint() {
    if (checkpoints.isEmpty()) {
      throw new IllegalStateException(caseId + " has no expected checkpoints");
    }
    return checkpoints.getLast();
  }

  public Optional<FailureState> failure() {
    return checkpoints.stream()
        .map(Checkpoint::failure)
        .filter(Objects::nonNull)
        .reduce((first, second) -> second);
  }

  public record Checkpoint(
      int actionIndex,
      String actionId,
      Snapshot snapshot,
      FailureState failure
  ) {

    public Checkpoint {
      Objects.requireNonNull(actionId, "actionId");
      Objects.requireNonNull(snapshot, "snapshot");
    }
  }

  public record Snapshot(
      List<OrderState> orders,
      List<TradeState> trades,
      List<PositionState> positions,
      List<WalletState> wallets,
      AccountState account,
      List<LedgerState> ledger,
      List<ProtectionState> protections,
      List<EventState> events
  ) {

    public Snapshot {
      orders = List.copyOf(Objects.requireNonNull(orders, "orders"));
      trades = List.copyOf(Objects.requireNonNull(trades, "trades"));
      positions = List.copyOf(Objects.requireNonNull(positions, "positions"));
      wallets = List.copyOf(Objects.requireNonNull(wallets, "wallets"));
      Objects.requireNonNull(account, "account");
      ledger = List.copyOf(Objects.requireNonNull(ledger, "ledger"));
      protections = List.copyOf(Objects.requireNonNull(protections, "protections"));
      events = List.copyOf(Objects.requireNonNull(events, "events"));
    }
  }

  public record OrderState(
      String ref,
      OrderSide side,
      OrderType type,
      String status,
      BigDecimal quantity,
      BigDecimal filledQuantity,
      BigDecimal remainingQuantity,
      BigDecimal avgFillPrice,
      BigDecimal fee,
      String feeAsset,
      LiquidityRole liquidityRole,
      boolean reduceOnly,
      String origin,
      String parentRef,
      String contingencyRef,
      String holdAsset,
      BigDecimal holdAmount,
      String holdOwnerRef,
      String errorCode
  ) {

    public OrderState {
      Objects.requireNonNull(ref, "ref");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(quantity, "quantity");
      Objects.requireNonNull(filledQuantity, "filledQuantity");
      Objects.requireNonNull(remainingQuantity, "remainingQuantity");
      Objects.requireNonNull(fee, "fee");
      feeAsset = text(feeAsset);
      origin = text(origin);
      parentRef = text(parentRef);
      contingencyRef = text(contingencyRef);
      holdAsset = text(holdAsset);
      Objects.requireNonNull(holdAmount, "holdAmount");
      holdOwnerRef = text(holdOwnerRef);
      errorCode = text(errorCode);
    }
  }

  public record TradeState(
      String ref,
      String orderRef,
      OrderSide side,
      BigDecimal quantity,
      BigDecimal price,
      BigDecimal quoteNotional,
      BigDecimal fee,
      String feeAsset,
      BigDecimal realizedPnl,
      ProductType productType,
      PositionSide positionSide,
      MarginMode marginMode,
      LiquidityRole liquidityRole,
      String uniqueKey
  ) {

    public TradeState {
      Objects.requireNonNull(ref, "ref");
      Objects.requireNonNull(orderRef, "orderRef");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(quantity, "quantity");
      Objects.requireNonNull(price, "price");
      Objects.requireNonNull(quoteNotional, "quoteNotional");
      Objects.requireNonNull(fee, "fee");
      Objects.requireNonNull(feeAsset, "feeAsset");
      Objects.requireNonNull(realizedPnl, "realizedPnl");
      Objects.requireNonNull(productType, "productType");
      Objects.requireNonNull(positionSide, "positionSide");
      Objects.requireNonNull(marginMode, "marginMode");
      Objects.requireNonNull(liquidityRole, "liquidityRole");
      Objects.requireNonNull(uniqueKey, "uniqueKey");
    }
  }

  public record PositionState(
      String slot,
      String status,
      OrderSide side,
      PositionMode positionMode,
      PositionSide positionSide,
      MarginMode marginMode,
      BigDecimal quantity,
      BigDecimal averageEntry,
      BigDecimal markPrice,
      BigDecimal realizedPnl,
      BigDecimal unrealizedPnl,
      BigDecimal fundingPnl,
      BigDecimal marginHeld,
      BigDecimal initialMargin,
      BigDecimal maintenanceMargin,
      BigDecimal notional,
      int leverage
  ) {

    public PositionState {
      Objects.requireNonNull(slot, "slot");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(positionMode, "positionMode");
      Objects.requireNonNull(positionSide, "positionSide");
      Objects.requireNonNull(marginMode, "marginMode");
      Objects.requireNonNull(quantity, "quantity");
      Objects.requireNonNull(averageEntry, "averageEntry");
      Objects.requireNonNull(markPrice, "markPrice");
      Objects.requireNonNull(realizedPnl, "realizedPnl");
      Objects.requireNonNull(unrealizedPnl, "unrealizedPnl");
      Objects.requireNonNull(fundingPnl, "fundingPnl");
      Objects.requireNonNull(marginHeld, "marginHeld");
      Objects.requireNonNull(initialMargin, "initialMargin");
      Objects.requireNonNull(maintenanceMargin, "maintenanceMargin");
      Objects.requireNonNull(notional, "notional");
    }
  }

  public record WalletState(
      String walletType,
      String asset,
      BigDecimal total,
      BigDecimal available,
      BigDecimal locked
  ) {

    public WalletState {
      Objects.requireNonNull(walletType, "walletType");
      Objects.requireNonNull(asset, "asset");
      Objects.requireNonNull(total, "total");
      Objects.requireNonNull(available, "available");
      Objects.requireNonNull(locked, "locked");
    }
  }

  public record AccountState(
      BigDecimal balance,
      BigDecimal equity,
      BigDecimal usedMargin,
      BigDecimal freeMargin,
      BigDecimal maintenanceMargin,
      BigDecimal bankruptcyShortfall
  ) {

    public AccountState {
      Objects.requireNonNull(balance, "balance");
      Objects.requireNonNull(equity, "equity");
      Objects.requireNonNull(usedMargin, "usedMargin");
      Objects.requireNonNull(freeMargin, "freeMargin");
      Objects.requireNonNull(maintenanceMargin, "maintenanceMargin");
      Objects.requireNonNull(bankruptcyShortfall, "bankruptcyShortfall");
    }
  }

  public record LedgerState(
      int sequence,
      String type,
      String asset,
      BigDecimal amount,
      BigDecimal balanceAfter,
      String referenceType,
      String reference,
      String operationType
  ) {

    public LedgerState {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(asset, "asset");
      Objects.requireNonNull(amount, "amount");
      Objects.requireNonNull(balanceAfter, "balanceAfter");
      Objects.requireNonNull(referenceType, "referenceType");
      Objects.requireNonNull(reference, "reference");
      Objects.requireNonNull(operationType, "operationType");
    }
  }

  public record ProtectionState(
      String ref,
      ProtectionType type,
      String status,
      String positionRef,
      PositionSide positionSide,
      BigDecimal quantity,
      BigDecimal triggerPrice,
      TriggerPriceType triggerPriceType,
      TriggerExecutionType executionType,
      BigDecimal limitPrice,
      int createdSequence
  ) {

    public ProtectionState {
      Objects.requireNonNull(ref, "ref");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(positionRef, "positionRef");
      Objects.requireNonNull(positionSide, "positionSide");
      Objects.requireNonNull(quantity, "quantity");
      Objects.requireNonNull(triggerPrice, "triggerPrice");
      Objects.requireNonNull(triggerPriceType, "triggerPriceType");
      Objects.requireNonNull(executionType, "executionType");
    }
  }

  public record EventState(
      int sequence,
      String type,
      String subjectRef,
      String fromStatus,
      String toStatus,
      String code
  ) {

    public EventState {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(subjectRef, "subjectRef");
      fromStatus = text(fromStatus);
      toStatus = text(toStatus);
      code = text(code);
    }
  }

  public record FailureState(
      String code,
      String exceptionType,
      boolean zeroMutation
  ) {

    public FailureState {
      code = text(code);
      Objects.requireNonNull(exceptionType, "exceptionType");
    }
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }
}
