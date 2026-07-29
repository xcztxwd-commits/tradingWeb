package com.fxplatform.trading.scenario;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.account.service.DemoAccountLifecycleService;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.SpotPositionEntity;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.SpotPositionRepository;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ScenarioFixture {

  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(8);
  private static final BigDecimal MAINTENANCE_RATE = new BigDecimal("0.00500000");

  private final JdbcTemplate jdbcTemplate;
  private final DemoAccountLifecycleService lifecycleService;
  private final TradingAccountRepository accountRepository;
  private final WalletBalanceRepository walletRepository;
  private final SpotPositionRepository spotPositionRepository;
  private final PositionRepository positionRepository;
  private final OrderRepository orderRepository;
  private final AccountSymbolSettingRepository settingRepository;
  private final Set<ScenarioContext> contexts = ConcurrentHashMap.newKeySet();

  public ScenarioFixture(
      JdbcTemplate jdbcTemplate,
      DemoAccountLifecycleService lifecycleService,
      TradingAccountRepository accountRepository,
      WalletBalanceRepository walletRepository,
      SpotPositionRepository spotPositionRepository,
      PositionRepository positionRepository,
      OrderRepository orderRepository,
      AccountSymbolSettingRepository settingRepository
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.lifecycleService = lifecycleService;
    this.accountRepository = accountRepository;
    this.walletRepository = walletRepository;
    this.spotPositionRepository = spotPositionRepository;
    this.positionRepository = positionRepository;
    this.orderRepository = orderRepository;
    this.settingRepository = settingRepository;
  }

  @Transactional
  public ScenarioContext create(ScenarioDefinition scenario) {
    Objects.requireNonNull(scenario, "scenario");
    UUID userId = UUID.randomUUID();
    String email = "scenario-" + scenario.caseId().toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "-") + "-" + userId + "@example.test";
    jdbcTemplate.update("""
        INSERT INTO auth.users (id, email, password_hash, status, role, kyc_status, risk_level)
        VALUES (?, ?, 'not-used', 'ACTIVE', 'USER', 'NOT_SUBMITTED', 'NORMAL')
        """, userId, email);

    TradingAccountEntity account = lifecycleService.getOrCreateDemoAccount(userId);
    account.setUserId(userId);
    ScenarioContext context = new ScenarioContext(
        scenario,
        userId,
        email,
        new UserPrincipal(userId, email, "USER"),
        account);
    context.putRef("account", account.getId());

    clearDemoInitialization(account.getId());
    seedSpotInstrumentRules(context);
    seedWallets(context);
    seedSettings(context);
    seedPosition(context);
    seedInitialOrders(context);
    accountRepository.save(account);
    contexts.add(context);
    return context;
  }

  @Transactional
  public void cleanup(ScenarioContext context) {
    if (context == null) {
      return;
    }
    UUID accountId = context.accountId();
    jdbcTemplate.update(
        "DELETE FROM audit.audit_logs WHERE target_id = ? OR actor_user_id = ?",
        accountId.toString(),
        context.userId());
    jdbcTemplate.update("""
        DELETE FROM trading.order_events
        WHERE order_id IN (SELECT id FROM trading.orders WHERE account_id = ?)
        """, accountId);
    jdbcTemplate.update("DELETE FROM trading.trades WHERE account_id = ?", accountId);
    jdbcTemplate.update(
        "DELETE FROM trading.cross_liquidation_charges WHERE account_id = ?", accountId);
    jdbcTemplate.update("DELETE FROM trading.orders WHERE account_id = ?", accountId);
    jdbcTemplate.update(
        "DELETE FROM trading.funding_settlements WHERE account_id = ?", accountId);
    jdbcTemplate.update(
        "DELETE FROM trading.fx_financing_settlements WHERE account_id = ?", accountId);
    jdbcTemplate.update("DELETE FROM trading.positions WHERE account_id = ?", accountId);
    jdbcTemplate.update("DELETE FROM trading.spot_positions WHERE account_id = ?", accountId);
    jdbcTemplate.update(
        "DELETE FROM ledger.asset_ledger_entries WHERE account_id = ?", accountId);
    jdbcTemplate.update("DELETE FROM ledger.ledger_entries WHERE account_id = ?", accountId);
    jdbcTemplate.update(
        "DELETE FROM core.wallet_daily_snapshots WHERE account_id = ?", accountId);
    jdbcTemplate.update(
        "DELETE FROM core.account_daily_snapshots WHERE account_id = ?", accountId);
    jdbcTemplate.update("DELETE FROM core.wallet_balances WHERE account_id = ?", accountId);
    jdbcTemplate.update(
        "DELETE FROM trading.account_symbol_settings WHERE account_id = ?", accountId);
    jdbcTemplate.update("DELETE FROM core.trading_accounts WHERE id = ?", accountId);
    jdbcTemplate.update("DELETE FROM auth.users WHERE id = ?", context.userId());
    contexts.remove(context);
  }

  @Transactional
  public void cleanupAll() {
    List.copyOf(contexts).forEach(this::cleanup);
  }

  private void clearDemoInitialization(UUID accountId) {
    jdbcTemplate.update(
        "DELETE FROM ledger.asset_ledger_entries "
            + "WHERE account_id = ? AND operation_type = 'DEMO_INIT'",
        accountId);
    jdbcTemplate.update(
        "DELETE FROM ledger.ledger_entries "
            + "WHERE account_id = ? AND operation_type = 'DEMO_INIT'",
        accountId);
  }

  private void seedSpotInstrumentRules(ScenarioContext context) {
    if (context.scenario().productType() != ProductType.CRYPTO_SPOT) {
      return;
    }
    jdbcTemplate.update("""
        INSERT INTO market.provider_instruments (
          id, provider_id, provider_symbol, asset_class, base_asset, quote_asset,
          display_name, listed, raw_json, last_synced_at
        )
        SELECT
          '4d42ff6a-e84e-5c78-b525-4e9b20d10742'::uuid,
          provider.id,
          'BTCUSDT',
          'CRYPTO',
          'BTC',
          'USDT',
          'BTC/USDT',
          TRUE,
          '{"rules":{"tickSize":"0.10000000","stepSize":"0.00010000","minQty":"0.00010000","maxQty":"100.00000000","minNotional":"5.00000000"}}'::jsonb,
          NOW()
        FROM market.data_providers provider
        WHERE provider.code = 'binance'
        ON CONFLICT (provider_id, provider_symbol) DO UPDATE
        SET raw_json = EXCLUDED.raw_json,
            last_synced_at = EXCLUDED.last_synced_at,
            listed = TRUE
        """);
    jdbcTemplate.update("""
        UPDATE market.symbol_provider_bindings binding
        SET provider_instrument_id = instrument.id
        FROM market.symbols symbol,
             market.data_providers provider,
             market.provider_instruments instrument
        WHERE binding.symbol_id = symbol.id
          AND binding.provider_id = provider.id
          AND instrument.provider_id = provider.id
          AND instrument.provider_symbol = 'BTCUSDT'
          AND symbol.symbol = 'BTCUSDT'
          AND provider.code = 'binance'
        """);
  }

  private void seedWallets(ScenarioContext context) {
    ScenarioDefinition scenario = context.scenario();
    Map<String, WalletBalanceEntity> existing = new LinkedHashMap<>();
    for (WalletBalanceEntity wallet :
        walletRepository.findByAccountIdOrderByAssetAsc(context.accountId())) {
      if ("SPOT".equals(wallet.getWalletType())) {
        existing.put(wallet.getAsset(), wallet);
      }
    }
    for (WalletBalanceEntity wallet : existing.values()) {
      if (!scenario.initialBalances().containsKey(wallet.getAsset())) {
        walletRepository.deleteById(wallet.getId());
      }
    }
    scenario.initialBalances().forEach((asset, value) -> {
      WalletBalanceEntity wallet = existing.getOrDefault(asset, new WalletBalanceEntity());
      if (wallet.getId() == null) {
        wallet.setId(UUID.randomUUID());
      }
      BigDecimal amount = money(value);
      wallet.setAccountId(context.accountId());
      wallet.setWalletType("SPOT");
      wallet.setAsset(asset);
      wallet.setTotal(amount);
      wallet.setAvailable(amount);
      wallet.setLocked(ZERO);
      walletRepository.save(wallet);
      context.putRef("wallet:SPOT:" + asset, wallet.getId());
    });

    BigDecimal balance = money(
        scenario.initialBalances().getOrDefault(context.quoteAsset(), "0"));
    TradingAccountEntity account = context.account();
    account.setBaseCurrency(context.quoteAsset());
    account.setBalance(balance);
    account.setEquity(balance);
    account.setUsedMargin(ZERO);
    account.setFreeMargin(balance);
    account.setMarginLevel(null);
    account.setLeverage(initialLeverage(scenario));
    account.setPositionMode(scenario.positionMode());
  }

  private void seedSettings(ScenarioContext context) {
    ScenarioDefinition scenario = context.scenario();
    var existing = settingRepository.findByAccountIdAndSymbol(
        context.accountId(), context.symbol());
    if (existing.isPresent()) {
      AccountSymbolSettingEntity setting = existing.orElseThrow();
      settingRepository.updateIfVersion(
          context.accountId(),
          context.symbol(),
          setting.getVersion(),
          initialLeverage(scenario),
          scenario.marginMode(),
          scenario.quantityUnit());
      return;
    }
    AccountSymbolSettingEntity setting = new AccountSymbolSettingEntity();
    setting.setAccountId(context.accountId());
    setting.setSymbol(context.symbol());
    setting.setLeverage(initialLeverage(scenario));
    setting.setMarginMode(scenario.marginMode());
    setting.setQuantityUnit(scenario.quantityUnit());
    if (setting.getVersion() == null) {
      setting.setVersion(0L);
    }
    settingRepository.save(setting);
  }

  private void seedPosition(ScenarioContext context) {
    String value = context.scenario().initialPosition();
    if ("NONE".equalsIgnoreCase(value)) {
      return;
    }
    InitialPosition initial = InitialPosition.parse(value);
    if (context.scenario().productType() == ProductType.CRYPTO_SPOT) {
      seedSpotPosition(context, initial);
    } else {
      seedPerpetualPosition(context, initial);
    }
  }

  private void seedSpotPosition(ScenarioContext context, InitialPosition initial) {
    SpotPositionEntity position = new SpotPositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(context.accountId());
    position.setWalletType("SPOT");
    position.setAsset(initial.direction());
    position.setCostAsset(context.quoteAsset());
    position.setQuantity(initial.quantity());
    position.setAverageCost(initial.entryPrice());
    position.setRealizedPnl(ZERO);
    position.setUnrealizedPnl(ZERO);
    position.setFeeCost(ZERO);
    spotPositionRepository.save(position);
    context.putRef(initial.direction(), position.getId());
  }

  private void seedPerpetualPosition(ScenarioContext context, InitialPosition initial) {
    ScenarioDefinition scenario = context.scenario();
    int leverage = initialLeverage(scenario);
    PositionSide requestedSide = PositionSide.valueOf(initial.direction());
    PositionSide slot = scenario.positionMode() == PositionMode.ONE_WAY
        ? PositionSide.BOTH
        : requestedSide;
    BigDecimal notional = money(initial.quantity().multiply(initial.entryPrice()));
    BigDecimal initialMargin = money(notional.divide(
        BigDecimal.valueOf(leverage), 16, RoundingMode.HALF_UP));
    BigDecimal marginHeld = initial.margin() == null ? initialMargin : initial.margin();
    BigDecimal mark = initial.entryPrice();

    PositionEntity position = new PositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(context.accountId());
    position.setSymbol(context.symbol());
    position.setProductType(ProductType.LINEAR_PERP);
    position.setPositionMode(scenario.positionMode());
    position.setPositionSide(slot);
    position.setMarginMode(scenario.marginMode());
    position.setSide(requestedSide == PositionSide.SHORT
        ? OrderSide.SELL
        : OrderSide.BUY);
    position.setLots(initial.quantity());
    position.setOpenPrice(initial.entryPrice());
    position.setCurrentPrice(mark);
    position.setMarkPrice(mark);
    position.setFloatingPnl(ZERO);
    position.setRealizedPnl(ZERO);
    position.setFundingPnl(ZERO);
    position.setMarginHeld(marginHeld);
    position.setNotional(notional);
    position.setInitialMargin(initialMargin);
    position.setMaintenanceMargin(money(notional.multiply(MAINTENANCE_RATE)));
    position.setSettlementAsset(context.quoteAsset());
    position.setMarginAsset(context.quoteAsset());
    position.setStatus(PositionStatus.OPEN);
    position.setLeverage(leverage);
    position.setVersion(0L);
    positionRepository.save(position);

    String slotRef = context.symbol() + ":" + slot;
    context.putRef(slotRef, position.getId());
    context.putRef("position:" + slot, position.getId());
    TradingAccountEntity account = context.account();
    account.setUsedMargin(money(account.getUsedMargin().add(marginHeld)));
    account.setEquity(account.getBalance());
    account.setFreeMargin(money(account.getEquity().subtract(account.getUsedMargin())));
  }

  private void seedInitialOrders(ScenarioContext context) {
    for (String encoded : context.scenario().initialOrders()) {
      InitialOrder initial = InitialOrder.parse(encoded, context.scenario());
      UUID orderId = UUID.randomUUID();
      OrderEntity order = new OrderEntity();
      order.setId(orderId);
      order.setUserId(context.userId());
      order.setAccountId(context.accountId());
      order.setSymbol(context.symbol());
      order.setProductType(context.scenario().productType());
      order.setPositionMode(context.scenario().positionMode());
      order.setPositionSide(context.scenario().positionSide());
      order.setMarginMode(context.scenario().marginMode());
      order.setSide(initial.side());
      order.setOrderType(initial.type());
      order.setStatus(initial.status());
      order.setLots(initial.quantity());
      order.setQuantity(initial.quantity());
      order.setOriginalQuantity(initial.quantity());
      order.setBaseQuantity(initial.quantity());
      order.setRequestedPrice(initial.price());
      order.setPrice(initial.price());
      order.setTriggerPrice(initial.triggerPrice());
      order.setFilledQuantity(initial.filledQuantity());
      order.setRemainingQuantity(initial.remainingQuantity());
      order.setHoldCurrency(initial.holdAsset());
      order.setHoldAmount(initial.holdAmount());
      order.setHoldOwnerOrderId(initial.holdAmount().signum() > 0 ? orderId : null);
      order.setClientOrderId(initial.ref() + "-client");
      order.setIdempotencyKey(initial.ref() + "-idempotency");
      order.setReduceOnly(context.scenario().reduceOnly());
      order.setOrderOrigin(OrderOrigin.USER);
      order.setLeverage(initialLeverage(context.scenario()));
      order.setFee(ZERO);
      order.setVersion(0L);
      orderRepository.save(order);
      context.putRef(initial.ref(), orderId);
    }
  }

  private static BigDecimal money(String value) {
    return money(new BigDecimal(value));
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(8, RoundingMode.HALF_UP);
  }

  private static int initialLeverage(ScenarioDefinition scenario) {
    return scenario.actions().getFirst().type() == ScenarioAction.Type.CHANGE_LEVERAGE
        ? 10
        : scenario.leverage();
  }

  private record InitialPosition(
      String direction,
      BigDecimal quantity,
      BigDecimal entryPrice,
      BigDecimal margin
  ) {

    private static InitialPosition parse(String encoded) {
      String[] parts = encoded.split(",");
      String[] position = parts[0].split("=", 2);
      String[] quantityAndPrice = position[1].split("@", 2);
      BigDecimal margin = null;
      for (int index = 1; index < parts.length; index++) {
        String[] option = parts[index].split("=", 2);
        if ("MARGIN".equalsIgnoreCase(option[0])) {
          margin = money(option[1]);
        }
      }
      return new InitialPosition(
          position[0].trim().toUpperCase(Locale.ROOT),
          money(quantityAndPrice[0]),
          money(quantityAndPrice[1]),
          margin);
    }
  }

  private record InitialOrder(
      String ref,
      OrderSide side,
      OrderType type,
      OrderStatus status,
      BigDecimal quantity,
      BigDecimal price,
      BigDecimal triggerPrice,
      BigDecimal filledQuantity,
      BigDecimal remainingQuantity,
      String holdAsset,
      BigDecimal holdAmount
  ) {

    private static InitialOrder parse(String encoded, ScenarioDefinition scenario) {
      Map<String, String> values = new LinkedHashMap<>();
      for (String token : encoded.split(";")) {
        String[] pair = token.split("=", 2);
        if (pair.length == 2) {
          values.put(pair[0].trim().toLowerCase(Locale.ROOT), pair[1].trim());
        }
      }
      String ref = values.getOrDefault("ref", encoded.trim());
      ScenarioAction firstAction = scenario.actions().getFirst();
      OrderSide defaultSide = firstAction.parameters().side() == null
          ? OrderSide.BUY
          : firstAction.parameters().side();
      BigDecimal quantity = money(values.getOrDefault(
          "quantity",
          firstAction.quantity() == null ? "1" : firstAction.quantity().toPlainString()));
      OrderStatus status = OrderStatus.valueOf(
          values.getOrDefault("status", "PENDING").toUpperCase(Locale.ROOT));
      BigDecimal filled = money(values.getOrDefault(
          "filledquantity",
          status == OrderStatus.FILLED ? quantity.toPlainString() : "0"));
      BigDecimal remaining = money(values.getOrDefault(
          "remainingquantity",
          status == OrderStatus.FILLED ? "0" : quantity.toPlainString()));
      return new InitialOrder(
          ref,
          OrderSide.valueOf(
              values.getOrDefault("side", defaultSide.name()).toUpperCase(Locale.ROOT)),
          OrderType.valueOf(
              values.getOrDefault("type", scenario.orderType().name())
                  .toUpperCase(Locale.ROOT)),
          status,
          quantity,
          decimal(values.get("price")),
          decimal(values.get("triggerprice")),
          filled,
          remaining,
          values.getOrDefault("holdasset", ""),
          money(values.getOrDefault("holdamount", "0")));
    }

    private static BigDecimal decimal(String value) {
      return value == null || value.isBlank() ? null : money(value);
    }
  }
}
