package com.fxplatform.risk.service;

import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.service.SymbolProductTypes;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.model.InstrumentProfile;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class TradingInstrumentClassifier {

  private static final BigDecimal FOREX_STANDARD_LOT = new BigDecimal("100000");
  private static final BigDecimal ONE_UNIT = BigDecimal.ONE;

  public InstrumentProfile profile(SymbolEntity symbol) {
    return profileForProductType(symbol, SymbolProductTypes.readOrLegacy(symbol));
  }

  private InstrumentProfile profileForProductType(SymbolEntity symbol, ProductType productType) {
    return switch (productType) {
      case FX_MARGIN -> forexProfile(symbol);
      case CRYPTO_SPOT -> spotProfile(symbol);
      case LINEAR_PERP -> perpProfile(symbol, false);
      case INVERSE_PERP -> perpProfile(symbol, true);
    };
  }

  private InstrumentProfile forexProfile(SymbolEntity symbol) {
    return new InstrumentProfile(InstrumentKind.FOREX, unitSize(symbol, FOREX_STANDARD_LOT), "FOREX", "LOT");
  }

  private InstrumentProfile spotProfile(SymbolEntity symbol) {
    return new InstrumentProfile(InstrumentKind.SPOT, unitSize(symbol, ONE_UNIT), "SPOT", baseUnit(symbol));
  }

  private InstrumentProfile perpProfile(SymbolEntity symbol, boolean inverseContract) {
    BigDecimal contractSize = contractSize(symbol, inverseContract ? new BigDecimal("100") : ONE_UNIT);
    BigDecimal contractMultiplier = positiveOrDefault(symbol.getContractMultiplier(), ONE_UNIT);
    BigDecimal unitSize = contractSize.multiply(contractMultiplier);
    return new InstrumentProfile(
        inverseContract ? InstrumentKind.INVERSE_PERPETUAL : InstrumentKind.LINEAR_PERPETUAL,
        unitSize,
        "SWAP",
        "CONTRACT",
        contractSize,
        contractMultiplier,
        rate(symbol.getMaintenanceMarginRate()),
        asset(symbol.getSettlementAsset(), inverseContract ? symbol.getBaseCurrency() : symbol.getQuoteCurrency()),
        asset(symbol.getMarginAsset(), inverseContract ? symbol.getBaseCurrency() : symbol.getQuoteCurrency()));
  }

  private boolean isContractAssetClass(String assetClass) {
    return "LINEAR_PERP".equals(assetClass)
        || "LINEAR_PERPETUAL".equals(assetClass)
        || "INVERSE_PERP".equals(assetClass)
        || "INVERSE_PERPETUAL".equals(assetClass)
        || "PERPETUAL".equals(assetClass)
        || "SWAP".equals(assetClass)
        || "FUTURES".equals(assetClass)
        || "CONTRACT".equals(assetClass);
  }

  private boolean isInverseAssetClass(String assetClass) {
    return "INVERSE_PERP".equals(assetClass) || "INVERSE_PERPETUAL".equals(assetClass);
  }

  private BigDecimal unitSize(SymbolEntity symbol, BigDecimal fallback) {
    BigDecimal lotSize = symbol.getLotSize();
    return lotSize == null || lotSize.compareTo(BigDecimal.ZERO) <= 0 ? fallback : lotSize;
  }

  private BigDecimal contractSize(SymbolEntity symbol, BigDecimal fallback) {
    BigDecimal contractSize = symbol.getContractSize();
    return positiveOrDefault(contractSize, unitSize(symbol, fallback));
  }

  private BigDecimal positiveOrDefault(BigDecimal value, BigDecimal fallback) {
    return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? fallback : value;
  }

  private BigDecimal rate(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private String asset(String preferred, String fallback) {
    String value = normalize(preferred);
    return value.isBlank() ? normalize(fallback) : value;
  }

  private String baseUnit(SymbolEntity symbol) {
    String baseCurrency = normalize(symbol.getBaseCurrency());
    return baseCurrency.isBlank() ? "" : baseCurrency;
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase();
  }
}
