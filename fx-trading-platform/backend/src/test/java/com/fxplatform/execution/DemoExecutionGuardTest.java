package com.fxplatform.execution;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import java.util.Map;
import java.util.Optional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class DemoExecutionGuardTest {

  private static final Map<String, ProductType> ALLOWED_SYMBOLS = Map.ofEntries(
      Map.entry("BTCUSDT", ProductType.CRYPTO_SPOT),
      Map.entry("ETHUSDT", ProductType.CRYPTO_SPOT),
      Map.entry("BNBUSDT", ProductType.CRYPTO_SPOT),
      Map.entry("SOLUSDT", ProductType.CRYPTO_SPOT),
      Map.entry("XRPUSDT", ProductType.CRYPTO_SPOT),
      Map.entry("BTCUSDT-PERP", ProductType.LINEAR_PERP),
      Map.entry("ETHUSDT-PERP", ProductType.LINEAR_PERP),
      Map.entry("BNBUSDT-PERP", ProductType.LINEAR_PERP),
      Map.entry("SOLUSDT-PERP", ProductType.LINEAR_PERP),
      Map.entry("XRPUSDT-PERP", ProductType.LINEAR_PERP));

  private ExecutionProperties properties;
  private SymbolRepository symbolRepository;
  private DemoExecutionGuard guard;

  @BeforeEach
  void setUp() {
    properties = new ExecutionProperties();
    properties.setMode(ExecutionMode.DEMO);
    symbolRepository = mock(SymbolRepository.class);
    when(symbolRepository.findBySymbol(anyString())).thenAnswer(invocation -> {
      String symbol = invocation.getArgument(0);
      ProductType productType = ALLOWED_SYMBOLS.get(symbol);
      return productType == null ? Optional.empty() : Optional.of(symbol(symbol, productType, true));
    });
    guard = new DemoExecutionGuard(properties, symbolRepository);
  }

  @Test
  void demoModeDemoAccountAndAllowedProductIsAccepted() {
    assertThatCode(() -> guard.requireDemo(
        account(AccountType.DEMO),
        ProductType.CRYPTO_SPOT,
        "BTCUSDT"))
        .doesNotThrowAnyException();
  }

  @Test
  void accountOnlyGuardAcceptsOnlyActiveDemoAccountsInDemoMode() {
    TradingAccountEntity demo = account(AccountType.DEMO);
    assertThatCode(() -> guard.requireDemoAccount(demo)).doesNotThrowAnyException();

    TradingAccountEntity frozen = account(AccountType.DEMO);
    frozen.setStatus(AccountStatus.FROZEN);
    assertCode("ACCOUNT_NOT_ACTIVE", () -> guard.requireDemoAccount(frozen));
    assertCode("DEMO_ACCOUNT_REQUIRED", () -> guard.requireDemoAccount(account(AccountType.LIVE)));
    properties.setMode(ExecutionMode.DISABLED);
    assertCode("EXECUTION_DISABLED", () -> guard.requireDemoAccount(demo));
  }

  @Test
  void disabledExecutionIsRejected() {
    properties.setMode(ExecutionMode.DISABLED);

    assertCode("EXECUTION_DISABLED", () -> guard.requireDemo(
        account(AccountType.DEMO),
        ProductType.CRYPTO_SPOT,
        "BTCUSDT"));
  }

  @Test
  void liveAccountIsRejected() {
    assertCode("DEMO_ACCOUNT_REQUIRED", () -> guard.requireDemo(
        account(AccountType.LIVE),
        ProductType.CRYPTO_SPOT,
        "BTCUSDT"));
  }

  @ParameterizedTest
  @EnumSource(value = ProductType.class, names = {"FX_MARGIN", "INVERSE_PERP"})
  void unsupportedProductIsRejected(ProductType productType) {
    assertCode("PRODUCT_NOT_ALLOWED", () -> guard.requireDemo(
        account(AccountType.DEMO),
        productType,
        "BTCUSDT"));
  }

  @Test
  void nonWhitelistSymbolIsRejected() {
    assertCode("SYMBOL_NOT_ALLOWED", () -> guard.requireDemo(
        account(AccountType.DEMO),
        ProductType.CRYPTO_SPOT,
        "DOGEUSDT"));
  }

  @ParameterizedTest(name = "{0} is an allowed {1} demo product")
  @MethodSource("allowedProducts")
  void exactTenTradableProductsAreAccepted(String symbol, ProductType productType) {
    assertThatCode(() -> guard.requireDemo(account(AccountType.DEMO), productType, symbol))
        .doesNotThrowAnyException();
  }

  @Test
  void whitelistedSymbolMustExistInMarketSymbols() {
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.empty());

    assertCode("SYMBOL_NOT_ALLOWED", () -> guard.requireDemo(
        account(AccountType.DEMO),
        ProductType.CRYPTO_SPOT,
        "BTCUSDT"));
  }

  @Test
  void whitelistedSymbolMustBeTradableInMarketSymbols() {
    when(symbolRepository.findBySymbol("BTCUSDT"))
        .thenReturn(Optional.of(symbol("BTCUSDT", ProductType.CRYPTO_SPOT, false)));

    assertCode("SYMBOL_NOT_ALLOWED", () -> guard.requireDemo(
        account(AccountType.DEMO),
        ProductType.CRYPTO_SPOT,
        "BTCUSDT"));
  }

  @Test
  void callerProductMustMatchWhitelistedMarketProduct() {
    assertCode("SYMBOL_NOT_ALLOWED", () -> guard.requireDemo(
        account(AccountType.DEMO),
        ProductType.CRYPTO_SPOT,
        "BTCUSDT-PERP"));
  }

  @Test
  void everyExistingDemoExecutionEntryUsesTheSharedGuard() throws Exception {
    assertGuarded("com/fxplatform/trading/service/OrderService.java");
    assertGuarded("com/fxplatform/trading/service/PendingOrderExecutionService.java");
    assertGuarded("com/fxplatform/trading/service/PositionService.java");
    assertGuarded("com/fxplatform/trading/service/ProtectiveOrderExecutionService.java");
    assertGuarded("com/fxplatform/trading/service/FundingService.java");
    assertGuarded("com/fxplatform/trading/service/LiquidationService.java");
    assertGuarded("com/fxplatform/admin/service/AdminTradingCommandService.java");
  }

  private static Stream<Arguments> allowedProducts() {
    return ALLOWED_SYMBOLS.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
  }

  private static void assertGuarded(String relativeSource) throws Exception {
    String source = Files.readString(Path.of("src/main/java").resolve(relativeSource));
    assertThatCode(() -> {
      org.assertj.core.api.Assertions.assertThat(source)
          .as(relativeSource)
          .contains("DemoExecutionGuard")
          .contains("demoExecutionGuard.requireDemo(");
    }).doesNotThrowAnyException();
  }

  private static TradingAccountEntity account(AccountType accountType) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setAccountType(accountType);
    return account;
  }

  private static SymbolEntity symbol(String symbol, ProductType productType, boolean tradable) {
    SymbolEntity entity = new SymbolEntity();
    entity.setSymbol(symbol);
    entity.setProductType(productType);
    entity.setTradable(tradable);
    return entity;
  }

  private static void assertCode(String expectedCode, ThrowingCall call) {
    assertThatThrownBy(call::run)
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo(expectedCode);
  }

  @FunctionalInterface
  private interface ThrowingCall {
    void run();
  }
}
