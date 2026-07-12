package com.fxplatform.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.market.entity.ProviderInstrumentEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.entity.SymbolProviderBindingEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketDataCapability;
import com.fxplatform.market.provider.ProviderResolver;
import com.fxplatform.market.repository.ProviderInstrumentRepository;
import com.fxplatform.market.repository.SymbolProviderBindingRepository;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.entity.RiskConfigEntity;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.repository.RiskConfigRepository;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InstrumentRulesEngineTest {

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private ProviderResolver providerResolver;

  @Mock
  private SymbolProviderBindingRepository bindingRepository;

  @Mock
  private ProviderInstrumentRepository providerInstrumentRepository;

  @Mock
  private RiskConfigRepository riskConfigRepository;

  @Test
  void rulesMergeSymbolProviderMetadataAndRiskLimits() {
    SymbolEntity symbol = cryptoSpotSymbol();
    SymbolProviderBindingEntity binding = binding(symbol.getId(), UUID.randomUUID(), UUID.randomUUID(), "BTCUSDT");
    ProviderInstrumentEntity instrument = providerInstrument(
        binding.getProviderInstrumentId(),
        binding.getProviderId(),
        "BTCUSDT",
        """
        {"rules":{"tickSize":"0.01000000","stepSize":"0.00001000","minLot":"0.00001000","maxLot":"9000.00000000","minNotional":"5.00000000","maxNotional":"1000000.00000000"}}
        """);
    RiskConfigEntity riskConfig = riskConfig("BTCUSDT", 12, "10.0000");

    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
    when(providerResolver.canResolve(symbol, MarketDataCapability.QUOTE)).thenReturn(true);
    when(providerResolver.canResolve(symbol, MarketDataCapability.CANDLES)).thenReturn(false);
    when(providerResolver.canResolve(symbol, MarketDataCapability.ORDER_BOOK)).thenReturn(true);
    when(bindingRepository.findEnabledBySymbolIdOrderByPriority(symbol.getId())).thenReturn(List.of(binding));
    when(providerInstrumentRepository.findById(binding.getProviderInstrumentId())).thenReturn(Optional.of(instrument));
    when(riskConfigRepository.findEnabledBySymbolOrGlobal("BTCUSDT")).thenReturn(Optional.of(riskConfig));

    InstrumentRules rules = engine().rules("btcusdt");

    assertThat(rules.symbol()).isEqualTo("BTCUSDT");
    assertThat(rules.exists()).isTrue();
    assertThat(rules.enabled()).isTrue();
    assertThat(rules.tradable()).isTrue();
    assertThat(rules.quoteEnabled()).isTrue();
    assertThat(rules.chartEnabled()).isFalse();
    assertThat(rules.orderBookEnabled()).isTrue();
    assertThat(rules.orderEnabled()).isTrue();
    assertThat(rules.productType()).isEqualTo(ProductType.CRYPTO_SPOT);
    assertThat(rules.tickSize()).isEqualByComparingTo("0.01000000");
    assertThat(rules.stepSize()).isEqualByComparingTo("0.00001000");
    assertThat(rules.minQty()).isEqualByComparingTo("0.00001000");
    assertThat(rules.maxQty()).isEqualByComparingTo("10.0000");
    assertThat(rules.minNotional()).isEqualByComparingTo("5.00000000");
    assertThat(rules.maxNotional()).isEqualByComparingTo("1000000.00000000");
    assertThat(rules.maxLeverage()).isEqualTo(12);
    assertThat(rules.defaultLeverage()).isEqualTo(1);
    assertThat(rules.marginAsset()).isEqualTo("USDT");
    assertThat(rules.settlementAsset()).isEqualTo("USDT");
    assertThat(rules.contractSize()).isEqualByComparingTo("1");
    assertThat(rules.riskTier()).isEqualTo("DEFAULT");
    assertThat(rules.tradingSession()).isEqualTo("ALWAYS");
    assertThat(rules.kycRequirement()).isEqualTo("NONE");
    assertThat(rules.userRiskLevelRestriction()).isEqualTo("NORMAL");
  }

  @Test
  void validateOrderRulesRejectsDisabledTradableAndPrecisionViolations() {
    SymbolEntity symbol = cryptoSpotSymbol();
    symbol.setTradable(false);
    when(providerResolver.canResolve(symbol, MarketDataCapability.QUOTE)).thenReturn(true);

    assertThatThrownBy(() -> engine().validateOrderRules(limitOrder("BTCUSDT", "0.01000000", "65000.01000000"), symbol))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Symbol is not tradable");

    symbol.setTradable(true);
    symbol.setTickSize(new BigDecimal("0.10"));
    symbol.setLotSize(new BigDecimal("0.001"));
    symbol.setMinLot(new BigDecimal("0.001"));
    symbol.setMaxLot(new BigDecimal("1"));

    assertThatThrownBy(() -> engine().validateOrderRules(limitOrder("BTCUSDT", "0.0105", "65000.10"), symbol))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Quantity does not match step size");

    assertThatThrownBy(() -> engine().validateOrderRules(limitOrder("BTCUSDT", "0.010", "65000.15"), symbol))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Price does not match tick size");
  }

  @Test
  void demoQuoteFallbackMakesMarketOrdersTradableWithoutProviderResolution() {
    SymbolEntity symbol = cryptoSpotSymbol();
    symbol.setLotSize(new BigDecimal("0.00001"));
    InstrumentRulesEngine engine = engine();
    ReflectionTestUtils.setField(engine, "demoQuotesEnabled", true);

    assertThat(engine.rules(symbol).quoteEnabled()).isTrue();
    assertThatCode(() -> engine.validateOrderRules(marketOrder("BTCUSDT", "0.01000000"), symbol))
        .doesNotThrowAnyException();
  }

  @Test
  void validateOrderRulesRejectsMarketOrderWithoutQuoteUsingStandardCode() {
    SymbolEntity symbol = cryptoSpotSymbol();

    assertThatThrownBy(() -> engine().validateOrderRules(marketOrder("BTCUSDT", "0.01000000"), symbol))
        .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.SYMBOL_NOT_TRADABLE))
        .hasMessageContaining("Quote capability is unavailable");
  }

  @Test
  void validateOrderNotionalRejectsBelowMinimumNotional() {
    SymbolEntity symbol = cryptoSpotSymbol();
    symbol.setMinLot(new BigDecimal("0.0001"));
    SymbolProviderBindingEntity binding = binding(symbol.getId(), UUID.randomUUID(), UUID.randomUUID(), "BTCUSDT");
    ProviderInstrumentEntity instrument = providerInstrument(
        binding.getProviderInstrumentId(),
        binding.getProviderId(),
        "BTCUSDT",
        """
        {"rules":{"minNotional":"5.00000000"}}
        """);
    when(bindingRepository.findEnabledBySymbolIdOrderByPriority(symbol.getId())).thenReturn(List.of(binding));
    when(providerInstrumentRepository.findById(binding.getProviderInstrumentId())).thenReturn(Optional.of(instrument));

    assertThatThrownBy(() -> engine().validateOrderNotional(
        limitOrder("BTCUSDT", "0.0001", "10000.00"),
        symbol,
        new BigDecimal("10000.00")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Order notional is below minimum");
  }

  @Test
  void missingSymbolReturnsNonExistingRulesForReadApis() {
    when(symbolRepository.findBySymbol("UNKNOWN")).thenReturn(Optional.empty());

    InstrumentRules rules = engine().rules("unknown");

    assertThat(rules.symbol()).isEqualTo("UNKNOWN");
    assertThat(rules.exists()).isFalse();
    assertThat(rules.enabled()).isFalse();
    assertThat(rules.orderEnabled()).isFalse();
  }

  @Test
  void usesSeededSpotMinLotAsStepFallbackWhenBindingHasNoProviderInstrument() {
    SymbolEntity symbol = cryptoSpotSymbol();
    symbol.setLotSize(BigDecimal.ONE);
    symbol.setMinLot(new BigDecimal("0.0001"));
    SymbolProviderBindingEntity seededBinding = binding(
        symbol.getId(), UUID.randomUUID(), null, "BTCUSDT");
    when(bindingRepository.findEnabledBySymbolIdOrderByPriority(symbol.getId()))
        .thenReturn(List.of(seededBinding));

    InstrumentRulesEngine engine = engine();
    InstrumentRules rules = engine.rules(symbol);

    assertThat(rules.stepSize()).isEqualByComparingTo("0.0001");
    assertThat(rules.minQty()).isEqualByComparingTo("0.0001");
    assertThatCode(() -> engine.validateCanonicalOrder(
        limitOrder("BTCUSDT", "0.1", "60000.00"),
        symbol,
        new BigDecimal("0.1"),
        new BigDecimal("60000.00")))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> engine.validateCanonicalOrder(
        limitOrder("BTCUSDT", "0.10005", "60000.00"),
        symbol,
        new BigDecimal("0.10005"),
        new BigDecimal("60000.00")))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("QUANTITY_STEP_MISMATCH"));
  }

  @Test
  void v47FiveSpotSymbolsUseMinLotFallbackWhenSeededBindingsHaveNoInstrumentId() {
    InstrumentRulesEngine engine = engine();
    java.util.Map<String, BigDecimal> expectedSteps = java.util.Map.of(
        "BTCUSDT", new BigDecimal("0.0001"),
        "ETHUSDT", new BigDecimal("0.0001"),
        "BNBUSDT", new BigDecimal("0.001"),
        "SOLUSDT", new BigDecimal("0.01"),
        "XRPUSDT", BigDecimal.ONE);

    expectedSteps.forEach((symbolCode, expectedStep) -> {
      SymbolEntity symbol = cryptoSpotSymbol();
      symbol.setId(UUID.randomUUID());
      symbol.setSymbol(symbolCode);
      symbol.setBaseCurrency(symbolCode.substring(0, symbolCode.length() - 4));
      symbol.setLotSize(BigDecimal.ONE);
      symbol.setMinLot(expectedStep);
      SymbolProviderBindingEntity seededBinding = binding(
          symbol.getId(), UUID.randomUUID(), null, symbolCode);
      when(bindingRepository.findEnabledBySymbolIdOrderByPriority(symbol.getId()))
          .thenReturn(List.of(seededBinding));

      assertThat(engine.rules(symbol).stepSize()).isEqualByComparingTo(expectedStep);
    });
  }

  @Test
  void validatesCanonicalBaseAfterQuoteBudgetConversion() {
    SymbolEntity symbol = cryptoSpotSymbol();
    symbol.setLotSize(new BigDecimal("0.0001"));
    symbol.setMinLot(new BigDecimal("0.0001"));
    InstrumentRulesEngine engine = engine();
    ReflectionTestUtils.setField(engine, "demoQuotesEnabled", true);
    CreateOrderRequest quoteBudget = new CreateOrderRequest(
        UUID.randomUUID(), "BTCUSDT", OrderSide.BUY, OrderType.MARKET,
        null, null, null, null, "quote-budget", "quote-budget",
        new BigDecimal("100"), null, 1, PositionSide.BOTH, QuantityUnit.QUOTE,
        MarginMode.CASH, null, null, false, List.of());

    assertThatCode(() -> engine.validateCanonicalOrder(
        quoteBudget, symbol, new BigDecimal("0.0019"), new BigDecimal("50005")))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> engine.validateCanonicalOrder(
        quoteBudget, symbol, new BigDecimal("0.00195"), new BigDecimal("50005")))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("QUANTITY_STEP_MISMATCH"));
  }

  @Test
  void stopMarketValidatesTriggerTickButUsesCanonicalExecutionPriceForNotional() {
    SymbolEntity symbol = cryptoSpotSymbol();
    symbol.setTickSize(new BigDecimal("0.10"));
    symbol.setLotSize(new BigDecimal("0.001"));
    symbol.setMinLot(new BigDecimal("0.001"));
    CreateOrderRequest badTrigger = stopMarketOrder("BTCUSDT", "0.100", "99.95");

    assertThatThrownBy(() -> engine().validateCanonicalOrder(
        badTrigger, symbol, new BigDecimal("0.100"), new BigDecimal("100.01")))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("PRICE_TICK_MISMATCH"));

    CreateOrderRequest validTrigger = stopMarketOrder("BTCUSDT", "0.100", "99.90");
    assertThatCode(() -> engine().validateCanonicalOrder(
        validTrigger, symbol, new BigDecimal("0.100"), new BigDecimal("100.01")))
        .doesNotThrowAnyException();
  }

  @Test
  void stopMarketAppliesMinNotionalToCanonicalBaseAtProjectedExecutionPrice() {
    SymbolEntity symbol = cryptoSpotSymbol();
    symbol.setLotSize(new BigDecimal("0.001"));
    symbol.setMinLot(new BigDecimal("0.001"));
    SymbolProviderBindingEntity binding = binding(
        symbol.getId(), UUID.randomUUID(), UUID.randomUUID(), "BTCUSDT");
    ProviderInstrumentEntity instrument = providerInstrument(
        binding.getProviderInstrumentId(), binding.getProviderId(), "BTCUSDT",
        """
        {"rules":{"minNotional":"5.00000000"}}
        """);
    when(bindingRepository.findEnabledBySymbolIdOrderByPriority(symbol.getId()))
        .thenReturn(List.of(binding));
    when(providerInstrumentRepository.findById(binding.getProviderInstrumentId()))
        .thenReturn(Optional.of(instrument));

    assertThatThrownBy(() -> engine().validateCanonicalOrder(
        stopMarketOrder("BTCUSDT", "0.010", "99.90"),
        symbol,
        new BigDecimal("0.010"),
        new BigDecimal("100.01")))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("ORDER_NOTIONAL_TOO_SMALL"));
  }

  private InstrumentRulesEngine engine() {
    return new InstrumentRulesEngine(
        symbolRepository,
        providerResolver,
        bindingRepository,
        providerInstrumentRepository,
        riskConfigRepository,
        new ObjectMapper(),
        new TradingInstrumentClassifier());
  }

  private SymbolEntity cryptoSpotSymbol() {
    SymbolEntity entity = new SymbolEntity();
    entity.setId(UUID.randomUUID());
    entity.setSymbol("BTCUSDT");
    entity.setDisplayName("BTC/USDT");
    entity.setAssetClass("CRYPTO");
    entity.setProductType(ProductType.CRYPTO_SPOT);
    entity.setBaseCurrency("BTC");
    entity.setQuoteCurrency("USDT");
    entity.setTickSize(new BigDecimal("0.01"));
    entity.setLotSize(BigDecimal.ONE);
    entity.setContractSize(BigDecimal.ONE);
    entity.setMinLot(new BigDecimal("0.00001"));
    entity.setMaxLot(new BigDecimal("9000"));
    entity.setLeverage(20);
    entity.setEnabled(true);
    entity.setTradable(true);
    entity.setQuoteEnabled(true);
    entity.setChartEnabled(true);
    entity.setOrderBookEnabled(true);
    return entity;
  }

  private SymbolProviderBindingEntity binding(UUID symbolId, UUID providerId, UUID instrumentId, String providerSymbol) {
    SymbolProviderBindingEntity binding = new SymbolProviderBindingEntity();
    binding.setSymbolId(symbolId);
    binding.setProviderId(providerId);
    binding.setProviderInstrumentId(instrumentId);
    binding.setProviderSymbol(providerSymbol);
    binding.setEnabled(true);
    return binding;
  }

  private ProviderInstrumentEntity providerInstrument(UUID id, UUID providerId, String providerSymbol, String rawJson) {
    ProviderInstrumentEntity instrument = new ProviderInstrumentEntity();
    instrument.setId(id);
    instrument.setProviderId(providerId);
    instrument.setProviderSymbol(providerSymbol);
    instrument.setRawJson(rawJson);
    return instrument;
  }

  private RiskConfigEntity riskConfig(String symbol, int maxLeverage, String maxLots) {
    RiskConfigEntity config = new RiskConfigEntity();
    config.setSymbol(symbol);
    config.setMaxLeverage(maxLeverage);
    config.setMaxLots(new BigDecimal(maxLots));
    config.setEnabled(true);
    return config;
  }

  private CreateOrderRequest limitOrder(String symbol, String quantity, String price) {
    return new CreateOrderRequest(
        UUID.randomUUID(),
        symbol,
        OrderSide.BUY,
        OrderType.LIMIT,
        new BigDecimal(quantity),
        new BigDecimal(price),
        null,
        null,
        "idem-" + symbol,
        "client-" + symbol,
        new BigDecimal(quantity),
        new BigDecimal(price),
        null);
  }

  private CreateOrderRequest marketOrder(String symbol, String quantity) {
    return new CreateOrderRequest(
        UUID.randomUUID(),
        symbol,
        OrderSide.BUY,
        OrderType.MARKET,
        new BigDecimal(quantity),
        null,
        null,
        null,
        "idem-" + symbol,
        "client-" + symbol,
        new BigDecimal(quantity),
        null,
        null);
  }

  private CreateOrderRequest stopMarketOrder(String symbol, String quantity, String triggerPrice) {
    return new CreateOrderRequest(
        UUID.randomUUID(), symbol, OrderSide.SELL, OrderType.STOP_MARKET,
        null, null, null, null, "idem-stop-" + symbol, "client-stop-" + symbol,
        new BigDecimal(quantity), null, 1, PositionSide.BOTH, QuantityUnit.BASE,
        MarginMode.CASH, new BigDecimal(triggerPrice), null, false, List.of());
  }
}
