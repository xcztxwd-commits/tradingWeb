package com.fxplatform.tradinglab.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fxplatform.execution.ExecutionProperties;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.SymbolProductTypes;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.tradinglab.admin.dto.TradingLabConfigResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(
    readOnly = true,
    isolation = Isolation.REPEATABLE_READ,
    propagation = Propagation.REQUIRES_NEW)
public class DefaultTradingLabConfigProvider implements TradingLabConfigProvider {

  private static final Set<ProductType> SUPPORTED = Set.of(
      ProductType.CRYPTO_SPOT,
      ProductType.LINEAR_PERP);

  private final SymbolRepository symbols;
  private final InstrumentRulesEngine rulesEngine;
  private final ExecutionProperties execution;
  private final TradingLabScenarioCanonicalizer canonicalizer;
  private final ObjectMapper json;
  private final Environment environment;

  @Override
  public TradingLabConfigResponse current() {
    String modelVersion = property("trading-lab.model-version", "trading-lab-model-v1");
    String codeVersion = codeVersion();
    ArrayNode instruments = json.createArrayNode();
    List<SymbolEntity> enabled = symbols.findByEnabledTrueOrderBySymbolAsc();
    for (SymbolEntity symbol : enabled) {
      ProductType productType = SymbolProductTypes.readOrLegacy(symbol);
      if (!SUPPORTED.contains(productType) || !Boolean.TRUE.equals(symbol.getTradable())) {
        continue;
      }
      InstrumentRules rules = rulesEngine.rules(symbol);
      requireAuthoritativeRules(symbol, productType, rules);
      instruments.add(instrument(symbol, rules));
    }

    ObjectNode symbolDocument = json.createObjectNode();
    symbolDocument.set("instruments", instruments.deepCopy());
    String symbolConfigVersion = canonicalizer.canonicalize(symbolDocument).sha256();

    ObjectNode snapshot = json.createObjectNode();
    snapshot.put("modelVersion", modelVersion);
    snapshot.put("symbolConfigVersion", symbolConfigVersion);
    snapshot.put("codeVersion", codeVersion);
    snapshot.set("executionPolicy", executionPolicy());
    snapshot.set("instruments", instruments);
    TradingLabCanonicalDocument canonical = canonicalizer.canonicalize(snapshot);
    return new TradingLabConfigResponse(
        canonical.value(),
        canonical.sha256(),
        modelVersion,
        symbolConfigVersion,
        codeVersion);
  }

  private ObjectNode instrument(SymbolEntity symbol, InstrumentRules rules) {
    ObjectNode value = json.createObjectNode();
    value.put("symbol", rules.symbol());
    value.put("productType", rules.productType().name());
    value.put("baseAsset", symbol.getBaseCurrency());
    value.put("quoteAsset", symbol.getQuoteCurrency());
    decimal(value, "tickSize", rules.tickSize());
    decimal(value, "stepSize", rules.stepSize());
    value.put("pricePrecision", precision(rules.tickSize()));
    value.put("quantityPrecision", precision(rules.stepSize()));
    decimal(value, "minQty", rules.minQty());
    decimal(value, "maxQty", rules.maxQty());
    decimal(value, "minNotional", rules.minNotional());
    decimal(value, "maxNotional", rules.maxNotional());
    decimal(
        value,
        "initialMarginRate",
        BigDecimal.ONE.divide(
            BigDecimal.valueOf(rules.maxLeverage()),
            18,
            RoundingMode.HALF_UP));
    decimal(value, "maintenanceMarginRate", symbol.getMaintenanceMarginRate());
    decimal(value, "liquidationFeeRate", symbol.getLiquidationFeeRate());
    decimal(value, "fixedFundingRate", symbol.getFixedFundingRate());
    if (symbol.getFixedFundingIntervalMinutes() == null
        || symbol.getFixedFundingIntervalMinutes() < 1) {
      throw new IllegalStateException("Trading Lab funding interval is invalid");
    }
    value.put("fixedFundingIntervalMinutes", symbol.getFixedFundingIntervalMinutes());
    value.put("markPriceSource", symbol.getMarkPriceSource());
    decimal(value, "contractSize", rules.contractSize());
    value.put("maxLeverage", rules.maxLeverage());
    value.put("defaultLeverage", rules.defaultLeverage());
    value.put("marginAsset", rules.marginAsset());
    value.put("settlementAsset", rules.settlementAsset());
    value.put("riskTier", rules.riskTier());
    return value;
  }

  private ObjectNode executionPolicy() {
    ExecutionProperties.DemoProperties demo = execution.getDemo();
    ObjectNode value = json.createObjectNode();
    value.put("matchingMode", demo.getMatchingMode().name());
    decimal(value, "makerFeeRate", demo.getMakerFeeRate());
    decimal(value, "takerFeeRate", demo.getTakerFeeRate());
    decimal(value, "liquidationFeeRate", demo.getLiquidationFeeRate());
    decimal(value, "slippageRate", demo.getSlippageRate());
    decimal(value, "maxFillQuantityPerTick", demo.getMaxFillQuantityPerTick());
    return value;
  }

  private static void requireAuthoritativeRules(
      SymbolEntity symbol,
      ProductType productType,
      InstrumentRules rules
  ) {
    if (rules == null
        || !rules.exists()
        || !rules.enabled()
        || !rules.tradable()
        || !rules.orderEnabled()
        || symbol.getSymbol() == null
        || !symbol.getSymbol().equals(rules.symbol())
        || productType != rules.productType()
        || symbol.getBaseCurrency() == null
        || symbol.getBaseCurrency().isBlank()
        || symbol.getQuoteCurrency() == null
        || symbol.getQuoteCurrency().isBlank()
        || rules.tickSize() == null
        || rules.tickSize().signum() <= 0
        || rules.stepSize() == null
        || rules.stepSize().signum() <= 0
        || rules.maxLeverage() == null
        || rules.maxLeverage() < 1
        || rules.defaultLeverage() == null
        || rules.defaultLeverage() < 1
        || rules.defaultLeverage() > rules.maxLeverage()
        || rules.marginAsset() == null
        || rules.marginAsset().isBlank()
        || rules.settlementAsset() == null
        || rules.settlementAsset().isBlank()
        || rules.contractSize() == null
        || rules.contractSize().signum() <= 0
        || symbol.getFixedFundingRate() == null
        || symbol.getMarkPriceSource() == null
        || symbol.getMarkPriceSource().isBlank()) {
      throw new IllegalStateException(
          "Trading Lab instrument rules are incomplete or inconsistent");
    }
  }

  private String codeVersion() {
    String explicit = firstNonBlank(
        environment.getProperty("TRADING_LAB_CODE_VERSION"),
        environment.getProperty("trading-lab.code-version"));
    if (explicit != null) {
      return explicit;
    }
    String build = firstNonBlank(
        environment.getProperty("build.version"),
        environment.getProperty("info.build.version"));
    return (build == null ? "local" : build) + "+working-tree";
  }

  private String property(String name, String fallback) {
    String value = firstNonBlank(environment.getProperty(name));
    return value == null ? fallback : value;
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }

  private static void decimal(ObjectNode target, String field, BigDecimal value) {
    if (value == null) {
      target.putNull(field);
      return;
    }
    BigDecimal normalized = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    target.put(field, normalized.toPlainString());
  }

  private static int precision(BigDecimal value) {
    return value == null ? 0 : Math.max(0, value.stripTrailingZeros().scale());
  }
}
