package com.fxplatform.validation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.validation.service.ValidationMarketPathService.PathRequest;
import com.fxplatform.validation.service.ValidationRunEngine.AccountConfigurationPlan;
import com.fxplatform.validation.service.ValidationRunEngine.PublicAction;
import com.fxplatform.validation.service.ValidationRunEngine.PublicActionType;
import com.fxplatform.validation.service.ValidationRunEngine.SeedPlan;
import com.fxplatform.validation.service.ValidationRunEngine.StartRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Fixed-route loopback client. Runtime credentials exist only in this process-local registry. */
@Profile("validation")
@Component
public class JdkValidationLoopbackHttpClient
    implements ValidationLoopbackHttpClient, ValidationRuntimeResetParticipant {

  private static final String REGISTER_PATH = "/api/auth/register";
  private static final String LOGIN_PATH = "/api/auth/login";
  private static final String ACCOUNTS_PATH = "/api/accounts";
  private static final String TRADING_ORDERS_PATH = "/api/trading/orders";
  private static final String TRADING_TRADES_PATH = "/api/trading/trades";
  private static final String TRADING_POSITIONS_PATH = "/api/trading/positions";
  private static final String FUNDING_SETTLEMENTS_PATH = "/api/trading/funding/settlements";
  private static final String LEDGER_PATH = "/api/ledger";
  private static final String INTERNAL_VALIDATION_PATH = "/internal/validation";
  private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
  private static final int MAX_TRACE_BODY_BYTES = 64 * 1024;
  private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(20);
  private static final Set<String> SENSITIVE_KEY_PARTS = Set.of(
      "authorization", "cookie", "password", "secret", "token", "credential", "apikey",
      "api_key");
  private static final Pattern SENSITIVE_TEXT_PATTERN = Pattern.compile(
      "(?i)(?:\\bbearer\\s+\\S+|"
          + "(?:authorization|password|secret|token|cookie|credential|api[-_]?key)"
          + "\\s*[=:]\\s*\\S+)");

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final ObjectReader strictJsonReader;
  private final String internalSecret;
  private final Duration requestTimeout;
  private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
  private final ThreadLocal<ArrayList<HttpHop>> capturedHops = new ThreadLocal<>();

  @Autowired
  public JdkValidationLoopbackHttpClient(
      ObjectMapper objectMapper,
      @Value("${validation.internal.secret}") String internalSecret
  ) {
    this(objectMapper, internalSecret, DEFAULT_REQUEST_TIMEOUT);
  }

  JdkValidationLoopbackHttpClient(
      ObjectMapper objectMapper,
      String internalSecret,
      Duration requestTimeout
  ) {
    if (internalSecret == null || internalSecret.length() < 32) {
      throw new IllegalArgumentException("Validation internal secret is required");
    }
    if (requestTimeout == null
        || requestTimeout.isZero()
        || requestTimeout.isNegative()
        || requestTimeout.compareTo(DEFAULT_REQUEST_TIMEOUT) > 0) {
      throw new IllegalArgumentException("Validation loopback timeout is outside the fixed bound");
    }
    this.objectMapper = objectMapper;
    this.strictJsonReader = objectMapper.reader()
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    this.internalSecret = internalSecret;
    this.requestTimeout = requestTimeout;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  @Override
  public HttpResult execute(Command request) {
    return captureHops(() -> switch (request.operation()) {
      case REGISTER -> register(request);
      case SEED_ACCOUNT -> seed(request);
      case CONFIGURE_ACCOUNT -> configureAccount(request);
      case START_MARKET_PATH -> startMarketPath(request);
      case SYSTEM_STEP -> systemStep(request);
      case PUBLIC_ACTION -> publicAction(request);
      case QUERY_STATE -> queryState(request);
    });
  }

  @Override
  public LookupResult lookup(Command request) {
    return captureHops(() -> {
      try {
        return switch (request.operation()) {
          case REGISTER -> {
            Session session = requireSession(request.runId(), request.generation());
            yield new LookupResult(
                LookupStatus.FOUND,
                metadataResult(request, session, true));
          }
          case PUBLIC_ACTION -> lookupPublicAction(request);
          case CONFIGURE_ACCOUNT -> lookupAccountConfiguration(request);
          case SEED_ACCOUNT, START_MARKET_PATH, SYSTEM_STEP, QUERY_STATE ->
              new LookupResult(LookupStatus.DEFINITELY_ABSENT, null);
        };
      } catch (RuntimeException ignored) {
        return new LookupResult(LookupStatus.UNKNOWN, null);
      }
    });
  }

  @Override
  public HttpResult rehydrate(Command request) {
    return captureHops(() -> switch (request.operation()) {
      case REGISTER -> {
        LookupResult lookup = lookup(request);
        if (lookup.status() != LookupStatus.FOUND) {
          throw failure(
              "VALIDATION_SESSION_RECOVERY_FAILED",
              "Validation registration session could not be rehydrated");
        }
        yield lookup.result();
      }
      case START_MARKET_PATH, SYSTEM_STEP -> execute(request);
      case SEED_ACCOUNT, CONFIGURE_ACCOUNT, PUBLIC_ACTION, QUERY_STATE -> throw failure(
          "VALIDATION_REHYDRATION_REJECTED",
          "Completed validation business operations cannot be replayed for rehydration");
    });
  }

  @Override
  public void clearForGeneration(long generation) {
    if (generation <= 0L) {
      throw new IllegalArgumentException("Validation generation must be positive");
    }
    sessions.clear();
  }

  private HttpResult register(Command command) {
    Credentials credentials = credentials(command.runId());
    RawResponse response = send(
        "POST",
        REGISTER_PATH,
        Map.of(
            "email", credentials.email(),
            "password", credentials.password()),
        Authentication.none(),
        command);
    if (!successful(response.status())) {
      response = send(
          "POST",
          LOGIN_PATH,
          Map.of("email", credentials.email(), "password", credentials.password()),
          Authentication.none(),
          command);
    }
    if (!successful(response.status())) {
      return result(command, response);
    }
    Session session = establishSession(command, credentials.email(), response);
    return metadataResult(command, session, false);
  }

  private HttpResult seed(Command command) {
    Session session = requireSession(command.runId(), command.generation());
    SeedPlan seed = objectMapper.convertValue(command.body(), SeedPlan.class);
    ValidationAccountSeedRequest body = new ValidationAccountSeedRequest(
        seed.seedId(),
        seed.balances());
    RawResponse response = send(
        "POST",
        INTERNAL_VALIDATION_PATH + "/accounts/" + session.accountId() + "/seed",
        body,
        Authentication.internalAuth(),
        command);
    return result(command, response);
  }

  private HttpResult configureAccount(Command command) {
    Session session = requireSession(command.runId(), command.generation());
    AccountConfigurationPlan plan = objectMapper.convertValue(
        command.body(), AccountConfigurationPlan.class);
    RawResponse response = send(
        "PATCH",
        ACCOUNTS_PATH + "/" + session.accountId() + "/position-mode",
        Map.of("positionMode", plan.settings().positionMode()),
        Authentication.bearer(session.accessToken()),
        command);
    if (!successful(response.status())) {
      return result(command, response);
    }
    for (String requestedSymbol : plan.perpetualSymbols()) {
      String symbol = safeSymbol(requestedSymbol);
      SymbolSetting current = currentSymbolSetting(command, session, symbol);
      response = send(
          "PATCH",
          ACCOUNTS_PATH + "/" + session.accountId() + "/symbols/" + symbol + "/settings",
          Map.of(
              "leverage", plan.settings().leverage(),
              "marginMode", plan.settings().marginMode(),
              "quantityUnit", plan.settings().quantityUnit(),
              "expectedVersion", current.version()),
          Authentication.bearer(session.accessToken()),
          command);
      if (!successful(response.status())) {
        return result(command, response);
      }
    }
    return result(command, response);
  }

  private HttpResult startMarketPath(Command command) {
    StartRequest run = objectMapper.convertValue(command.body(), StartRequest.class);
    PathRequest body = new PathRequest(
        run.runId(),
        run.generation(),
        run.requestFingerprint(),
        run.virtualStart(),
        run.executionPolicy(),
        run.ticks());
    RawResponse response = send(
        "POST",
        INTERNAL_VALIDATION_PATH + "/market/path",
        body,
        Authentication.internalAuth(),
        command);
    return result(command, response);
  }

  private HttpResult systemStep(Command command) {
    RawResponse response = send(
        "POST",
        INTERNAL_VALIDATION_PATH + "/system/step",
        command.body(),
        Authentication.internalAuth(),
        command);
    return result(command, response);
  }

  private HttpResult publicAction(Command command) {
    PublicAction action = validatedPublicAction(command);
    Session session = requireSession(command.runId(), command.generation());
    LinkedHashMap<String, Object> payload = new LinkedHashMap<>(action.payload());
    RawResponse response = switch (action.type()) {
      case PLACE_ORDER -> {
        payload.put("accountId", session.accountId());
        payload.put("idempotencyKey", command.idempotencyKey());
        payload.put("clientOrderId", command.idempotencyKey());
        yield send(
            "POST",
            TRADING_ORDERS_PATH,
            payload,
            Authentication.bearer(session.accessToken()),
            command);
      }
      case CANCEL_ORDER -> cancelOrder(command, session, payload);
      case CANCEL_ALL -> send(
          "POST",
          TRADING_ORDERS_PATH + "/cancel-all",
          Map.of(
              "accountId", session.accountId(),
              "requestId", UUID.nameUUIDFromBytes(
                  command.idempotencyKey().getBytes(StandardCharsets.UTF_8))),
          Authentication.bearer(session.accessToken()),
          command);
      case SET_POSITION_MODE -> send(
          "PATCH",
          ACCOUNTS_PATH + "/" + session.accountId() + "/position-mode",
          Map.of("positionMode", requiredText(payload, "positionMode")),
          Authentication.bearer(session.accessToken()),
          command);
      case SET_MARGIN_MODE, SET_LEVERAGE -> updateSymbolSettings(
          command, session, action.type(), payload);
    };
    return result(command, response);
  }

  private RawResponse cancelOrder(
      Command command,
      Session session,
      Map<String, Object> payload
  ) {
    RawResponse lookup = send(
        "GET",
        orderByClientOrderIdPath(
            session.accountId(),
            requiredText(payload, "clientOrderId")),
        null,
        Authentication.bearer(session.accessToken()),
        command);
    if (!successful(lookup.status())) {
      return lookup;
    }
    JsonNode data = lookup.body().path("data");
    if (data.isMissingNode() || data.isNull()) {
      return new RawResponse(404, objectMapper.createObjectNode());
    }
    UUID orderId;
    try {
      orderId = UUID.fromString(data.path("id").asText());
    } catch (IllegalArgumentException exception) {
      throw failure(
          "VALIDATION_ORDER_LOOKUP_INVALID",
          "Validation order lookup response was invalid");
    }
    return send(
        "POST",
        TRADING_ORDERS_PATH + "/" + orderId + "/cancel",
        null,
        Authentication.bearer(session.accessToken()),
        command);
  }

  private RawResponse updateSymbolSettings(
      Command command,
      Session session,
      PublicActionType actionType,
      Map<String, Object> payload
  ) {
    String symbol = safeSymbol(requiredText(payload, "symbol"));
    SymbolSetting current = currentSymbolSetting(command, session, symbol);
    Object leverage = actionType == PublicActionType.SET_LEVERAGE
        ? payload.get("leverage")
        : current.leverage();
    Object marginMode = actionType == PublicActionType.SET_MARGIN_MODE
        ? payload.get("marginMode")
        : current.marginMode();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("leverage", leverage == null ? 10 : leverage);
    body.put("marginMode", marginMode == null ? "CROSS" : marginMode);
    body.put("quantityUnit", current.quantityUnit() == null ? "BASE" : current.quantityUnit());
    body.put("expectedVersion", current.version());
    return send(
        "PATCH",
        ACCOUNTS_PATH + "/" + session.accountId() + "/symbols/" + symbol + "/settings",
        body,
        Authentication.bearer(session.accessToken()),
        command);
  }

  private HttpResult queryState(Command command) {
    Session session = requireSession(command.runId(), command.generation());
    LinkedHashMap<String, Object> state = new LinkedHashMap<>();
    List<StateQuery> directQueries = List.of(
        new StateQuery(
            "summary",
            ACCOUNTS_PATH + "/" + session.accountId() + "/summary",
            DirectStateShape.ACCOUNT),
        new StateQuery(
            "walletBalances",
            ACCOUNTS_PATH + "/" + session.accountId() + "/wallet-balances",
            DirectStateShape.ACCOUNT_SCOPED_LIST),
        new StateQuery(
            "assetLedger",
            ACCOUNTS_PATH + "/" + session.accountId() + "/asset-ledger",
            DirectStateShape.ACCOUNT_SCOPED_LIST),
        new StateQuery(
            "cashLedger",
            LEDGER_PATH + "?accountId=" + session.accountId(),
            DirectStateShape.ACCOUNT_SCOPED_LIST));
    for (StateQuery query : directQueries) {
      RawResponse response = send(
          "GET",
          query.path(),
          null,
          Authentication.bearer(session.accessToken()),
          command);
      if (!successful(response.status())) {
        return result(command, response);
      }
      JsonNode sanitized = sanitize(response.body(), command.runId());
      state.put(query.name(), directStateValue(query, sanitized, session.accountId()));
    }
    List<PagedStateQuery> pagedQueries = List.of(
        new PagedStateQuery("orders", TRADING_ORDERS_PATH),
        new PagedStateQuery("trades", TRADING_TRADES_PATH),
        new PagedStateQuery("positions", TRADING_POSITIONS_PATH),
        new PagedStateQuery("fundingSettlements", FUNDING_SETTLEMENTS_PATH));
    for (PagedStateQuery query : pagedQueries) {
      ValidationStatePageCollector collector = new ValidationStatePageCollector(objectMapper);
      for (int page = 0; !collector.complete(); page++) {
        RawResponse response = send(
            "GET",
            pagePath(query.rootPath(), session.accountId(), page),
            null,
            Authentication.bearer(session.accessToken()),
            command);
        if (!successful(response.status())) {
          return result(command, response);
        }
        JsonNode sanitized = sanitize(response.body(), command.runId());
        collector.append(page, successfulData(sanitized));
      }
      state.put(query.name(), collector.snapshot());
    }
    return new HttpResult(
        200,
        correlation(command),
        Collections.unmodifiableMap(new LinkedHashMap<>(state)),
        snapshotHops());
  }

  private LookupResult lookupPublicAction(Command command) {
    PublicAction action = validatedPublicAction(command);
    Session session = requireSession(command.runId(), command.generation());
    return switch (action.type()) {
      case PLACE_ORDER -> lookupCreatedOrder(command, session);
      case CANCEL_ORDER -> lookupCancelledOrder(command, session, action);
      case CANCEL_ALL -> new LookupResult(LookupStatus.DEFINITELY_ABSENT, null);
      case SET_POSITION_MODE, SET_MARGIN_MODE, SET_LEVERAGE ->
          lookupSettings(command, session, action);
    };
  }

  private LookupResult lookupAccountConfiguration(Command command) {
    Session session = requireSession(command.runId(), command.generation());
    AccountConfigurationPlan plan = objectMapper.convertValue(
        command.body(), AccountConfigurationPlan.class);
    RawResponse response = settings(command, session);
    if (!successful(response.status())) {
      return new LookupResult(LookupStatus.UNKNOWN, null);
    }
    JsonNode data = response.body().path("data");
    JsonNode symbols = data.path("symbols");
    if (!data.isObject()
        || !data.path("positionMode").isTextual()
        || !symbols.isArray()) {
      return new LookupResult(LookupStatus.UNKNOWN, null);
    }
    if (!plan.settings().positionMode().equals(data.path("positionMode").textValue())) {
      return new LookupResult(LookupStatus.DEFINITELY_ABSENT, null);
    }
    for (String requestedSymbol : plan.perpetualSymbols()) {
      String symbol = safeSymbol(requestedSymbol);
      JsonNode matched = null;
      for (JsonNode candidate : symbols) {
        if (symbol.equals(candidate.path("symbol").asText(null))) {
          matched = candidate;
          break;
        }
      }
      if (matched == null
          || !matched.path("leverage").isIntegralNumber()
          || matched.path("leverage").intValue() != plan.settings().leverage()
          || !plan.settings().marginMode().equals(matched.path("marginMode").asText(null))
          || !plan.settings().quantityUnit().equals(matched.path("quantityUnit").asText(null))) {
        return new LookupResult(LookupStatus.DEFINITELY_ABSENT, null);
      }
    }
    return new LookupResult(LookupStatus.FOUND, result(command, response));
  }

  private LookupResult lookupCreatedOrder(Command command, Session session) {
    RawResponse response = send(
        "GET",
        orderByClientOrderIdPath(session.accountId(), command.idempotencyKey()),
        null,
        Authentication.bearer(session.accessToken()),
        command);
    if (!successful(response.status())) {
      return new LookupResult(LookupStatus.UNKNOWN, null);
    }
    JsonNode data = response.body().path("data");
    return data.isMissingNode() || data.isNull()
        ? new LookupResult(LookupStatus.DEFINITELY_ABSENT, null)
        : new LookupResult(LookupStatus.FOUND, result(command, response));
  }

  private LookupResult lookupCancelledOrder(
      Command command,
      Session session,
      PublicAction action
  ) {
    RawResponse response = send(
        "GET",
        orderByClientOrderIdPath(
            session.accountId(),
            requiredText(action.payload(), "clientOrderId")),
        null,
        Authentication.bearer(session.accessToken()),
        command);
    if (!successful(response.status())) {
      return new LookupResult(LookupStatus.UNKNOWN, null);
    }
    JsonNode data = response.body().path("data");
    if (data.isMissingNode() || data.isNull()) {
      return new LookupResult(LookupStatus.UNKNOWN, null);
    }
    String status = data.path("status").asText();
    return Set.of("CANCELED", "CANCELLED", "FILLED", "REJECTED", "EXPIRED", "FAILED")
        .contains(status)
            ? new LookupResult(LookupStatus.FOUND, result(command, response))
            : new LookupResult(LookupStatus.DEFINITELY_ABSENT, null);
  }

  private LookupResult lookupSettings(
      Command command,
      Session session,
      PublicAction action
  ) {
    RawResponse response = settings(command, session);
    if (!successful(response.status())) {
      return new LookupResult(LookupStatus.UNKNOWN, null);
    }
    JsonNode data = response.body().path("data");
    if (action.type() == PublicActionType.SET_POSITION_MODE) {
      boolean found = requiredText(action.payload(), "positionMode")
          .equals(data.path("positionMode").asText());
      return found
          ? new LookupResult(LookupStatus.FOUND, result(command, response))
          : new LookupResult(LookupStatus.DEFINITELY_ABSENT, null);
    }
    String symbol = safeSymbol(requiredText(action.payload(), "symbol"));
    for (JsonNode item : data.path("symbols")) {
      if (!symbol.equals(item.path("symbol").asText())) {
        continue;
      }
      String field = action.type() == PublicActionType.SET_LEVERAGE ? "leverage" : "marginMode";
      boolean found = String.valueOf(action.payload().get(field))
          .equals(item.path(field).asText());
      return found
          ? new LookupResult(LookupStatus.FOUND, result(command, response))
          : new LookupResult(LookupStatus.DEFINITELY_ABSENT, null);
    }
    return new LookupResult(LookupStatus.DEFINITELY_ABSENT, null);
  }

  private Session requireSession(UUID runId, long generation) {
    Session existing = sessions.get(runId);
    if (existing != null && existing.generation() == generation) {
      return existing;
    }
    Credentials credentials = credentials(runId);
    Command recovery = new Command(
        Operation.REGISTER,
        runId,
        generation,
        0L,
        runId + ":session-recovery",
        runId + ":session-recovery",
        Map.of());
    RawResponse login = send(
        "POST",
        LOGIN_PATH,
        Map.of("email", credentials.email(), "password", credentials.password()),
        Authentication.none(),
        recovery);
    if (!successful(login.status())) {
      throw failure(
          "VALIDATION_SESSION_RECOVERY_FAILED",
          "Validation loopback session could not be recovered");
    }
    return establishSession(recovery, credentials.email(), login);
  }

  private Session establishSession(Command command, String email, RawResponse authResponse) {
    JsonNode data = authResponse.body().path("data");
    String accessToken = data.path("accessToken").asText(null);
    String userId = data.path("userId").asText(null);
    if (accessToken == null || accessToken.isBlank() || userId == null) {
      throw failure("VALIDATION_SESSION_INVALID", "Validation authentication response is incomplete");
    }
    RawResponse accounts = send(
        "GET",
        ACCOUNTS_PATH,
        null,
        Authentication.bearer(accessToken),
        command);
    if (!successful(accounts.status())) {
      throw failure("VALIDATION_ACCOUNT_LOOKUP_FAILED", "Validation Demo account lookup failed");
    }
    UUID accountId = null;
    for (JsonNode account : accounts.body().path("data")) {
      if ("DEMO".equals(account.path("accountType").asText())) {
        accountId = UUID.fromString(account.path("id").asText());
        break;
      }
    }
    if (accountId == null) {
      throw failure("VALIDATION_DEMO_ACCOUNT_MISSING", "Validation Demo account was not created");
    }
    Session session = new Session(
        command.generation(),
        UUID.fromString(userId),
        accountId,
        email,
        accessToken);
    sessions.put(command.runId(), session);
    return session;
  }

  private SymbolSetting currentSymbolSetting(
      Command command,
      Session session,
      String symbol
  ) {
    RawResponse response = settings(command, session);
    if (!successful(response.status())) {
      throw failure("VALIDATION_SETTINGS_LOOKUP_FAILED", "Validation settings lookup failed");
    }
    for (JsonNode item : response.body().path("data").path("symbols")) {
      if (symbol.equals(item.path("symbol").asText())) {
        return new SymbolSetting(
            item.path("leverage").isNumber() ? item.path("leverage").intValue() : 10,
            item.path("marginMode").asText("CROSS"),
            item.path("quantityUnit").asText("BASE"),
            item.path("version").asLong(0L));
      }
    }
    return new SymbolSetting(10, "CROSS", "BASE", 0L);
  }

  private RawResponse settings(Command command, Session session) {
    return send(
        "GET",
        ACCOUNTS_PATH + "/" + session.accountId() + "/trading-settings",
        null,
        Authentication.bearer(session.accessToken()),
        command);
  }

  private PublicAction validatedPublicAction(Command command) {
    PublicAction action = objectMapper.convertValue(command.body(), PublicAction.class);
    ValidationPublicActionPayloadPolicy.validate(action.type(), action.payload());
    return action;
  }

  private RawResponse send(
      String method,
      String path,
      Object body,
      Authentication authentication,
      Command command
  ) {
    if (!path.startsWith("/") || path.contains("://") || path.contains("\\")) {
      throw new IllegalArgumentException("Loopback route is not a fixed local path");
    }
    HttpRequest.Builder builder = HttpRequest.newBuilder(
            URI.create(LOOPBACK_BASE_URI.toString() + path))
        .timeout(requestTimeout)
        .header("Accept", "application/json")
        .header("X-Validation-Correlation-Id", correlation(command))
        .header("X-Validation-Generation", Long.toString(command.generation()));
    if (authentication.internal()) {
      builder.header("X-Validation-Internal-Token", internalSecret);
    }
    if (authentication.bearerToken() != null) {
      builder.header("Authorization", "Bearer " + authentication.bearerToken());
    }
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.header("Content-Type", "application/json");
      builder.method(method, HttpRequest.BodyPublishers.ofString(json(body)));
    }
    long startedNanos = System.nanoTime();
    Instant realTime = Instant.now();
    try {
      ValidationBoundedHttpExchange.Result response =
          ValidationBoundedHttpExchange.send(
              httpClient,
              builder.build(),
              requestTimeout,
              MAX_RESPONSE_BYTES);
      byte[] bytes = response.body();
      if (response.bodyTooLarge()) {
        if (successful(response.statusCode())) {
          throw failure(
              "VALIDATION_LOOPBACK_RESPONSE_TOO_LARGE",
              "Validation loopback response exceeded its fixed bound");
        }
        ObjectNode sanitized = objectMapper.createObjectNode();
        sanitized.put("responseTooLarge", true);
        return observe(
            command,
            method,
            path,
            body,
            response.statusCode(),
            sanitized,
            startedNanos,
            realTime);
      }
      JsonNode responseBody;
      if (bytes.length == 0) {
        responseBody = objectMapper.createObjectNode();
      } else {
        try {
          responseBody = strictJsonReader.readTree(bytes);
        } catch (IOException malformed) {
          ObjectNode sanitized = objectMapper.createObjectNode();
          sanitized.put("malformedBody", true);
          responseBody = sanitized;
        }
      }
      return observe(
          command,
          method,
          path,
          body,
          response.statusCode(),
          responseBody,
          startedNanos,
          realTime);
    } catch (HttpTimeoutException exception) {
      throw timeoutFailure();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw failure("VALIDATION_LOOPBACK_INTERRUPTED", "Validation loopback request was interrupted");
    } catch (IOException exception) {
      throw failure("VALIDATION_LOOPBACK_UNAVAILABLE", "Validation loopback request failed");
    }
  }

  private static BusinessException timeoutFailure() {
    return failure(
        "VALIDATION_LOOPBACK_UNAVAILABLE",
        "Validation loopback response timed out");
  }

  private HttpResult result(Command command, RawResponse response) {
    return new HttpResult(
        response.status(),
        correlation(command),
        objectMap(sanitize(response.body(), command.runId())),
        snapshotHops());
  }

  private HttpResult metadataResult(Command command, Session session, boolean recovered) {
    return new HttpResult(
        200,
        correlation(command),
        Map.of(
            "userId", session.userId().toString(),
            "accountId", session.accountId().toString(),
            "credentialType", "DERIVED_EPHEMERAL",
            "recovered", recovered),
        snapshotHops());
  }

  private RawResponse observe(
      Command command,
      String method,
      String path,
      Object requestBody,
      int status,
      JsonNode responseBody,
      long startedNanos,
      Instant realTime
  ) {
    long elapsedNanos = Math.max(0L, System.nanoTime() - startedNanos);
    long durationMillis = Math.min(
        120_000L,
        Math.max(0L, (elapsedNanos + 999_999L) / 1_000_000L));
    SafeTraceRoute route = safeTraceRoute(path, command.runId());
    ArrayList<HttpHop> hops = capturedHops.get();
    if (hops == null || hops.size() >= MAX_HTTP_HOPS) {
      throw failure(
          "VALIDATION_HTTP_TRACE_LIMIT_EXCEEDED",
          "Validation HTTP trace exceeded its fixed bound");
    }
    hops.add(new HttpHop(
        method,
        route.path(),
        status,
        correlation(command),
        durationMillis,
        safeTrace(
            command,
            method,
            route,
            requestBody,
            status,
            responseBody,
            durationMillis,
            realTime)));
    return new RawResponse(status, responseBody);
  }

  private Map<String, Object> safeTrace(
      Command command,
      String method,
      SafeTraceRoute route,
      Object requestBody,
      int status,
      JsonNode responseBody,
      long durationMillis,
      Instant realTime
  ) {
    String url = LOOPBACK_BASE_URI + route.path();
    LinkedHashMap<String, Object> request = new LinkedHashMap<>();
    request.put("sequence", command.tickSequence());
    request.put("environment", "validation");
    request.put("method", method);
    request.put("url", route.requestUrl());
    request.put("virtualTime", null);
    request.put("realTime", realTime.toString());
    request.put("sanitizedRequest", boundedTraceValue(
        requestBody == null ? null : objectMapper.valueToTree(requestBody),
        command.runId()));
    LinkedHashMap<String, Object> response = new LinkedHashMap<>();
    response.put("status", status);
    response.put("duration", durationMillis);
    response.put("traceId", null);
    response.put("correlationId", correlation(command));
    response.put("recordedException", null);
    response.put("sanitizedResponse", boundedTraceValue(responseBody, command.runId()));
    LinkedHashMap<String, Object> trace = new LinkedHashMap<>();
    trace.put("url", url);
    trace.put("queryParameters", route.queryParameters());
    trace.put("requestHeaders", Map.of());
    trace.put("requestContentType", requestBody == null ? null : "application/json");
    trace.put("requestBody", request);
    trace.put("responseHeaders", Map.of());
    trace.put("responseContentType", "application/json");
    trace.put("responseBody", response);
    trace.put("exception", null);
    trace.put("authentication", null);
    return trace;
  }

  private Object boundedTraceValue(JsonNode raw, UUID runId) {
    if (raw == null || raw.isNull()) {
      return null;
    }
    JsonNode safe = sanitize(raw, runId);
    try {
      if (objectMapper.writeValueAsBytes(safe).length > MAX_TRACE_BODY_BYTES) {
        return Map.of("bodyOmitted", true, "reason", "TRACE_BODY_LIMIT");
      }
    } catch (JsonProcessingException invalid) {
      throw failure(
          "VALIDATION_HTTP_TRACE_INVALID",
          "Validation HTTP trace could not be bounded");
    }
    return objectValue(safe);
  }

  private SafeTraceRoute safeTraceRoute(String path, UUID runId) {
    URI uri = URI.create(path);
    String rawPath = uri.getRawPath();
    if (rawPath == null || rawPath.isBlank()) {
      throw new IllegalArgumentException("Loopback route has no safe path");
    }
    String rawQuery = uri.getRawQuery();
    if (rawQuery == null || rawQuery.isEmpty()) {
      return new SafeTraceRoute(rawPath, LOOPBACK_BASE_URI + rawPath, Map.of());
    }
    if (rawQuery.length() > 16_384) {
      throw new IllegalArgumentException("Loopback query exceeds its fixed bound");
    }
    LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
    StringBuilder safeQuery = new StringBuilder();
    int parameterCount = 0;
    for (String pair : rawQuery.split("&", -1)) {
      if (pair.isEmpty()) {
        continue;
      }
      if (++parameterCount > 128) {
        throw new IllegalArgumentException("Loopback query exceeds its fixed bound");
      }
      int separator = pair.indexOf('=');
      String key = decodeQuery(separator < 0 ? pair : pair.substring(0, separator));
      String value = decodeQuery(separator < 0 ? "" : pair.substring(separator + 1));
      if (key.length() > 512 || value.length() > 4_096) {
        throw new IllegalArgumentException("Loopback query exceeds its fixed bound");
      }
      if (sensitiveKey(key)) {
        continue;
      }
      String safeValue = safeQueryValue(value, runId);
      @SuppressWarnings("unchecked")
      List<String> values = (List<String>) parameters.computeIfAbsent(
          key, ignored -> new ArrayList<String>());
      values.add(safeValue);
      if (!safeQuery.isEmpty()) {
        safeQuery.append('&');
      }
      safeQuery.append(query(key)).append('=').append(query(safeValue));
    }
    String requestUrl = LOOPBACK_BASE_URI + rawPath
        + (safeQuery.isEmpty() ? "" : "?" + safeQuery);
    return new SafeTraceRoute(
        rawPath,
        requestUrl,
        Collections.unmodifiableMap(parameters));
  }

  private String safeQueryValue(String value, UUID runId) {
    Session session = sessions.get(runId);
    Credentials credentials = credentials(runId);
    return containsSecret(value, internalSecret)
        || containsSecret(value, credentials.password())
        || (session != null && containsSecret(value, session.accessToken()))
        || SENSITIVE_TEXT_PATTERN.matcher(value).find()
            ? "[REDACTED]"
            : value;
  }

  private static String decodeQuery(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException("Loopback query is malformed", malformed);
    }
  }

  private List<HttpHop> snapshotHops() {
    ArrayList<HttpHop> hops = capturedHops.get();
    return hops == null ? List.of() : List.copyOf(hops);
  }

  private <T> T captureHops(Supplier<T> action) {
    if (capturedHops.get() != null) {
      return action.get();
    }
    capturedHops.set(new ArrayList<>());
    try {
      return action.get();
    } finally {
      capturedHops.remove();
    }
  }

  private JsonNode sanitize(JsonNode value, UUID runId) {
    Session session = sessions.get(runId);
    Credentials credentials = credentials(runId);
    if (value == null || value.isNull()) {
      return objectMapper.nullNode();
    }
    if (value.isObject()) {
      ObjectNode sanitized = objectMapper.createObjectNode();
      Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        if (!sensitiveKey(field.getKey())) {
          sanitized.set(field.getKey(), sanitize(field.getValue(), runId));
        }
      }
      return sanitized;
    }
    if (value.isArray()) {
      ArrayNode sanitized = objectMapper.createArrayNode();
      for (JsonNode item : value) {
        sanitized.add(sanitize(item, runId));
      }
      return sanitized;
    }
    if (value.isTextual()) {
      String text = value.textValue();
      if (containsSecret(text, internalSecret)
          || containsSecret(text, credentials.password())
          || (session != null && containsSecret(text, session.accessToken()))
          || SENSITIVE_TEXT_PATTERN.matcher(text).find()) {
        return objectMapper.getNodeFactory().textNode("[REDACTED]");
      }
    }
    return value.deepCopy();
  }

  private static boolean sensitiveKey(String key) {
    String canonical = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    return SENSITIVE_KEY_PARTS.stream()
        .map(part -> part.replace("_", ""))
        .anyMatch(canonical::contains);
  }

  private static boolean containsSecret(String text, String secret) {
    return text != null && secret != null && !secret.isBlank() && text.contains(secret);
  }

  private Credentials credentials(UUID runId) {
    String digest = hmac(runId + ":credentials");
    return new Credentials(
        "validation-" + digest.substring(0, 24) + "@example.invalid",
        "V!" + digest.substring(0, 46));
  }

  private String hmac(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(
          internalSecret.getBytes(StandardCharsets.UTF_8),
          "HmacSHA256"));
      return java.util.HexFormat.of().formatHex(
          mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
    }
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Validation loopback body is not serializable", exception);
    }
  }

  private Map<String, Object> objectMap(JsonNode value) {
    if (value != null && value.isObject()) {
      return objectMapper.convertValue(value, new TypeReference<>() { });
    }
    return Map.of("data", objectValue(value));
  }

  private Object directStateValue(
      StateQuery query,
      JsonNode envelope,
      UUID expectedAccountId
  ) {
    JsonNode data = successfulData(envelope);
    if (query.shape() == DirectStateShape.ACCOUNT) {
      if (!data.isObject()
          || !expectedAccountId.equals(requiredCanonicalUuid(data, "id"))) {
        throw invalidStateResponse();
      }
      return immutableJsonValue(data, 0);
    }
    if (!data.isArray() || data.size() > 10_000) {
      throw invalidStateResponse();
    }
    for (JsonNode item : data) {
      if (item == null
          || !item.isObject()
          || requiredCanonicalUuid(item, "id") == null
          || !expectedAccountId.equals(requiredCanonicalUuid(item, "accountId"))) {
        throw invalidStateResponse();
      }
    }
    return immutableJsonValue(data, 0);
  }

  private static JsonNode successfulData(JsonNode envelope) {
    if (envelope == null
        || !envelope.isObject()
        || !envelope.path("success").isBoolean()
        || !envelope.path("success").booleanValue()
        || !envelope.path("code").isTextual()
        || !"OK".equals(envelope.path("code").textValue())) {
      throw invalidStateResponse();
    }
    JsonNode data = envelope.get("data");
    if (data == null || data.isNull() || data.isMissingNode()) {
      throw invalidStateResponse();
    }
    return data;
  }

  private Object immutableJsonValue(JsonNode value, int depth) {
    if (value == null || depth > 64) {
      throw invalidStateResponse();
    }
    if (value.isNull()) {
      return null;
    }
    if (value.isObject()) {
      LinkedHashMap<String, Object> frozen = new LinkedHashMap<>();
      value.fields().forEachRemaining(entry ->
          frozen.put(entry.getKey(), immutableJsonValue(entry.getValue(), depth + 1)));
      return Collections.unmodifiableMap(frozen);
    }
    if (value.isArray()) {
      ArrayList<Object> frozen = new ArrayList<>(value.size());
      value.forEach(item -> frozen.add(immutableJsonValue(item, depth + 1)));
      return Collections.unmodifiableList(frozen);
    }
    if (value.isTextual()) {
      return value.textValue();
    }
    if (value.isBoolean()) {
      return value.booleanValue();
    }
    if (value.isIntegralNumber()) {
      return value.bigIntegerValue();
    }
    if (value.isFloatingPointNumber()) {
      return value.decimalValue();
    }
    throw invalidStateResponse();
  }

  private static UUID requiredCanonicalUuid(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual()) {
      throw invalidStateResponse();
    }
    try {
      UUID parsed = UUID.fromString(value.textValue());
      if (!parsed.toString().equals(value.textValue())) {
        throw invalidStateResponse();
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw invalidStateResponse();
    }
  }

  private static BusinessException invalidStateResponse() {
    return failure(
        "VALIDATION_STATE_RESPONSE_INVALID",
        "Validation state response was incomplete or inconsistent");
  }

  private Object objectValue(JsonNode value) {
    return value == null || value.isMissingNode() || value.isNull()
        ? nullValue()
        : objectMapper.convertValue(value, Object.class);
  }

  private static Object nullValue() {
    return Map.of();
  }

  private static String pagePath(String root, UUID accountId, int page) {
    return root
        + "?accountId=" + accountId
        + "&page=" + page
        + "&size=" + ValidationStatePageCollector.PAGE_SIZE;
  }

  private static String orderByClientOrderIdPath(UUID accountId, String clientOrderId) {
    return TRADING_ORDERS_PATH
        + "/by-client-order-id?accountId=" + accountId
        + "&clientOrderId=" + query(clientOrderId);
  }

  private static String query(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String requiredText(Map<String, ?> values, String key) {
    Object value = values.get(key);
    if (value == null || value.toString().isBlank()) {
      throw new BusinessException("VALIDATION_ACTION_INVALID", "Validation action field is required");
    }
    return value.toString();
  }

  private static String safeSymbol(String raw) {
    String symbol = SymbolNormalizer.normalize(raw);
    if (!symbol.matches("[A-Z0-9-]{1,40}")) {
      throw new BusinessException("VALIDATION_ACTION_INVALID", "Validation symbol is invalid");
    }
    return symbol;
  }

  private static String correlation(Command command) {
    return command.runId() + ":" + command.operation() + ":" + command.tickSequence();
  }

  private static boolean successful(int status) {
    return status >= 200 && status < 300;
  }

  private static BusinessException failure(String code, String message) {
    return new BusinessException(code, message);
  }

  private record RawResponse(int status, JsonNode body) {
  }

  private record SafeTraceRoute(
      String path,
      String requestUrl,
      Map<String, Object> queryParameters
  ) {
  }

  private record Credentials(String email, String password) {
  }

  private record Session(
      long generation,
      UUID userId,
      UUID accountId,
      String email,
      String accessToken
  ) {
  }

  private enum DirectStateShape {
    ACCOUNT,
    ACCOUNT_SCOPED_LIST
  }

  private record StateQuery(String name, String path, DirectStateShape shape) {
  }

  private record PagedStateQuery(String name, String rootPath) {
  }

  private record SymbolSetting(
      Integer leverage,
      String marginMode,
      String quantityUnit,
      long version
  ) {
  }

  private record Authentication(boolean internal, String bearerToken) {

    private static Authentication none() {
      return new Authentication(false, null);
    }

    private static Authentication internalAuth() {
      return new Authentication(true, null);
    }

    private static Authentication bearer(String token) {
      return new Authentication(false, token);
    }
  }
}
