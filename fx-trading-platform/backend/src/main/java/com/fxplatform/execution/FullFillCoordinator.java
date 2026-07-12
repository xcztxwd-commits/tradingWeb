package com.fxplatform.execution;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Owns canonical Demo full-fill price, fee and source metadata calculation. */
@Component
public class FullFillCoordinator {

  private static final BigDecimal MAKER_FEE_RATE = new BigDecimal("0.0002");
  private static final BigDecimal TAKER_FEE_RATE = new BigDecimal("0.0005");
  private static final BigDecimal SLIPPAGE_RATE = new BigDecimal("0.0001");

  private final ExecutionAdapter executionAdapter;
  private final Clock clock;

  @Autowired
  public FullFillCoordinator(ExecutionAdapter executionAdapter) {
    this(executionAdapter, Clock.systemUTC());
  }

  public FullFillCoordinator(ExecutionAdapter executionAdapter, Clock clock) {
    this.executionAdapter = executionAdapter;
    this.clock = clock;
  }

  public FullFillResult execute(FullFillRequest request, ExecutableMarketSnapshot snapshot) {
    validateRequest(request);
    validateSnapshot(request, snapshot);

    ExecutionResult intent = executionAdapter.execute(request.toExecutionIntent());
    if (intent != null && intent.rejected()) {
      throw new BusinessException(intent.rejectCode(), intent.rejectMessage());
    }
    requireFullIntent(request.requestedBaseQuantity(), intent);

    FullFillPricingProjection pricing = project(
        request.productType(),
        request.side(),
        request.executionPath(),
        request.limitPrice(),
        snapshot);
    BigDecimal fee = fee(request, pricing.filledPrice(), pricing.feeRate());

    FullFillResult result = new FullFillResult(
        pricing.filledPrice(),
        clock.instant(),
        request.requestedBaseQuantity(),
        BigDecimal.ZERO,
        pricing.feeRate(),
        fee,
        feeAsset(request),
        pricing.liquidityRole(),
        pricing.slippage(),
        snapshot.sourceMode(),
        snapshot.providerCode(),
        snapshot.providerSymbol(),
        snapshot.asOf(),
        snapshot.expiresAt());
    requireFresh(result);
    return result;
  }

  /** Projects canonical prices and policy rates without invoking the execution adapter. */
  public FullFillPricingProjection project(
      ProductType productType,
      OrderSide side,
      FullFillExecutionPath executionPath,
      BigDecimal limitPrice,
      ExecutableMarketSnapshot snapshot
  ) {
    if (productType == null || side == null || executionPath == null || snapshot == null) {
      throw incomplete("Full-fill pricing projection is incomplete");
    }
    validateSnapshot(snapshot.platformSymbol(), productType, snapshot);
    BigDecimal referencePrice = side == OrderSide.BUY ? snapshot.ask() : snapshot.bid();
    BigDecimal slippage = executionPath.marketPricing()
        ? referencePrice.multiply(SLIPPAGE_RATE)
        : BigDecimal.ZERO;
    BigDecimal filledPrice = fillPrice(side, executionPath, limitPrice, referencePrice, slippage);
    LiquidityRole role = executionPath.liquidityRole();
    BigDecimal feeRate = role == LiquidityRole.MAKER ? MAKER_FEE_RATE : TAKER_FEE_RATE;
    return new FullFillPricingProjection(
        filledPrice,
        slippage,
        SLIPPAGE_RATE,
        feeRate,
        TAKER_FEE_RATE.max(MAKER_FEE_RATE),
        role);
  }

  /** Final strict gate used immediately before the first persistence mutation. */
  public void requireFresh(FullFillResult result) {
    if (result == null || result.filledAt() == null || result.expiresAt() == null) {
      throw incomplete("Canonical full-fill freshness metadata is incomplete");
    }
    if (!result.filledAt().isBefore(result.expiresAt())
        || !clock.instant().isBefore(result.expiresAt())) {
      throw new BusinessException(ErrorCode.MARKET_DATA_STALE, "Executable market snapshot expired");
    }
  }

  /** Final strict gate for pending-order writes that do not yet have a fill result. */
  public void requireFresh(ExecutableMarketSnapshot snapshot) {
    if (snapshot == null || snapshot.asOf() == null || snapshot.expiresAt() == null) {
      throw incomplete("Executable market snapshot freshness metadata is incomplete");
    }
    if (snapshot.asOf().isAfter(snapshot.expiresAt())
        || !clock.instant().isBefore(snapshot.expiresAt())) {
      throw new BusinessException(ErrorCode.MARKET_DATA_STALE, "Executable market snapshot expired");
    }
  }

  private BigDecimal fillPrice(
      OrderSide side,
      FullFillExecutionPath executionPath,
      BigDecimal limitPrice,
      BigDecimal referencePrice,
      BigDecimal slippage
  ) {
    if (executionPath.marketPricing()) {
      return side == OrderSide.BUY
          ? referencePrice.add(slippage)
          : referencePrice.subtract(slippage);
    }
    requirePositive(limitPrice, "Limit price is required for limit execution");
    return side == OrderSide.BUY
        ? referencePrice.min(limitPrice)
        : referencePrice.max(limitPrice);
  }

  private BigDecimal fee(FullFillRequest request, BigDecimal filledPrice, BigDecimal feeRate) {
    if (request.productType() == ProductType.CRYPTO_SPOT && request.side() == OrderSide.BUY) {
      return request.requestedBaseQuantity().multiply(feeRate);
    }
    return request.requestedBaseQuantity().multiply(filledPrice).multiply(feeRate);
  }

  private String feeAsset(FullFillRequest request) {
    if (request.productType() == ProductType.LINEAR_PERP || request.side() == OrderSide.SELL) {
      return "USDT";
    }
    String symbol = normalize(request.platformSymbol());
    if (!symbol.endsWith("USDT") || symbol.length() <= 4) {
      throw incomplete("Spot symbol does not expose a USDT base asset");
    }
    return symbol.substring(0, symbol.length() - 4);
  }

  private void validateRequest(FullFillRequest request) {
    if (request == null
        || request.executionIntent() == null
        || request.productType() == null
        || request.side() == null
        || request.executionPath() == null
        || normalize(request.platformSymbol()).isBlank()) {
      throw incomplete("Full-fill request is incomplete");
    }
    requirePositive(request.requestedBaseQuantity(), "Requested base quantity must be positive");
    OrderType expectedOrderType = switch (request.executionPath()) {
      case MARKET -> OrderType.MARKET;
      case IMMEDIATE_LIMIT, RESTING_LIMIT -> OrderType.LIMIT;
      case TRIGGERED_STOP_MARKET -> OrderType.STOP_MARKET;
    };
    if (!normalize(request.platformSymbol()).equals(normalize(request.executionIntent().symbol()))
        || request.side() != request.executionIntent().side()
        || request.executionIntent().orderType() != expectedOrderType
        || request.executionIntent().quantity() == null
        || request.executionIntent().quantity().compareTo(request.requestedBaseQuantity()) != 0) {
      throw incomplete("Execution intent does not match the canonical full-fill request");
    }
    if ((request.executionPath() == FullFillExecutionPath.IMMEDIATE_LIMIT
        || request.executionPath() == FullFillExecutionPath.RESTING_LIMIT)
        && (request.limitPrice() == null
        || request.executionIntent().price() == null
        || request.executionIntent().price().compareTo(request.limitPrice()) != 0)) {
      throw incomplete("Limit execution intent does not match the canonical limit price");
    }
  }

  private void validateSnapshot(FullFillRequest request, ExecutableMarketSnapshot snapshot) {
    validateSnapshot(request.platformSymbol(), request.productType(), snapshot);
  }

  private void validateSnapshot(
      String platformSymbol,
      ProductType productType,
      ExecutableMarketSnapshot snapshot
  ) {
    if (snapshot == null
        || !normalize(platformSymbol).equals(normalize(snapshot.platformSymbol()))
        || productType != snapshot.productType()
        || blank(snapshot.providerCode())
        || blank(snapshot.providerSymbol())
        || snapshot.sourceMode() == null
        || snapshot.asOf() == null
        || snapshot.expiresAt() == null) {
      throw incomplete("Executable market snapshot does not match the request");
    }
    requirePositive(snapshot.bid(), "Executable bid is missing");
    requirePositive(snapshot.ask(), "Executable ask is missing");
    requirePositive(snapshot.last(), "Executable last is missing");
    if (productType == ProductType.LINEAR_PERP) {
      requirePositive(snapshot.mark(), "Perpetual mark price is missing");
      requirePositive(snapshot.index(), "Perpetual index price is missing");
    }
    Instant now = clock.instant();
    if (!now.isBefore(snapshot.expiresAt())) {
      throw new BusinessException(ErrorCode.MARKET_DATA_STALE, "Executable market snapshot expired");
    }
    if (snapshot.asOf().isAfter(snapshot.expiresAt())) {
      throw incomplete("Executable market snapshot timestamps are inconsistent");
    }
  }

  private void requireFullIntent(BigDecimal requestedQuantity, ExecutionResult intent) {
    if (intent == null
        || intent.filledQuantity() == null
        || intent.remainingQuantity() == null
        || intent.filledQuantity().compareTo(requestedQuantity) != 0
        || intent.remainingQuantity().compareTo(BigDecimal.ZERO) != 0) {
      throw new BusinessException(
          ErrorCode.PARTIAL_FILL_NOT_SUPPORTED,
          "Demo execution supports one full fill only");
    }
  }

  private void requirePositive(BigDecimal value, String message) {
    if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
      throw incomplete(message);
    }
  }

  private static BusinessException incomplete(String message) {
    return new BusinessException(ErrorCode.MARKET_BUNDLE_INCOMPLETE, message);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? "" : SymbolNormalizer.normalize(value.trim());
  }
}
