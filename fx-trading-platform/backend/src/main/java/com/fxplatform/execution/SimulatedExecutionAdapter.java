package com.fxplatform.execution;

import cn.hutool.core.date.DateUtil;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.model.InstrumentProfile;
import com.fxplatform.risk.service.TradingAlgorithmEngine;
import com.fxplatform.risk.service.TradingInstrumentClassifier;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.enums.OrderSide;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
@ConditionalOnProperty(prefix = "execution", name = "mode", havingValue = "demo")
public class SimulatedExecutionAdapter implements ExecutionAdapter {

  private static final BigDecimal SLIPPAGE_RATE = new BigDecimal("0.0001");
  private static final BigDecimal FEE_RATE = new BigDecimal("0.0010");

  private final QuoteService quoteService;
  private final SymbolRepository symbolRepository;
  private final TradingInstrumentClassifier instrumentClassifier = new TradingInstrumentClassifier();
  private final TradingAlgorithmEngine tradingAlgorithmEngine = new TradingAlgorithmEngine();

  @Autowired
  public SimulatedExecutionAdapter(
      QuoteService quoteService,
      ObjectProvider<SymbolRepository> symbolRepositoryProvider
  ) {
    this(quoteService, symbolRepositoryProvider.getIfAvailable());
  }

  public SimulatedExecutionAdapter(QuoteService quoteService) {
    this(quoteService, (SymbolRepository) null);
  }

  SimulatedExecutionAdapter(QuoteService quoteService, SymbolRepository symbolRepository) {
    this.quoteService = quoteService;
    this.symbolRepository = symbolRepository;
  }

  @Override
  public ExecutionResult execute(CreateOrderRequest request) {
    QuoteResponse quote = quoteService.freshQuote(request.symbol());
    BigDecimal referencePrice = request.side() == OrderSide.BUY ? quote.ask() : quote.bid();
    BigDecimal slippage = referencePrice.multiply(SLIPPAGE_RATE).setScale(10, RoundingMode.HALF_UP);
    BigDecimal filledPrice = request.side() == OrderSide.BUY
        ? referencePrice.add(slippage)
        : referencePrice.subtract(slippage);
    BigDecimal quantity = request.quantity();
    BigDecimal filledQuantity = quantity;
    BigDecimal remainingQuantity = BigDecimal.ZERO;
    InstrumentProfile profile = profileFor(request.symbol());
    BigDecimal fee = feeFor(profile, filledQuantity, filledPrice);

    return new ExecutionResult(
        filledPrice,
        DateUtil.date().toInstant(),
        filledQuantity,
        remainingQuantity,
        fee,
        feeAssetFor(profile),
        slippage,
        null,
        null);
  }

  private BigDecimal feeFor(InstrumentProfile profile, BigDecimal filledQuantity, BigDecimal filledPrice) {
    if (profile.kind() == InstrumentKind.INVERSE_PERPETUAL) {
      BigDecimal usdNotional = tradingAlgorithmEngine.inverseUsdNotional(filledQuantity, profile.unitSize(), BigDecimal.ONE);
      return tradingAlgorithmEngine.inverseFee(usdNotional, filledPrice, FEE_RATE);
    }
    return tradingAlgorithmEngine.linearFee(filledQuantity, filledPrice, FEE_RATE, profile.unitSize());
  }

  private String feeAssetFor(InstrumentProfile profile) {
    if (profile.kind() != InstrumentKind.INVERSE_PERPETUAL) {
      return null;
    }
    String asset = profile.marginAsset() != null && !profile.marginAsset().isBlank()
        ? profile.marginAsset()
        : profile.settlementAsset();
    return asset == null || asset.isBlank() ? null : asset.trim().toUpperCase(Locale.ROOT);
  }

  private InstrumentProfile profileFor(String symbol) {
    String normalized = normalizeSymbol(symbol);
    return instrumentClassifier.profile(findConfiguredSymbol(normalized).orElseGet(() -> fallbackSymbol(normalized)));
  }

  private Optional<SymbolEntity> findConfiguredSymbol(String symbol) {
    if (symbolRepository == null || symbol.isBlank()) {
      return Optional.empty();
    }
    Optional<SymbolEntity> configuredSymbol = symbolRepository.findBySymbol(symbol);
    return configuredSymbol == null ? Optional.empty() : configuredSymbol;
  }

  private SymbolEntity fallbackSymbol(String symbol) {
    SymbolEntity entity = new SymbolEntity();
    entity.setSymbol(symbol);
    return entity;
  }

  private String normalizeSymbol(String symbol) {
    return symbol == null ? "" : symbol.trim().toUpperCase();
  }
}
