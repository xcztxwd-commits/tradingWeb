package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.entity.DataProviderEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.DataProviderRepository;
import com.fxplatform.market.repository.SymbolProviderBindingRepository;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.trading.entity.FundingRateEntity;
import com.fxplatform.trading.entity.FundingSettlementEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.repository.FundingRateRepository;
import com.fxplatform.trading.repository.FundingSettlementRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
    "execution.mode=demo",
    "spring.task.scheduling.enabled=false",
    "trading.funding.enabled=false"
})
@ActiveProfiles("database-it")
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class Task11PostgresFundingIT {

  private static final String SYMBOL = "BTCUSDT-PERP";
  private static final Instant OPENED_AT = Instant.parse("2098-01-01T00:00:00Z");
  private static final Instant FUNDING_TIME = Instant.parse("2098-01-01T08:00:00Z");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired SymbolRepository symbolRepository;
  @Autowired DataProviderRepository dataProviderRepository;
  @Autowired SymbolProviderBindingRepository bindingRepository;
  @Autowired FundingRateRepository fundingRateRepository;
  @Autowired FundingSettlementRepository fundingSettlementRepository;
  @Autowired PositionRepository positionRepository;

  private UUID userId;
  private UUID accountId;
  private UUID positionId;

  @BeforeEach
  void createEligibleDemoPosition() {
    userId = UUID.randomUUID();
    accountId = UUID.randomUUID();
    positionId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO auth.users (id, email, password_hash) VALUES (?, ?, ?)",
        userId,
        "task11-" + userId + "@example.test",
        "not-a-real-password");
    jdbcTemplate.update("""
        INSERT INTO core.trading_accounts (
          id, user_id, account_type, base_currency, balance, equity,
          used_margin, free_margin, leverage, status
        ) VALUES (?, ?, 'DEMO', 'USDT', 10000, 10000, 1000, 9000, 10, 'ACTIVE')
        """, accountId, userId);
    jdbcTemplate.update("""
        INSERT INTO trading.positions (
          id, account_id, symbol, product_type, position_mode, position_side,
          margin_mode, side, lots, open_price, current_price, mark_price,
          settlement_asset, margin_asset, leverage, status, opened_at
        ) VALUES (
          ?, ?, ?, 'LINEAR_PERP', 'ONE_WAY', 'BOTH',
          'CROSS', 'BUY', 1, 50000, 50000, 50000,
          'USDT', 'USDT', 10, 'OPEN', ?
        )
        """, positionId, accountId, SYMBOL, Timestamp.from(OPENED_AT));
  }

  @AfterEach
  void cleanFixture() {
    jdbcTemplate.update("DELETE FROM trading.funding_settlements WHERE position_id = ?", positionId);
    jdbcTemplate.update(
        "DELETE FROM trading.funding_rates WHERE symbol = ? AND funding_time = ?",
        SYMBOL,
        Timestamp.from(FUNDING_TIME));
    jdbcTemplate.update("DELETE FROM trading.positions WHERE id = ?", positionId);
    jdbcTemplate.update("DELETE FROM core.trading_accounts WHERE id = ?", accountId);
    jdbcTemplate.update("DELETE FROM auth.users WHERE id = ?", userId);
  }

  @Test
  void v47ArrayAndProviderBindingTypeHandlersRoundTrip() {
    SymbolEntity symbol = symbolRepository.findBySymbol(SYMBOL).orElseThrow();

    assertThat(symbol.getFundingSourcePriority())
        .containsExactly("BINANCE", "OKX", "FIXED");
    assertThat(symbolRepository.findByIdForUpdate(symbol.getId()).orElseThrow()
        .getFundingSourcePriority()).containsExactly("BINANCE", "OKX", "FIXED");
    DataProviderEntity binance = dataProviderRepository.findByCode("binance-usdm").orElseThrow();
    assertThat(binance.getAssetClasses()).contains("LINEAR_PERP");
    assertThat(bindingRepository.findEnabledBySymbolIdOrderByPriority(symbol.getId()))
        .extracting(binding -> binding.getProviderSymbol())
        .contains("BTCUSDT", "BTC-USDT-SWAP");
  }

  @Test
  void canonicalInsertAndPersistentDueEligibilityAreRestartSafe() {
    FundingRateEntity rate = rate();

    assertThat(fundingRateRepository.insertIfAbsent(rate)).isTrue();
    assertThat(fundingRateRepository.insertIfAbsent(rate())).isFalse();
    FundingRateEntity persisted = fundingRateRepository.findLatestBySymbol(SYMBOL).orElseThrow();
    assertThat(persisted.getProviderCode()).isEqualTo("binance-usdm");
    assertThat(persisted.getSourceMode()).isEqualTo("PUBLIC_EXTERNAL");
    assertThat(persisted.getRawPayloadHash()).isEqualTo("task11-rate-hash");
    assertThat(fundingRateRepository.findDueRates(null, FUNDING_TIME))
        .extracting(FundingRateEntity::getFundingTime)
        .containsExactly(FUNDING_TIME);

    assertThat(fundingSettlementRepository.insertIfAbsent(settlement())).isTrue();

    assertThat(fundingRateRepository.findDueRates(null, FUNDING_TIME)).isEmpty();
  }

  @Test
  void bootstrapCursorReadsEarliestEligibleDemoLinearPosition() {
    assertThat(positionRepository.findEarliestOpenLinearPerpTimeBySymbol(SYMBOL))
        .contains(OPENED_AT);
  }

  private FundingRateEntity rate() {
    FundingRateEntity rate = new FundingRateEntity();
    rate.setId(UUID.randomUUID());
    rate.setSymbol(SYMBOL);
    rate.setFundingRate(new BigDecimal("0.0001000000"));
    rate.setFundingTime(FUNDING_TIME);
    rate.setNextFundingTime(FUNDING_TIME.plusSeconds(8 * 60 * 60));
    rate.setMarkPrice(new BigDecimal("50000.0000000000"));
    rate.setProviderCode("binance-usdm");
    rate.setSourceMode("PUBLIC_EXTERNAL");
    rate.setAsOf(FUNDING_TIME.minusSeconds(1));
    rate.setIntervalMinutes(480);
    rate.setRawPayloadHash("task11-rate-hash");
    return rate;
  }

  private FundingSettlementEntity settlement() {
    FundingSettlementEntity settlement = new FundingSettlementEntity();
    settlement.setId(UUID.randomUUID());
    settlement.setPositionId(positionId);
    settlement.setAccountId(accountId);
    settlement.setSymbol(SYMBOL);
    settlement.setFundingTime(FUNDING_TIME);
    settlement.setFundingRate(new BigDecimal("0.0001000000"));
    settlement.setAmount(new BigDecimal("-5.00000000"));
    settlement.setAsset("USDT");
    settlement.setPositionSide(PositionSide.BOTH);
    settlement.setMarginMode(MarginMode.CROSS);
    settlement.setMarkPrice(new BigDecimal("50000.0000000000"));
    settlement.setSource("binance-usdm");
    settlement.setBalanceAfter(new BigDecimal("9995.00000000"));
    settlement.setIsolatedMarginAfter(BigDecimal.ZERO.setScale(8));
    settlement.setShortfall(BigDecimal.ZERO.setScale(8));
    return settlement;
  }
}
