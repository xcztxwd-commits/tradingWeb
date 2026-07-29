package com.fxplatform.risk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.execution.DemoMatchFill;
import com.fxplatform.market.entity.ProviderInstrumentEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketDataCapability;
import com.fxplatform.market.provider.ProviderResolver;
import com.fxplatform.market.repository.ProviderInstrumentRepository;
import com.fxplatform.market.repository.SymbolProviderBindingRepository;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.SymbolProductTypes;
import com.fxplatform.risk.entity.RiskConfigEntity;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.model.InstrumentProfile;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.repository.RiskConfigRepository;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.enums.OrderType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InstrumentRulesEngine {

  private final SymbolRepository symbolRepository;
  private final ProviderResolver providerResolver;
  private final SymbolProviderBindingRepository bindingRepository;
  private final ProviderInstrumentRepository providerInstrumentRepository;
  private final RiskConfigRepository riskConfigRepository;
  private final ObjectMapper objectMapper;
  private final TradingInstrumentClassifier instrumentClassifier;

  @Value("${market.demo-quotes.enabled:false}")
  private boolean demoQuotesEnabled = false;

  public InstrumentRules rules(String symbolCode) {
    String normalizedSymbol = normalizeSymbol(symbolCode);
    Optional<SymbolEntity> maybeSymbol = symbolRepository.findBySymbol(normalizedSymbol);
    if (maybeSymbol.isEmpty()) {
      return InstrumentRules.missing(normalizedSymbol);
    }

    return rules(maybeSymbol.get());
  }

  public InstrumentRules rules(SymbolEntity symbol) {
    InstrumentProfile profile = instrumentClassifier.profile(symbol);
    ProviderRules providerRules = providerRules(symbol);
    Optional<RiskConfigEntity> riskConfig = riskConfig(symbol.getSymbol());
    ProductType productType = SymbolProductTypes.readOrLegacy(symbol);
    boolean enabled = Boolean.TRUE.equals(symbol.getEnabled());
    boolean tradable = Boolean.TRUE.equals(symbol.getTradable());
    boolean quoteEnabled = enabled && Boolean.TRUE.equals(symbol.getQuoteEnabled())
        && (demoQuotesEnabled || canResolve(symbol, MarketDataCapability.QUOTE));
    boolean chartEnabled = enabled && Boolean.TRUE.equals(symbol.getChartEnabled())
        && canResolve(symbol, MarketDataCapability.CANDLES);
    boolean orderBookEnabled = enabled && Boolean.TRUE.equals(symbol.getOrderBookEnabled())
        && canResolve(symbol, MarketDataCapability.ORDER_BOOK);
    BigDecimal maxLots = riskConfig
        .map(RiskConfigEntity::getMaxLots)
        .orElse(firstPositive(providerRules.maxQty(), symbol.getMaxLot(), BigDecimal.ZERO));

    return new InstrumentRules(
        symbol.getSymbol(),
        true,
        enabled,
        tradable,
        quoteEnabled,
        chartEnabled,
        orderBookEnabled,
        enabled && tradable,
        productType,
        firstPositive(providerRules.tickSize(), symbol.getTickSize(), BigDecimal.ONE),
        firstPositive(providerRules.stepSize(), symbol.getMinLot(), BigDecimal.ONE),
        firstPositive(providerRules.minQty(), symbol.getMinLot(), BigDecimal.ZERO),
        maxLots,
        firstPositiveOrNull(providerRules.minNotional()),
        firstPositiveOrNull(providerRules.maxNotional()),
        firstPositive(providerRules.minQty(), symbol.getMinLot(), BigDecimal.ZERO),
        maxLots,
        riskConfig.map(RiskConfigEntity::getMaxLeverage).orElse(positiveOrDefault(symbol.getLeverage(), 1)),
        profile.kind() == InstrumentKind.SPOT ? 1 : positiveOrDefault(symbol.getLeverage(), 1),
        asset(symbol.getMarginAsset(), symbol.getQuoteCurrency()),
        asset(symbol.getSettlementAsset(), symbol.getQuoteCurrency()),
        firstPositive(symbol.getContractSize(), profile.contractSize(), BigDecimal.ONE),
        firstPositive(symbol.getContractMultiplier(), profile.contractMultiplier(), BigDecimal.ONE),
        "DEFAULT",
        "ALWAYS",
        "NONE",
        "NORMAL");
  }

  public List<InstrumentRules> rules(List<String> symbolCodes) {
    return symbolCodes.stream()
        .map(this::rules)
        .toList();
  }

  public void validateOrderRules(CreateOrderRequest request, SymbolEntity symbol) {
    validateOrderRules(request, symbol, request.quantity());
  }

  private void validateOrderRules(
      CreateOrderRequest request,
      SymbolEntity symbol,
      BigDecimal canonicalBaseQuantity
  ) {
    InstrumentRules rules = rules(symbol);
    if (!rules.enabled()) {
      throw new BusinessException("SYMBOL_NOT_ENABLED", "Symbol is disabled");
    }
    if (!rules.tradable() || !rules.orderEnabled()) {
      throw new BusinessException(ErrorCode.SYMBOL_NOT_TRADABLE, "Symbol is not tradable");
    }
    if (request.orderType() == OrderType.MARKET && !rules.quoteEnabled()) {
      throw new BusinessException(ErrorCode.SYMBOL_NOT_TRADABLE, "Quote capability is unavailable");
    }

    BigDecimal quantity = canonicalBaseQuantity;
    if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException("BAD_QUANTITY", "Quantity must be greater than zero");
    }
    if (rules.stepSize() != null && !isMultiple(quantity, rules.stepSize())) {
      throw new BusinessException("QUANTITY_STEP_MISMATCH", "Quantity does not match step size");
    }
    if (rules.minQty() != null && rules.minQty().compareTo(BigDecimal.ZERO) > 0
        && quantity.compareTo(rules.minQty()) < 0) {
      throw new BusinessException("QUANTITY_TOO_SMALL", "Quantity is below minimum");
    }
    if (rules.maxQty() != null && rules.maxQty().compareTo(BigDecimal.ZERO) > 0
        && quantity.compareTo(rules.maxQty()) > 0) {
      throw new BusinessException("QUANTITY_TOO_LARGE", "Quantity is above maximum");
    }
    BigDecimal priceForTick = requiresRequestedPrice(request.orderType())
        ? request.price()
        : null;
    if (priceForTick != null) {
      if (rules.tickSize() != null && !isMultiple(priceForTick, rules.tickSize())) {
        throw new BusinessException("PRICE_TICK_MISMATCH", "Price does not match tick size");
      }
    }
    BigDecimal triggerPriceForTick = request.orderType() == OrderType.STOP_MARKET
        || request.orderType() == OrderType.STOP_LIMIT
        ? request.triggerPrice()
        : null;
    if (triggerPriceForTick != null
        && rules.tickSize() != null
        && !isMultiple(triggerPriceForTick, rules.tickSize())) {
      throw new BusinessException(
          "PRICE_TICK_MISMATCH",
          "Trigger price does not match tick size");
    }
    if (request.leverage() != null && rules.maxLeverage() != null && request.leverage() > rules.maxLeverage()) {
      throw new BusinessException("MAX_LEVERAGE_EXCEEDED", "Leverage is above maximum");
    }
  }

  public void validateOrderNotional(CreateOrderRequest request, SymbolEntity symbol, BigDecimal referencePrice) {
    validateOrderNotional(request, symbol, request.quantity(), referencePrice);
  }

  /** Validates aggregated instrument rules only after public quantity is canonical BASE. */
  public void validateCanonicalOrder(
      CreateOrderRequest request,
      SymbolEntity symbol,
      BigDecimal canonicalBaseQuantity,
      BigDecimal referencePrice
  ) {
    validateOrderRules(request, symbol, canonicalBaseQuantity);
    validateOrderNotional(request, symbol, canonicalBaseQuantity, referencePrice);
  }

  /** Validates the exact current DEPTH fills plus the sealed conservative future tail. */
  public void validateCanonicalDepthExecution(
      SymbolEntity symbol,
      List<DemoMatchFill> fills,
      BigDecimal remainingBaseQuantity,
      BigDecimal remainingReferencePrice
  ) {
    validateCanonicalDepthExecution(
        symbol,
        symbol == null ? null : rules(symbol),
        fills,
        remainingBaseQuantity,
        remainingReferencePrice);
  }

  /** Validates a DEPTH execution against one caller-sealed instrument-rules snapshot. */
  public void validateCanonicalDepthExecution(
      SymbolEntity symbol,
      InstrumentRules rules,
      List<DemoMatchFill> fills,
      BigDecimal remainingBaseQuantity,
      BigDecimal remainingReferencePrice
  ) {
    if (symbol == null || fills == null
        || remainingBaseQuantity == null || remainingBaseQuantity.signum() < 0) {
      throw new BusinessException(
          "INVALID_INSTRUMENT_RULES",
          "Canonical DEPTH execution authority is incomplete");
    }
    validateDepthRulesSnapshot(symbol, rules);
    BigDecimal canonicalUnitSize = canonicalUnitSize(symbol);
    BigDecimal totalNotional = canonicalDepthFillNotional(fills, rules, canonicalUnitSize);
    if (remainingBaseQuantity.signum() > 0) {
      if (remainingReferencePrice == null || remainingReferencePrice.signum() <= 0) {
        throw new BusinessException(
            "INVALID_INSTRUMENT_RULES",
            "Canonical DEPTH tail price is required");
      }
      validateExecutionPriceTick(rules, remainingReferencePrice);
      totalNotional = totalNotional.add(
          remainingBaseQuantity.multiply(remainingReferencePrice).multiply(canonicalUnitSize));
    }
    if (rules.minNotional() != null
        && totalNotional.compareTo(rules.minNotional()) < 0) {
      throw new BusinessException(
          ErrorCode.ORDER_NOTIONAL_TOO_SMALL,
          "DEPTH execution notional is below minimum");
    }
    if (rules.maxNotional() != null
        && totalNotional.compareTo(rules.maxNotional()) > 0) {
      throw new BusinessException(
          "ORDER_NOTIONAL_TOO_LARGE",
          "DEPTH execution notional is above maximum");
    }
  }

  /** Validates only fills executable in the current DEPTH Tick. */
  public void validateCanonicalDepthFills(
      SymbolEntity symbol,
      List<DemoMatchFill> fills
  ) {
    validateCanonicalDepthFills(
        symbol,
        symbol == null ? null : rules(symbol),
        fills);
  }

  /** Validates current fills against the same rules snapshot used for quantity-step checks. */
  public void validateCanonicalDepthFills(
      SymbolEntity symbol,
      InstrumentRules rules,
      List<DemoMatchFill> fills
  ) {
    if (symbol == null || fills == null) {
      throw new BusinessException(
          "INVALID_INSTRUMENT_RULES",
          "Canonical DEPTH fill authority is incomplete");
    }
    validateDepthExecutionAuthority(symbol, rules);
    BigDecimal currentFillNotional = canonicalDepthFillNotional(
        fills,
        rules,
        canonicalUnitSize(symbol));
    if (rules.maxNotional() != null
        && currentFillNotional.compareTo(rules.maxNotional()) > 0) {
      throw new BusinessException(
          "ORDER_NOTIONAL_TOO_LARGE",
          "Current DEPTH fill notional is above maximum");
    }
  }

  /** Validates the caller-sealed DEPTH instrument authority before matching can run. */
  public void validateDepthExecutionAuthority(SymbolEntity symbol, InstrumentRules rules) {
    if (symbol == null) {
      throw new BusinessException(
          "INVALID_INSTRUMENT_RULES",
          "Canonical DEPTH instrument authority is incomplete");
    }
    validateDepthRulesSnapshot(symbol, rules);
  }

  private void validateDepthRulesSnapshot(SymbolEntity symbol, InstrumentRules rules) {
    if (rules == null
        || !normalizeSymbol(symbol.getSymbol()).equals(normalizeSymbol(rules.symbol()))
        || rules.productType() != SymbolProductTypes.readOrLegacy(symbol)) {
      throw new BusinessException(
          "INVALID_INSTRUMENT_RULES",
          "Canonical DEPTH instrument-rules snapshot is inconsistent");
    }
    if (!rules.exists() || !rules.enabled() || !rules.tradable() || !rules.orderEnabled()) {
      throw new BusinessException(
          ErrorCode.SYMBOL_NOT_TRADABLE,
          "Symbol is not currently tradable for DEPTH execution");
    }
  }

  private BigDecimal canonicalDepthFillNotional(
      List<DemoMatchFill> fills,
      InstrumentRules rules,
      BigDecimal canonicalUnitSize
  ) {
    BigDecimal totalNotional = BigDecimal.ZERO;
    for (DemoMatchFill fill : fills) {
      if (fill == null
          || fill.quantity() == null || fill.quantity().signum() <= 0
          || fill.price() == null || fill.price().signum() <= 0) {
        throw new BusinessException(
            "INVALID_INSTRUMENT_RULES",
            "Canonical DEPTH fill is incomplete");
      }
      validateExecutionPriceTick(rules, fill.price());
      totalNotional = totalNotional.add(
          fill.quantity().multiply(fill.price()).multiply(canonicalUnitSize));
    }
    return totalNotional;
  }

  private BigDecimal canonicalUnitSize(SymbolEntity symbol) {
    InstrumentProfile profile = instrumentClassifier.profile(symbol);
    return profile.kind() == InstrumentKind.LINEAR_PERPETUAL
        ? BigDecimal.ONE
        : profile.unitSize();
  }

  private void validateExecutionPriceTick(
      InstrumentRules rules,
      BigDecimal price
  ) {
    if (rules.tickSize() != null && !isMultiple(price, rules.tickSize())) {
      throw new BusinessException(
          ErrorCode.PRICE_TICK_MISMATCH,
          "DEPTH execution price does not match tick size");
    }
  }

  private void validateOrderNotional(
      CreateOrderRequest request,
      SymbolEntity symbol,
      BigDecimal canonicalBaseQuantity,
      BigDecimal referencePrice
  ) {
    InstrumentRules rules = rules(symbol);
    BigDecimal price = request.orderType() == OrderType.MARKET
        || request.orderType() == OrderType.STOP_MARKET
        ? referencePrice
        : request.price();
    if (canonicalBaseQuantity == null || price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    InstrumentProfile profile = instrumentClassifier.profile(symbol);
    BigDecimal canonicalUnitSize = profile.kind() == InstrumentKind.LINEAR_PERPETUAL
        ? BigDecimal.ONE
        : profile.unitSize();
    BigDecimal notional = canonicalBaseQuantity.multiply(price).multiply(canonicalUnitSize);
    if (rules.minNotional() != null && notional.compareTo(rules.minNotional()) < 0) {
      throw new BusinessException("ORDER_NOTIONAL_TOO_SMALL", "Order notional is below minimum");
    }
    if (rules.maxNotional() != null && notional.compareTo(rules.maxNotional()) > 0) {
      throw new BusinessException("ORDER_NOTIONAL_TOO_LARGE", "Order notional is above maximum");
    }
  }

  private ProviderRules providerRules(SymbolEntity symbol) {
    List<com.fxplatform.market.entity.SymbolProviderBindingEntity> bindings =
        bindingRepository.findEnabledBySymbolIdOrderByPriority(symbol.getId());
    if (bindings == null) {
      return ProviderRules.empty();
    }
    return bindings.stream()
        .filter(binding -> binding.getProviderInstrumentId() != null)
        .findFirst()
        .flatMap(binding -> providerInstrumentRepository.findById(binding.getProviderInstrumentId()))
        .map(this::providerRules)
        .orElse(ProviderRules.empty());
  }

  private ProviderRules providerRules(ProviderInstrumentEntity instrument) {
    try {
      JsonNode rules = objectMapper.readTree(instrument.getRawJson()).path("rules");
      return new ProviderRules(
          decimal(rules, "tickSize"),
          decimal(rules, "stepSize"),
          decimal(rules, "minQty", "minLot"),
          decimal(rules, "maxQty", "maxLot"),
          decimal(rules, "minNotional"),
          decimal(rules, "maxNotional"));
    } catch (RuntimeException ex) {
      return ProviderRules.empty();
    } catch (Exception ex) {
      return ProviderRules.empty();
    }
  }

  private BigDecimal decimal(JsonNode node, String... keys) {
    for (String key : keys) {
      String value = node.path(key).asText("");
      if (!value.isBlank()) {
        return new BigDecimal(value);
      }
    }
    return null;
  }

  private boolean requiresRequestedPrice(OrderType orderType) {
    return orderType == OrderType.LIMIT
        || orderType == OrderType.STOP
        || orderType == OrderType.STOP_LIMIT;
  }

  private boolean isMultiple(BigDecimal value, BigDecimal step) {
    if (step.compareTo(BigDecimal.ZERO) <= 0) {
      return true;
    }
    return value.remainder(step).compareTo(BigDecimal.ZERO) == 0;
  }

  private BigDecimal firstPositive(BigDecimal... values) {
    for (BigDecimal value : values) {
      if (value != null && value.compareTo(BigDecimal.ZERO) > 0) {
        return value;
      }
    }
    return BigDecimal.ZERO;
  }

  private BigDecimal firstPositiveOrNull(BigDecimal... values) {
    for (BigDecimal value : values) {
      if (value != null && value.compareTo(BigDecimal.ZERO) > 0) {
        return value;
      }
    }
    return null;
  }

  private Optional<RiskConfigEntity> riskConfig(String symbol) {
    Optional<RiskConfigEntity> result = riskConfigRepository.findEnabledBySymbolOrGlobal(normalizeSymbol(symbol));
    return result == null ? Optional.empty() : result;
  }

  private boolean canResolve(SymbolEntity symbol, MarketDataCapability capability) {
    try {
      return providerResolver.canResolve(symbol, capability);
    } catch (BusinessException ex) {
      return false;
    }
  }

  private int positiveOrDefault(Integer value, int fallback) {
    return value == null || value <= 0 ? fallback : value;
  }

  private String asset(String preferred, String fallback) {
    String value = normalize(preferred);
    return value.isBlank() ? normalize(fallback) : value;
  }

  private String normalizeSymbol(String symbol) {
    return SymbolNormalizer.normalize(normalize(symbol));
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase();
  }

  private record ProviderRules(
      BigDecimal tickSize,
      BigDecimal stepSize,
      BigDecimal minQty,
      BigDecimal maxQty,
      BigDecimal minNotional,
      BigDecimal maxNotional
  ) {
    private static ProviderRules empty() {
      return new ProviderRules(null, null, null, null, null, null);
    }
  }
}
