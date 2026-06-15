package com.fxplatform.common.market;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SymbolNormalizerTest {

  @Test
  void normalizesCommonSeparators() {
    assertThat(SymbolNormalizer.normalize("eur-usd")).isEqualTo("EURUSD");
    assertThat(SymbolNormalizer.normalize("eur_usd")).isEqualTo("EURUSD");
    assertThat(SymbolNormalizer.normalize("eur/usd")).isEqualTo("EURUSD");
  }

  @Test
  void buildsNormalizedMarketTopics() {
    assertThat(SymbolNormalizer.quoteTopic("eur-usd")).isEqualTo("/topic/market/quotes/EURUSD");
    assertThat(SymbolNormalizer.orderBookTopic("eur_usd")).isEqualTo("/topic/market/order-book/EURUSD");
    assertThat(SymbolNormalizer.tradesTopic("eur/usd")).isEqualTo("/topic/market/trades/EURUSD");
  }
}
