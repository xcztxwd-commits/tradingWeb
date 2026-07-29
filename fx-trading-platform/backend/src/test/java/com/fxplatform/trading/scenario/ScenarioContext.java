package com.fxplatform.trading.scenario;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.market.model.ProductType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ScenarioContext {

  private final ScenarioDefinition scenario;
  private final UUID userId;
  private final String email;
  private final UserPrincipal principal;
  private final TradingAccountEntity account;
  private final Map<String, UUID> references = new LinkedHashMap<>();
  private final Map<UUID, List<String>> logicalReferences = new LinkedHashMap<>();
  private volatile ScenarioPriceStep currentPriceStep;

  public ScenarioContext(
      ScenarioDefinition scenario,
      UUID userId,
      String email,
      UserPrincipal principal,
      TradingAccountEntity account
  ) {
    this.scenario = Objects.requireNonNull(scenario, "scenario");
    this.userId = Objects.requireNonNull(userId, "userId");
    this.email = Objects.requireNonNull(email, "email");
    this.principal = Objects.requireNonNull(principal, "principal");
    this.account = Objects.requireNonNull(account, "account");
    Objects.requireNonNull(account.getId(), "account.id");
    this.currentPriceStep = scenario.priceSteps().getFirst();
  }

  public ScenarioDefinition scenario() {
    return scenario;
  }

  public UUID userId() {
    return userId;
  }

  public UUID accountId() {
    return account.getId();
  }

  public String email() {
    return email;
  }

  public UserPrincipal principal() {
    return principal;
  }

  public TradingAccountEntity account() {
    return account;
  }

  public String symbol() {
    return scenario.productType() == ProductType.CRYPTO_SPOT
        ? "BTCUSDT"
        : "BTCUSDT-PERP";
  }

  public String baseAsset() {
    return "BTC";
  }

  public String quoteAsset() {
    return "USDT";
  }

  public ScenarioPriceStep currentPriceStep() {
    return currentPriceStep;
  }

  public void currentPriceStep(ScenarioPriceStep value) {
    currentPriceStep = Objects.requireNonNull(value, "currentPriceStep");
  }

  public synchronized UUID putRef(String logicalRef, UUID id) {
    String normalized = requireRefText(logicalRef);
    Objects.requireNonNull(id, "id");
    UUID existing = references.putIfAbsent(normalized, id);
    if (existing != null && !existing.equals(id)) {
      throw new IllegalStateException(
          "Logical reference " + normalized + " is already bound to " + existing);
    }
    logicalReferences.computeIfAbsent(id, ignored -> new ArrayList<>());
    List<String> aliases = logicalReferences.get(id);
    if (!aliases.contains(normalized)) {
      aliases.add(normalized);
    }
    return id;
  }

  public synchronized UUID rebindRef(String logicalRef, UUID id) {
    String normalized = requireRefText(logicalRef);
    Objects.requireNonNull(id, "id");
    references.put(normalized, id);
    logicalReferences.computeIfAbsent(id, ignored -> new ArrayList<>());
    List<String> aliases = logicalReferences.get(id);
    if (!aliases.contains(normalized)) {
      aliases.add(normalized);
    }
    return id;
  }

  public synchronized Optional<UUID> findRef(String logicalRef) {
    if (logicalRef == null || logicalRef.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(references.get(logicalRef.trim()));
  }

  public synchronized UUID requireRef(String logicalRef) {
    return findRef(logicalRef).orElseThrow(() ->
        new IllegalStateException("No database id is bound to logical reference " + logicalRef));
  }

  public synchronized List<String> logicalRefs(UUID id) {
    List<String> values = logicalReferences.get(id);
    return values == null ? List.of() : List.copyOf(values);
  }

  public synchronized Optional<String> findLogicalRef(UUID id) {
    return logicalRefs(id).stream().findFirst();
  }

  public synchronized Map<String, UUID> references() {
    return Map.copyOf(references);
  }

  private static String requireRefText(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("logicalRef must not be blank");
    }
    return normalized;
  }
}
