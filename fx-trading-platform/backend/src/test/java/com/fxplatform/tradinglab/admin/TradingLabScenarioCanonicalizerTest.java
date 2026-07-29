package com.fxplatform.tradinglab.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TradingLabScenarioCanonicalizerTest {

  private final TradingLabScenarioCanonicalizer canonicalizer =
      new TradingLabScenarioCanonicalizer(new ObjectMapper(), 4096);

  @Test
  void recursivelySortsKeysAndPreservesArrayOrder() {
    var result = canonicalizer.canonicalize("""
        {
          "z":[2,1],
          "nested":{"b":2,"a":1},
          "a":"1.23"
        }
        """);

    assertThat(result.json())
        .isEqualTo("{\"a\":\"1.23\",\"nested\":{\"a\":1,\"b\":2},\"z\":[2,1]}");
    assertThat(result.sha256())
        .isEqualTo("f44d9a6ba8e0069ebe7298b4b87a3a7366ed8d95712a4386f70a893a9e97be2e");
  }

  @Test
  void rejectsDuplicateKeysFloatingJsonNumbersAndCredentialFields() {
    assertThatThrownBy(() -> canonicalizer.canonicalize("{\"a\":1,\"a\":2}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> canonicalizer.canonicalize("{\"price\":1.25}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> canonicalizer.canonicalize("{\"price\":1}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> canonicalizer.canonicalize("{\"apiKey\":\"secret\"}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> canonicalizer.canonicalize(
        "{\"security.config.encryption-key\":\"value\"}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> canonicalizer.canonicalize(
        "{\"credentialBundle\":\"value\"}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsTrailingRootContentInsteadOfHashingOnlyTheFirstDocument() {
    assertThatThrownBy(() -> canonicalizer.canonicalize("""
        {"price":"1.00"}{"apiKey":"secret"}
        """))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Trading Lab scenario JSON is invalid");
  }

  @Test
  void rejectsNumericExponentAndNonFiniteTextForms() {
    assertThatThrownBy(() -> canonicalizer.canonicalize("{\"price\":\"1e3\"}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> canonicalizer.canonicalize("{\"price\":\"-Infinity\"}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsTransientFieldsInsteadOfSilentlyChangingCallerEvidence() throws Exception {
    assertThatThrownBy(() -> canonicalizer.canonicalize("""
        {"ui_state":{"dirty":true},"kept":"1.00"}
        """))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> canonicalizer.canonicalize(
        new ObjectMapper().readTree("""
            {"validation-errors":["%s"],"kept":"1.00"}
            """.formatted("x".repeat(5_000)))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void normalizesDecimalStringsBeforeHashingEquivalentEvidence() {
    TradingLabCanonicalDocument first =
        canonicalizer.canonicalize("{\"price\":\"1.0\",\"zero\":\"-0.000\"}");
    TradingLabCanonicalDocument second =
        canonicalizer.canonicalize("{\"price\":\"1.00\",\"zero\":\"0\"}");

    assertThat(first.json()).isEqualTo("{\"price\":\"1\",\"zero\":\"0\"}");
    assertThat(first.sha256()).isEqualTo(second.sha256());
  }

  @Test
  void preservesOpaqueNumericIdentifiersWhileNormalizingFinancialValues() {
    assertThat(canonicalizer.canonicalize("""
        {"actionId":"001","price":"001.2300","modelVersion":"1.0"}
        """).json()).isEqualTo(
            "{\"actionId\":\"001\",\"modelVersion\":\"1.0\",\"price\":\"1.23\"}");
  }

  @Test
  void preservesStringSeedIdentityForDeterministicGeneration() {
    TradingLabCanonicalDocument padded =
        canonicalizer.canonicalize("{\"price\":\"1.00\",\"seed\":\"001\"}");
    TradingLabCanonicalDocument plain =
        canonicalizer.canonicalize("{\"price\":\"1.00\",\"seed\":\"1\"}");

    assertThat(padded.json()).isEqualTo("{\"price\":\"1\",\"seed\":\"001\"}");
    assertThat(padded.sha256())
        .isEqualTo("3463361e178baf96be6d828b27785ed72b59ae5b3cb10d55706535215c4ec65f");
    assertThat(padded.sha256()).isNotEqualTo(plain.sha256());
  }

  @Test
  void requiresInitialBalanceMapValuesToBeNormalizedDecimalStrings() {
    TradingLabCanonicalDocument padded = canonicalizer.canonicalize("""
        {"initialBalances":{"USDT":"100000.00"}}
        """);
    TradingLabCanonicalDocument normalized = canonicalizer.canonicalize("""
        {"initialBalances":{"USDT":"100000"}}
        """);

    assertThat(padded.json())
        .isEqualTo("{\"initialBalances\":{\"USDT\":\"100000\"}}");
    assertThat(padded.sha256()).isEqualTo(normalized.sha256());
    assertThatThrownBy(() -> canonicalizer.canonicalize("""
        {"initialBalances":{"USDT":100000}}
        """))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDocumentsAboveTheUtf8Budget() {
    String oversized = "{\"value\":\"" + "x".repeat(4096) + "\"}";

    assertThatThrownBy(() -> canonicalizer.canonicalize(oversized))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Trading Lab scenario JSON is invalid");
  }
}
