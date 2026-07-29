package com.fxplatform.trading.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.account.service.DemoAccountLifecycleService;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.SpotPositionEntity;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.SpotPositionRepository;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class ScenarioFixtureContractTest {

  private RecordingJdbcTemplate jdbcTemplate;
  private DemoAccountLifecycleService lifecycleService;
  private TradingAccountRepository accountRepository;
  private WalletBalanceRepository walletRepository;
  private SpotPositionRepository spotPositionRepository;
  private PositionRepository positionRepository;
  private OrderRepository orderRepository;
  private AccountSymbolSettingRepository settingRepository;
  private ScenarioFixture fixture;

  @BeforeEach
  void setUp() {
    jdbcTemplate = new RecordingJdbcTemplate();
    lifecycleService = mock(DemoAccountLifecycleService.class);
    accountRepository = mock(TradingAccountRepository.class);
    walletRepository = mock(WalletBalanceRepository.class);
    spotPositionRepository = mock(SpotPositionRepository.class);
    positionRepository = mock(PositionRepository.class);
    orderRepository = mock(OrderRepository.class);
    settingRepository = mock(AccountSymbolSettingRepository.class);
    fixture = new ScenarioFixture(
        jdbcTemplate,
        lifecycleService,
        accountRepository,
        walletRepository,
        spotPositionRepository,
        positionRepository,
        orderRepository,
        settingRepository);
  }

  @Test
  void createUsesRealDemoLifecycleAndSeedsExactSpotBalancesAndPosition() {
    ScenarioDefinition scenario = ScenarioCatalog.spot()
        .filter(candidate ->
            candidate.caseId().equals("SPOT_CORE_PARTIAL_SELL_PARTIAL_SELL"))
        .findFirst()
        .orElseThrow();
    TradingAccountEntity account = account();
    when(lifecycleService.getOrCreateDemoAccount(any(UUID.class))).thenReturn(account);

    ScenarioContext context = fixture.create(scenario);

    verify(lifecycleService).getOrCreateDemoAccount(context.userId());
    assertThat(context.account()).isSameAs(account);
    assertThat(context.principal().id()).isEqualTo(context.userId());
    assertThat(context.email()).endsWith("@example.test");
    assertThat(jdbcTemplate.sql()).anySatisfy(sql ->
        assertThat(sql).contains("INSERT INTO auth.users"));
    assertThat(jdbcTemplate.sql()).anySatisfy(sql ->
        assertThat(sql)
            .contains("INSERT INTO market.provider_instruments")
            .contains("\"minNotional\":\"5.00000000\""));
    assertThat(jdbcTemplate.sql()).anySatisfy(sql ->
        assertThat(sql)
            .contains("UPDATE market.symbol_provider_bindings")
            .contains("provider_instrument_id"));

    ArgumentCaptor<WalletBalanceEntity> wallets =
        ArgumentCaptor.forClass(WalletBalanceEntity.class);
    verify(walletRepository, org.mockito.Mockito.times(2)).save(wallets.capture());
    assertThat(wallets.getAllValues())
        .extracting(WalletBalanceEntity::getAsset)
        .containsExactlyInAnyOrder("USDT", "BTC");
    assertThat(wallets.getAllValues()).allSatisfy(wallet -> {
      assertThat(wallet.getWalletType()).isEqualTo("SPOT");
      assertThat(wallet.getTotal()).isEqualByComparingTo(
          new BigDecimal(scenario.initialBalances().get(wallet.getAsset())));
      assertThat(wallet.getAvailable()).isEqualByComparingTo(wallet.getTotal());
      assertThat(wallet.getLocked()).isZero();
    });

    ArgumentCaptor<SpotPositionEntity> position =
        ArgumentCaptor.forClass(SpotPositionEntity.class);
    verify(spotPositionRepository).save(position.capture());
    assertThat(position.getValue().getAsset()).isEqualTo("BTC");
    assertThat(position.getValue().getCostAsset()).isEqualTo("USDT");
    assertThat(position.getValue().getQuantity()).isEqualByComparingTo("2.00000000");
    assertThat(position.getValue().getAverageCost()).isEqualByComparingTo("100.00000000");
  }

  @Test
  void createSeedsPerpetualPositionAccountRiskAndSymbolSettings() {
    ScenarioDefinition scenario = ScenarioCatalog.perpetual()
        .filter(candidate ->
            candidate.caseId().equals("PERP_CORE_UP_UP_UP_PARTIAL_CLOSE_ADD_LONG"))
        .findFirst()
        .orElseThrow();
    TradingAccountEntity account = account();
    when(lifecycleService.getOrCreateDemoAccount(any(UUID.class))).thenReturn(account);

    ScenarioContext context = fixture.create(scenario);

    ArgumentCaptor<TradingAccountEntity> savedAccount =
        ArgumentCaptor.forClass(TradingAccountEntity.class);
    verify(accountRepository).save(savedAccount.capture());
    // The core partial-close path starts with three contracts so both closes remain valid.
    assertThat(savedAccount.getValue().getBalance()).isEqualByComparingTo("100000.00000000");
    assertThat(savedAccount.getValue().getEquity()).isEqualByComparingTo("100000.00000000");
    assertThat(savedAccount.getValue().getUsedMargin()).isEqualByComparingTo("30.00000000");
    assertThat(savedAccount.getValue().getFreeMargin()).isEqualByComparingTo("99970.00000000");
    assertThat(savedAccount.getValue().getLeverage()).isEqualTo(10);

    ArgumentCaptor<PositionEntity> position = ArgumentCaptor.forClass(PositionEntity.class);
    verify(positionRepository).save(position.capture());
    assertThat(position.getValue().getProductType()).isEqualTo(ProductType.LINEAR_PERP);
    assertThat(position.getValue().getSymbol()).isEqualTo("BTCUSDT-PERP");
    assertThat(position.getValue().getLots()).isEqualByComparingTo("3.00000000");
    assertThat(position.getValue().getOpenPrice()).isEqualByComparingTo("100.00000000");
    assertThat(position.getValue().getNotional()).isEqualByComparingTo("300.00000000");
    assertThat(position.getValue().getInitialMargin()).isEqualByComparingTo("30.00000000");
    assertThat(position.getValue().getMaintenanceMargin()).isEqualByComparingTo("1.50000000");
    assertThat(context.findRef("BTCUSDT-PERP:BOTH")).contains(position.getValue().getId());
    verify(settingRepository).save(any());
  }

  @Test
  void createUpdatesLifecycleSeededSymbolSettingInsteadOfInsertingDuplicate() {
    ScenarioDefinition scenario = ScenarioCatalog.spot()
        .filter(candidate ->
            candidate.caseId().equals("SPOT_CORE_PARTIAL_SELL_PARTIAL_SELL"))
        .findFirst()
        .orElseThrow();
    TradingAccountEntity account = account();
    String symbol = "BTCUSDT";
    AccountSymbolSettingEntity existing = new AccountSymbolSettingEntity();
    existing.setAccountId(account.getId());
    existing.setSymbol(symbol);
    existing.setVersion(7L);
    when(lifecycleService.getOrCreateDemoAccount(any(UUID.class))).thenReturn(account);
    when(settingRepository.findByAccountIdAndSymbol(account.getId(), symbol))
        .thenReturn(java.util.Optional.of(existing));

    fixture.create(scenario);

    verify(settingRepository).updateIfVersion(
        account.getId(),
        symbol,
        7L,
        scenario.leverage(),
        scenario.marginMode(),
        scenario.quantityUnit());
    verify(settingRepository, never()).save(any());
  }

  @Test
  void changeLeverageScenariosStartFromTheDemoDefaultInsteadOfTheTarget() {
    ScenarioDefinition scenario = ScenarioCatalog.perpetual()
        .filter(candidate -> candidate.caseId().equals("PERP_LEVERAGE_DOWN_SAFE"))
        .findFirst()
        .orElseThrow();
    TradingAccountEntity account = account();
    when(lifecycleService.getOrCreateDemoAccount(any(UUID.class))).thenReturn(account);

    fixture.create(scenario);

    ArgumentCaptor<TradingAccountEntity> savedAccount =
        ArgumentCaptor.forClass(TradingAccountEntity.class);
    verify(accountRepository).save(savedAccount.capture());
    assertThat(savedAccount.getValue().getLeverage()).isEqualTo(10);

    ArgumentCaptor<AccountSymbolSettingEntity> setting =
        ArgumentCaptor.forClass(AccountSymbolSettingEntity.class);
    verify(settingRepository).save(setting.capture());
    assertThat(setting.getValue().getLeverage()).isEqualTo(10);

    ArgumentCaptor<PositionEntity> position = ArgumentCaptor.forClass(PositionEntity.class);
    verify(positionRepository).save(position.capture());
    assertThat(position.getValue().getLeverage()).isEqualTo(10);
    assertThat(position.getValue().getInitialMargin()).isEqualByComparingTo("10.00000000");
    assertThat(position.getValue().getMarginHeld()).isEqualByComparingTo("10.00000000");
  }

  @Test
  void createSeedsSelfDescribingInitialOrdersAndRegistersTheirLogicalRefs() {
    ScenarioDefinition base = ScenarioCatalog.spot().findFirst().orElseThrow();
    ScenarioDefinition scenario = withInitialOrders(base, List.of(
        "ref=seed-order;side=BUY;type=LIMIT;status=PENDING;"
            + "quantity=1.25000000;price=95.00000000;"
            + "holdAsset=USDT;holdAmount=118.80937500"));
    TradingAccountEntity account = account();
    when(lifecycleService.getOrCreateDemoAccount(any(UUID.class))).thenReturn(account);

    ScenarioContext context = fixture.create(scenario);

    ArgumentCaptor<OrderEntity> order = ArgumentCaptor.forClass(OrderEntity.class);
    verify(orderRepository).save(order.capture());
    assertThat(order.getValue().getSide().name()).isEqualTo("BUY");
    assertThat(order.getValue().getOrderType().name()).isEqualTo("LIMIT");
    assertThat(order.getValue().getStatus().name()).isEqualTo("PENDING");
    assertThat(order.getValue().getBaseQuantity()).isEqualByComparingTo("1.25000000");
    assertThat(order.getValue().getPrice()).isEqualByComparingTo("95.00000000");
    assertThat(order.getValue().getHoldCurrency()).isEqualTo("USDT");
    assertThat(order.getValue().getHoldAmount()).isEqualByComparingTo("118.80937500");
    assertThat(context.requireRef("seed-order")).isEqualTo(order.getValue().getId());
  }

  @Test
  void cleanupUsesTheForeignKeySafeAccountOwnedOrder() {
    ScenarioDefinition scenario = ScenarioCatalog.spot().findFirst().orElseThrow();
    TradingAccountEntity account = account();
    when(lifecycleService.getOrCreateDemoAccount(any(UUID.class))).thenReturn(account);
    ScenarioContext context = fixture.create(scenario);
    jdbcTemplate.clear();
    clearInvocations(
        accountRepository,
        walletRepository,
        spotPositionRepository,
        positionRepository,
        orderRepository,
        settingRepository);

    fixture.cleanup(context);

    assertThat(jdbcTemplate.deleteTargets()).containsSubsequence(
        "DELETE FROM audit.audit_logs",
        "DELETE FROM trading.order_events",
        "DELETE FROM trading.trades",
        "DELETE FROM trading.orders",
        "DELETE FROM trading.funding_settlements",
        "DELETE FROM trading.positions",
        "DELETE FROM trading.spot_positions",
        "DELETE FROM ledger.asset_ledger_entries",
        "DELETE FROM ledger.ledger_entries",
        "DELETE FROM core.wallet_balances",
        "DELETE FROM trading.account_symbol_settings",
        "DELETE FROM core.trading_accounts",
        "DELETE FROM auth.users");
  }

  private static TradingAccountEntity account() {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(UUID.randomUUID());
    account.setUserId(UUID.randomUUID());
    return account;
  }

  private static ScenarioDefinition withInitialOrders(
      ScenarioDefinition base,
      List<String> initialOrders
  ) {
    return new ScenarioDefinition(
        base.caseId() + "_SEEDED",
        base.priority(),
        base.productType(),
        base.positionMode(),
        base.positionSide(),
        base.marginMode(),
        base.leverage(),
        base.orderType(),
        base.quantityUnit(),
        base.reduceOnly(),
        base.initialBalances(),
        base.initialPosition(),
        initialOrders,
        base.priceSteps(),
        base.actions(),
        base.exitReason(),
        base.expectedOrder(),
        base.expectedTrades(),
        base.expectedPosition(),
        base.expectedWallet(),
        base.expectedAccount(),
        base.expectedLedger(),
        base.expectedProtections(),
        base.expectedEvents(),
        base.expectedError(),
        base.testClass(),
        base.testMethod(),
        base.executionStatus());
  }

  private static final class RecordingJdbcTemplate extends JdbcTemplate {

    private final List<String> sql = new ArrayList<>();

    @Override
    public int update(String sql) {
      this.sql.add(sql.strip());
      return 1;
    }

    @Override
    public int update(String sql, Object... args) {
      this.sql.add(sql.strip());
      return 1;
    }

    private List<String> sql() {
      return List.copyOf(sql);
    }

    private List<String> deleteTargets() {
      return sql.stream()
          .filter(statement -> statement.startsWith("DELETE FROM "))
          .map(statement -> {
            String singleLine = statement.replaceAll("\\s+", " ");
            int where = singleLine.indexOf(" WHERE ");
            return where < 0 ? singleLine : singleLine.substring(0, where);
          })
          .toList();
    }

    private void clear() {
      sql.clear();
    }
  }
}
