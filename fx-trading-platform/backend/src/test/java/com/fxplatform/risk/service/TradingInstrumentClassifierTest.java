package com.fxplatform.risk.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.model.InstrumentProfile;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TradingInstrumentClassifierTest {

  private final TradingInstrumentClassifier classifier = new TradingInstrumentClassifier();

  @Test
  void cryptoAssetClassRepresentsSpotWithoutLeverage() {
    SymbolEntity symbol = symbol("BTCUSDT", "CRYPTO", "BTC", "USDT", "1", 20);

    InstrumentProfile profile = classifier.profile(symbol);

    assertThat(profile.kind()).isEqualTo(InstrumentKind.SPOT);
    assertThat(profile.instrumentType()).isEqualTo("SPOT");
    assertThat(profile.positionUnit()).isEqualTo("BTC");
    assertThat(profile.unitSize()).isEqualByComparingTo("1");
  }

  @Test
  void cryptoSpotProductTypeOverridesLeverageHeuristic() {
    SymbolEntity symbol = symbol("BTCUSDT", "CRYPTO", "BTC", "USDT", "1", 20);
    symbol.setProductType(ProductType.CRYPTO_SPOT);

    InstrumentProfile profile = classifier.profile(symbol);

    assertThat(profile.kind()).isEqualTo(InstrumentKind.SPOT);
    assertThat(profile.instrumentType()).isEqualTo("SPOT");
  }

  @Test
  void productTypeMapsPerpetualContractsExplicitly() {
    SymbolEntity linear = symbol("BTCUSDT", "CRYPTO", "BTC", "USDT", "1", 20);
    linear.setProductType(ProductType.LINEAR_PERP);
    SymbolEntity inverse = symbol("BTCUSD", "CRYPTO", "BTC", "USD", "1", 20);
    inverse.setProductType(ProductType.INVERSE_PERP);

    assertThat(classifier.profile(linear).kind()).isEqualTo(InstrumentKind.LINEAR_PERPETUAL);
    assertThat(classifier.profile(inverse).kind()).isEqualTo(InstrumentKind.INVERSE_PERPETUAL);
  }

  @Test
  void linearPerpetualAssetClassRepresentsUsdtContract() {
    SymbolEntity symbol = symbol("BTCUSDT", "LINEAR_PERPETUAL", "BTC", "USDT", "1", 20);

    InstrumentProfile profile = classifier.profile(symbol);

    assertThat(profile.kind()).isEqualTo(InstrumentKind.LINEAR_PERPETUAL);
    assertThat(profile.instrumentType()).isEqualTo("SWAP");
    assertThat(profile.positionUnit()).isEqualTo("CONTRACT");
    assertThat(profile.unitSize()).isEqualByComparingTo("1");
  }

  private SymbolEntity symbol(
      String code,
      String assetClass,
      String baseCurrency,
      String quoteCurrency,
      String lotSize,
      int leverage
  ) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol(code);
    symbol.setAssetClass(assetClass);
    symbol.setBaseCurrency(baseCurrency);
    symbol.setQuoteCurrency(quoteCurrency);
    symbol.setLotSize(new BigDecimal(lotSize));
    symbol.setLeverage(leverage);
    return symbol;
  }
}
