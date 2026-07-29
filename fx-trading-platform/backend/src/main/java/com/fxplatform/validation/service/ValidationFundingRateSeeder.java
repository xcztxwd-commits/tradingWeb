package com.fxplatform.validation.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.trading.entity.FundingRateEntity;
import com.fxplatform.trading.repository.FundingRateRepository;
import com.fxplatform.validation.service.ValidationMarketState.CompositeTick;
import com.fxplatform.validation.service.ValidationMarketState.FundingRatePoint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Persists only the narrow deterministic funding authority carried by a validation market Tick.
 */
@Profile("validation")
@Component
public class ValidationFundingRateSeeder {

  private static final int INTERVAL_MINUTES = 480;

  private final FundingRateRepository repository;

  public ValidationFundingRateSeeder(FundingRateRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  public int persist(CompositeTick tick) {
    int inserted = 0;
    for (FundingRateEntity requested : requestedRows(tick)) {
      if (repository.insertIfAbsent(requested)) {
        inserted++;
        continue;
      }
      FundingRateEntity existing = repository.findBySymbolAndFundingTime(
              requested.getSymbol(),
              requested.getFundingTime())
          .orElseThrow(() -> conflict("Funding rate replay row is missing"));
      if (!same(existing, requested)) {
        throw conflict("Funding rate replay conflicts with persisted authority");
      }
    }
    return inserted;
  }

  public void requireExactPersisted(CompositeTick tick) {
    List<FundingRateEntity> requested = requestedRows(tick);
    Map<String, FundingRateEntity> expected = requested.stream()
        .collect(Collectors.toMap(FundingRateEntity::getSymbol, Function.identity()));
    List<FundingRateEntity> persisted = repository.findByFundingTime(tick.virtualTime());
    if (persisted.size() != expected.size()) {
      throw conflict("Funding rate replay set conflicts with persisted authority");
    }
    for (FundingRateEntity existing : persisted) {
      FundingRateEntity exact = expected.get(existing.getSymbol());
      if (exact == null || !same(existing, exact)) {
        throw conflict("Funding rate replay conflicts with persisted authority");
      }
    }
  }

  private static List<FundingRateEntity> requestedRows(CompositeTick tick) {
    Objects.requireNonNull(tick, "tick");
    ValidationMarketState.validateComplete(tick);
    Map<String, PerpetualMarketBundle> perpetuals = tick.perpetualBundles().stream()
        .collect(Collectors.toUnmodifiableMap(
            PerpetualMarketBundle::platformSymbol,
            Function.identity()));
    return tick.fundingRates().stream()
        .map(point -> row(tick, point, perpetuals.get(point.platformSymbol())))
        .toList();
  }

  private static FundingRateEntity row(
      CompositeTick tick,
      FundingRatePoint point,
      PerpetualMarketBundle market
  ) {
    if (market == null) {
      throw conflict("Funding rate symbol is absent from the market Tick");
    }
    BigDecimal mark;
    try {
      mark = market.mark().setScale(10, RoundingMode.UNNECESSARY);
      if (mark.signum() <= 0 || mark.precision() > 24) {
        throw new ArithmeticException("mark");
      }
    } catch (RuntimeException invalid) {
      throw new BusinessException(
          "VALIDATION_FUNDING_MARK_INVALID",
          "Validation funding mark is outside NUMERIC(24,10)");
    }
    String identity = tick.runId()
        + "|" + tick.generation()
        + "|" + tick.sequence()
        + "|" + point.platformSymbol();
    String canonical = identity
        + "|" + point.fundingRate().toPlainString()
        + "|" + mark.toPlainString()
        + "|" + tick.virtualTime();

    FundingRateEntity row = new FundingRateEntity();
    row.setId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)));
    row.setSymbol(point.platformSymbol());
    row.setFundingRate(point.fundingRate());
    row.setFundingTime(tick.virtualTime());
    row.setNextFundingTime(tick.virtualTime().plusSeconds(INTERVAL_MINUTES * 60L));
    row.setMarkPrice(mark);
    row.setProviderCode("VALIDATION");
    row.setSourceMode("DEMO");
    row.setAsOf(tick.virtualTime());
    row.setIntervalMinutes(INTERVAL_MINUTES);
    row.setRawPayloadHash(sha256(canonical));
    return row;
  }

  private static boolean same(FundingRateEntity left, FundingRateEntity right) {
    return Objects.equals(left.getId(), right.getId())
        && Objects.equals(left.getSymbol(), right.getSymbol())
        && decimalEquals(left.getFundingRate(), right.getFundingRate())
        && Objects.equals(left.getFundingTime(), right.getFundingTime())
        && Objects.equals(left.getNextFundingTime(), right.getNextFundingTime())
        && decimalEquals(left.getMarkPrice(), right.getMarkPrice())
        && Objects.equals(left.getProviderCode(), right.getProviderCode())
        && Objects.equals(left.getSourceMode(), right.getSourceMode())
        && Objects.equals(left.getAsOf(), right.getAsOf())
        && Objects.equals(left.getIntervalMinutes(), right.getIntervalMinutes())
        && Objects.equals(left.getRawPayloadHash(), right.getRawPayloadHash());
  }

  private static boolean decimalEquals(BigDecimal left, BigDecimal right) {
    return left != null && right != null && left.compareTo(right) == 0;
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static BusinessException conflict(String message) {
    return new BusinessException("VALIDATION_FUNDING_RATE_CONFLICT", message);
  }
}
