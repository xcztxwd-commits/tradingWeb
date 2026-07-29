package com.fxplatform.tradinglab.report;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.application.TradingLabCredentialSanitizer;
import java.io.IOException;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class TradingLabHttpTraceSanitizer {

  private static final String UNSAFE_TRACE = "TRADING_LAB_REPORT_UNSAFE_TRACE";
  private static final String UNSAFE_BODY = "TRADING_LAB_REPORT_UNSAFE_BODY";
  private static final int DEFAULT_MAX_TRACE_BYTES = 1_048_576;
  private static final int MAX_URI_CHARS = 16_384;
  private static final int MAX_HEADER_COUNT = 256;
  private static final int MAX_HEADER_VALUES = 64;
  private static final int MAX_HEADER_CHARS = 16_384;
  private static final int MAX_NUMBER_CHARS = 1_000;
  private static final int MAX_THROWABLE_DEPTH = 8;
  private static final int MAX_THROWABLE_TEXT_CODE_POINTS = 2_048;
  private static final Set<String> SESSION_COOKIE_NAMES = Set.of(
      "session", "sessionid", "jsessionid", "sid");

  private final TradingLabCredentialSanitizer credentialSanitizer;
  private final TradingLabBoundedCredentialSanitizer boundedCredentialSanitizer;
  private final JsonFactory jsonFactory;
  private final TradingLabFixedValidationSecretProvider fixedValidationSecrets;
  private final int maxTraceBytes;

  TradingLabHttpTraceSanitizer(
      TradingLabCredentialSanitizer credentialSanitizer,
      TradingLabFixedValidationSecretProvider fixedValidationSecrets,
      int maxTraceBytes
  ) {
    this.credentialSanitizer = Objects.requireNonNull(credentialSanitizer);
    this.boundedCredentialSanitizer = new TradingLabBoundedCredentialSanitizer(
        credentialSanitizer, maxTraceBytes);
    this.fixedValidationSecrets = Objects.requireNonNull(fixedValidationSecrets);
    if (maxTraceBytes < 1) {
      throw new IllegalArgumentException("Trading Lab HTTP trace limit must be positive");
    }
    this.maxTraceBytes = maxTraceBytes;
    this.jsonFactory = strictJsonFactory(maxTraceBytes);
  }

  TradingLabHttpTraceSanitizer(
      TradingLabCredentialSanitizer credentialSanitizer,
      TradingLabFixedValidationSecretProvider fixedValidationSecrets
  ) {
    this(credentialSanitizer, fixedValidationSecrets, DEFAULT_MAX_TRACE_BYTES);
  }

  TradingLabHttpTraceSanitizer(
      TradingLabCredentialSanitizer credentialSanitizer,
      List<String> fixedValidationSecrets
  ) {
    this(
        credentialSanitizer,
        new TradingLabFixedValidationSecretProvider(fixedValidationSecrets),
        DEFAULT_MAX_TRACE_BYTES);
  }

  TradingLabHttpTraceSanitizer(
      TradingLabCredentialSanitizer credentialSanitizer,
      List<String> fixedValidationSecrets,
      int maxTraceBytes
  ) {
    this(
        credentialSanitizer,
        new TradingLabFixedValidationSecretProvider(fixedValidationSecrets),
        maxTraceBytes);
  }

  public SafeTradingLabHttpTrace sanitize(TradingLabHttpTraceInput input) {
    if (input == null) {
      throw unsafeTrace();
    }
    try {
      return sanitizeChecked(input, null);
    } catch (BusinessException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw unsafeTrace();
    }
  }

  /**
   * Re-seals a canonical durable envelope while retaining the raw exchange's dynamic-secret
   * registry. The safe value is deterministic across live projection and journal replay.
   */
  public SafeTradingLabHttpTrace sanitizeWithInheritedSecrets(
      TradingLabHttpTraceInput input,
      SafeTradingLabHttpTrace source
  ) {
    try {
      return sanitizeChecked(input, Objects.requireNonNull(source, "source"));
    } catch (BusinessException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw unsafeTrace();
    }
  }

  private SafeTradingLabHttpTrace sanitizeChecked(
      TradingLabHttpTraceInput input,
      SafeTradingLabHttpTrace inherited
  ) {
    TradingLabTraceBudget budget = new TradingLabTraceBudget(maxTraceBytes);
    TradingLabTraceSecretRegistry secrets = new TradingLabTraceSecretRegistry();
    fixedValidationSecrets.secrets().forEach(secrets::register);
    if (inherited != null) {
      inherited.copySecretsTo(secrets);
    }

    List<String> scopes = snapshotScopes(input.scopes(), budget);
    SanitizedHeaders requestHeaders = sanitizeHeaders(
        input.requestHeaders(), true, secrets, budget);
    SanitizedHeaders responseHeaders = sanitizeHeaders(
        input.responseHeaders(), false, secrets, budget);
    AuthenticationContext authentication = extractAuthentication(
        requestHeaders.authentication(), input.actorId(), scopes, input.expiresAt(), secrets);
    SanitizedUri uri = sanitizeUri(input.uri(), secrets, budget);
    String requestContentType = snapshotContentType(input.requestContentType(), budget);
    String responseContentType = snapshotContentType(input.responseContentType(), budget);
    Object requestBody = sanitizeBody(
        requestContentType, input.requestBody(), secrets, budget);
    Object responseBody = sanitizeBody(
        responseContentType, input.responseBody(), secrets, budget);
    Map<String, Object> exception = sanitizeThrowable(input.exception(), secrets, budget);

    Map<String, Object> safeSnapshot = new LinkedHashMap<>();
    safeSnapshot.put("url", uri.url());
    safeSnapshot.put("queryParameters", uri.queryParameters());
    safeSnapshot.put("requestHeaders", requestHeaders.safe());
    safeSnapshot.put("requestContentType", requestContentType);
    safeSnapshot.put("requestBody", requestBody);
    safeSnapshot.put("responseHeaders", responseHeaders.safe());
    safeSnapshot.put("responseContentType", responseContentType);
    safeSnapshot.put("responseBody", responseBody);
    safeSnapshot.put("exception", exception);

    Map<String, Object> redacted = asMap(boundedCredentialSanitizer.redactKnownSecrets(
        safeSnapshot, secrets.snapshot()));
    TradingLabCredentialMetadata metadata = createMetadata(
        input.actorId(), scopes, input.expiresAt(), authentication);
    SafeTradingLabHttpTrace safe = new SafeTradingLabHttpTrace(
        asString(redacted.get("url")),
        asMap(redacted.get("queryParameters")),
        asMap(redacted.get("requestHeaders")),
        asString(redacted.get("requestContentType")),
        redacted.get("requestBody"),
        asMap(redacted.get("responseHeaders")),
        asString(redacted.get("responseContentType")),
        redacted.get("responseBody"),
        nullableMap(redacted.get("exception")),
        metadata,
        secrets);

    new TradingLabCanonicalCanaryScanner(secrets.snapshot(), maxTraceBytes)
        .scan(safe.toSafeMap());
    return safe;
  }

  private List<String> snapshotScopes(
      List<String> rawScopes,
      TradingLabTraceBudget budget
  ) {
    List<String> scopes = new ArrayList<>();
    for (String scope : rawScopes) {
      budget.consumeNode(TradingLabTraceBudget.Domain.TRACE);
      if (scope == null) {
        throw unsafeTrace();
      }
      budget.consumeText(scope, TradingLabTraceBudget.Domain.TRACE);
      scopes.add(scope);
    }
    return List.copyOf(scopes);
  }

  private SanitizedHeaders sanitizeHeaders(
      Map<String, List<String>> rawHeaders,
      boolean collectAuthentication,
      TradingLabTraceSecretRegistry secrets,
      TradingLabTraceBudget budget
  ) {
    Map<String, Object> safe = new LinkedHashMap<>();
    HeaderAuthentication authentication = new HeaderAuthentication();
    int headerCount = 0;
    for (Map.Entry<String, List<String>> entry : rawHeaders.entrySet()) {
      if (++headerCount > MAX_HEADER_COUNT) {
        throw unsafeTrace();
      }
      budget.consumeNode(TradingLabTraceBudget.Domain.TRACE);
      String name = entry.getKey();
      List<String> values = entry.getValue();
      if (name == null || name.length() > MAX_HEADER_CHARS || values == null) {
        throw unsafeTrace();
      }
      budget.consumeText(name, TradingLabTraceBudget.Domain.TRACE);
      boolean sensitive = credentialSanitizer.isSensitiveKey(name);
      List<String> copiedValues = new ArrayList<>();
      int valueCount = 0;
      for (String value : values) {
        if (++valueCount > MAX_HEADER_VALUES
            || value == null
            || value.length() > MAX_HEADER_CHARS) {
          throw unsafeTrace();
        }
        budget.consumeNode(TradingLabTraceBudget.Domain.TRACE);
        budget.consumeText(value, TradingLabTraceBudget.Domain.TRACE);
        if (sensitive) {
          collectHeaderSecrets(name, value, secrets);
        } else {
          copiedValues.add(value);
        }
        if (collectAuthentication) {
          collectAuthentication(name, value, authentication);
        }
      }
      if (!sensitive) {
        if (safe.put(name, copiedValues) != null) {
          throw unsafeTrace();
        }
      }
    }
    return new SanitizedHeaders(safe, authentication);
  }

  private void collectAuthentication(
      String headerName,
      String value,
      HeaderAuthentication authentication
  ) {
    if (headerName.equalsIgnoreCase("Authorization")
        || headerName.equalsIgnoreCase("Proxy-Authorization")) {
      authentication.authorizationCount++;
      if (authentication.authorizationCount > 1) {
        throw unsafeTrace();
      }
      authentication.authorization = value;
      return;
    }
    if (!headerName.equalsIgnoreCase("Cookie")) {
      return;
    }
    forEachCookiePair(value, false, (name, credential) -> {
      if (SESSION_COOKIE_NAMES.contains(name.toLowerCase(Locale.ROOT))
          && !credential.semantic().isEmpty()) {
        authentication.sessionCount++;
        if (authentication.sessionCount > 1) {
          throw unsafeTrace();
        }
        authentication.session = credential;
      }
    });
  }

  private AuthenticationContext extractAuthentication(
      HeaderAuthentication raw,
      UUID actorId,
      List<String> scopes,
      Instant expiresAt,
      TradingLabTraceSecretRegistry secrets
  ) {
    if (raw.authorization != null) {
      String bearer = bearerValue(raw.authorization);
      secrets.register(raw.authorization);
      secrets.register(bearer);
      requireAuthenticationProvenance(actorId, expiresAt);
      return new AuthenticationContext("BEARER", bearer);
    }
    if (raw.session != null) {
      secrets.register(raw.session.raw());
      secrets.register(raw.session.semantic());
      requireAuthenticationProvenance(actorId, expiresAt);
      return new AuthenticationContext("SESSION", raw.session.semantic());
    }
    if (actorId != null || expiresAt != null || !scopes.isEmpty()) {
      throw unsafeTrace();
    }
    return null;
  }

  private String bearerValue(String headerValue) {
    if (headerValue == null || headerValue.length() > MAX_HEADER_CHARS) {
      throw unsafeTrace();
    }
    String trimmed = headerValue.trim();
    int separator = trimmed.indexOf(' ');
    if (separator <= 0
        || !"bearer".equalsIgnoreCase(trimmed.substring(0, separator))) {
      throw unsafeTrace();
    }
    String bearer = trimmed.substring(separator + 1).trim();
    if (bearer.isEmpty() || !isToken68(bearer)) {
      throw unsafeTrace();
    }
    return bearer;
  }

  private static boolean isToken68(String value) {
    boolean padding = false;
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current == '=') {
        padding = true;
      } else if (padding || !isToken68Core(current)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isToken68Core(char value) {
    return (value >= 'a' && value <= 'z')
        || (value >= 'A' && value <= 'Z')
        || (value >= '0' && value <= '9')
        || value == '-'
        || value == '.'
        || value == '_'
        || value == '~'
        || value == '+'
        || value == '/';
  }

  private void requireAuthenticationProvenance(UUID actorId, Instant expiresAt) {
    if (actorId == null || expiresAt == null) {
      throw unsafeTrace();
    }
  }

  private TradingLabCredentialMetadata createMetadata(
      UUID actorId,
      List<String> scopes,
      Instant expiresAt,
      AuthenticationContext authentication
  ) {
    if (authentication == null) {
      return null;
    }
    if ("BEARER".equals(authentication.type())) {
      return TradingLabCredentialMetadata.bearer(
          actorId, scopes, expiresAt, authentication.credential());
    }
    return TradingLabCredentialMetadata.session(
        actorId, scopes, expiresAt, authentication.credential());
  }

  private void collectHeaderSecrets(
      String headerName,
      String value,
      TradingLabTraceSecretRegistry secrets
  ) {
    secrets.register(value);
    if (headerName.equalsIgnoreCase("Authorization")
        || headerName.equalsIgnoreCase("Proxy-Authorization")) {
      try {
        secrets.register(bearerValue(value));
      } catch (BusinessException ignored) {
        // Authentication extraction below rejects unsupported schemes.
      }
      return;
    }
    if (headerName.equalsIgnoreCase("Cookie")) {
      forEachCookiePair(value, false, (ignored, cookie) -> {
        secrets.register(cookie.raw());
        secrets.register(cookie.semantic());
      });
      return;
    }
    if (headerName.equalsIgnoreCase("Set-Cookie")) {
      forEachCookiePair(value, true, (ignored, cookie) -> {
        secrets.register(cookie.raw());
        secrets.register(cookie.semantic());
      });
    }
  }

  private void forEachCookiePair(
      String cookieHeader,
      boolean firstOnly,
      CookieConsumer consumer
  ) {
    if (cookieHeader == null || cookieHeader.length() > MAX_HEADER_CHARS) {
      throw unsafeTrace();
    }
    int start = 0;
    while (start <= cookieHeader.length()) {
      int end = cookieHeader.indexOf(';', start);
      if (end < 0) {
        end = cookieHeader.length();
      }
      String part = cookieHeader.substring(start, end);
      int separator = part.indexOf('=');
      if (separator > 0) {
        String name = part.substring(0, separator).trim();
        CookieCredential value = cookieValue(part.substring(separator + 1));
        if (!value.semantic().isEmpty()) {
          consumer.accept(name, value);
        }
      }
      if (firstOnly || end == cookieHeader.length()) {
        break;
      }
      start = end + 1;
    }
  }

  private CookieCredential cookieValue(String encoded) {
    String raw = encoded.trim();
    if (raw.isEmpty()) {
      return new CookieCredential(raw, raw);
    }
    boolean startsQuoted = raw.charAt(0) == '"';
    boolean endsQuoted = raw.charAt(raw.length() - 1) == '"';
    if (!startsQuoted && !endsQuoted) {
      return new CookieCredential(raw, raw);
    }
    if (!startsQuoted || !endsQuoted || raw.length() < 2) {
      throw unsafeTrace();
    }
    StringBuilder semantic = new StringBuilder(raw.length() - 2);
    for (int index = 1; index < raw.length() - 1; index++) {
      char current = raw.charAt(index);
      if (current == '\\') {
        if (++index >= raw.length() - 1) {
          throw unsafeTrace();
        }
        semantic.append(raw.charAt(index));
      } else if (current == '"') {
        throw unsafeTrace();
      } else {
        semantic.append(current);
      }
    }
    return new CookieCredential(raw, semantic.toString());
  }

  private SanitizedUri sanitizeUri(
      URI uri,
      TradingLabTraceSecretRegistry secrets,
      TradingLabTraceBudget budget
  ) {
    if (uri == null) {
      throw unsafeTrace();
    }
    String rawUri = uri.toString();
    if (rawUri.length() > MAX_URI_CHARS
        || uri.getScheme() == null
        || uri.getHost() == null
        || !(uri.getScheme().equalsIgnoreCase("http")
            || uri.getScheme().equalsIgnoreCase("https"))) {
      throw unsafeTrace();
    }
    budget.consumeNode(TradingLabTraceBudget.Domain.TRACE);
    budget.consumeText(rawUri, TradingLabTraceBudget.Domain.TRACE);

    String rawUserInfo = uri.getRawUserInfo();
    if (rawUserInfo != null && !rawUserInfo.isEmpty()) {
      secrets.register(rawUserInfo);
      String userInfo = decode(rawUserInfo, TradingLabTraceBudget.Domain.TRACE);
      secrets.register(userInfo);
      int passwordSeparator = userInfo.indexOf(':');
      if (passwordSeparator >= 0 && passwordSeparator + 1 < userInfo.length()) {
        secrets.register(userInfo.substring(passwordSeparator + 1));
      }
    }
    if (uri.getRawFragment() != null && !uri.getRawFragment().isEmpty()) {
      secrets.register(uri.getRawFragment());
      secrets.register(decode(
          uri.getRawFragment(), TradingLabTraceBudget.Domain.TRACE));
    }

    String host = uri.getHost().contains(":") ? "[" + uri.getHost() + "]" : uri.getHost();
    StringBuilder url = new StringBuilder()
        .append(uri.getScheme().toLowerCase(Locale.ROOT))
        .append("://")
        .append(host);
    if (uri.getPort() >= 0) {
      url.append(':').append(uri.getPort());
    }
    if (uri.getPath() != null) {
      url.append(uri.getPath());
    }
    Map<String, Object> parameters = parseParameters(
        uri.getRawQuery(), false, secrets, budget, TradingLabTraceBudget.Domain.TRACE);
    return new SanitizedUri(url.toString(), parameters);
  }

  private String snapshotContentType(String contentType, TradingLabTraceBudget budget) {
    if (contentType == null) {
      return null;
    }
    if (contentType.length() > MAX_HEADER_CHARS) {
      throw unsafeTrace();
    }
    budget.consumeNode(TradingLabTraceBudget.Domain.TRACE);
    budget.consumeText(contentType, TradingLabTraceBudget.Domain.TRACE);
    return contentType;
  }

  private Object sanitizeBody(
      String contentType,
      Object body,
      TradingLabTraceSecretRegistry secrets,
      TradingLabTraceBudget budget
  ) {
    if (body == null) {
      return null;
    }
    try {
      if (body instanceof byte[]) {
        throw unsafeBody();
      }
      if (body instanceof String text) {
        budget.consumeText(text, TradingLabTraceBudget.Domain.BODY);
        if (isJson(contentType)) {
          return parseJson(text, secrets, budget);
        }
        if (isForm(contentType)) {
          return parseParameters(
              text, false, secrets, budget, TradingLabTraceBudget.Domain.BODY);
        }
        throw unsafeBody();
      }
      return snapshotStructured(
          body,
          false,
          secrets,
          budget,
          new IdentityHashMap<>(),
          0);
    } catch (BusinessException exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw unsafeBody();
    }
  }

  private Object parseJson(
      String body,
      TradingLabTraceSecretRegistry secrets,
      TradingLabTraceBudget budget
  ) throws IOException {
    try (JsonParser parser = jsonFactory.createParser(body)) {
      JsonToken first = parser.nextToken();
      if (first == null) {
        throw unsafeBody();
      }
      Object value = readJsonValue(parser, first, false, secrets, budget, 0);
      if (parser.nextToken() != null) {
        throw unsafeBody();
      }
      return value;
    }
  }

  private Object readJsonValue(
      JsonParser parser,
      JsonToken token,
      boolean collectSecret,
      TradingLabTraceSecretRegistry secrets,
      TradingLabTraceBudget budget,
      int depth
  ) throws IOException {
    budget.checkDepth(depth, TradingLabTraceBudget.Domain.BODY);
    budget.consumeNode(TradingLabTraceBudget.Domain.BODY);
    if (token == JsonToken.VALUE_NULL) {
      return null;
    }
    if (token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE) {
      return collectSecret ? null : token == JsonToken.VALUE_TRUE;
    }
    if (token == JsonToken.VALUE_STRING) {
      String text = parser.getText();
      if (collectSecret) {
        secrets.register(text);
        return null;
      }
      return text;
    }
    if (token.isNumeric()) {
      Object number = parser.getNumberValue();
      if (collectSecret) {
        return null;
      }
      long canonicalBytes = TradingLabJsonNumberBounds.requireCanonicalLength(
          number, maxTraceBytes);
      budget.consumeBytes(canonicalBytes, TradingLabTraceBudget.Domain.BODY);
      return number;
    }
    if (token == JsonToken.START_ARRAY) {
      List<Object> values = collectSecret ? null : new ArrayList<>();
      JsonToken child;
      while ((child = parser.nextToken()) != JsonToken.END_ARRAY) {
        if (child == null) {
          throw unsafeBody();
        }
        Object value = readJsonValue(
            parser, child, collectSecret, secrets, budget, depth + 1);
        if (!collectSecret) {
          values.add(value);
        }
      }
      return values;
    }
    if (token == JsonToken.START_OBJECT) {
      Map<String, Object> values = collectSecret ? null : new LinkedHashMap<>();
      JsonToken field;
      while ((field = parser.nextToken()) != JsonToken.END_OBJECT) {
        if (field != JsonToken.FIELD_NAME) {
          throw unsafeBody();
        }
        String key = parser.currentName();
        boolean sensitive = credentialSanitizer.isSensitiveKey(key);
        JsonToken child = parser.nextToken();
        if (child == null) {
          throw unsafeBody();
        }
        Object value = readJsonValue(
            parser, child, collectSecret || sensitive, secrets, budget, depth + 1);
        if (!collectSecret && !sensitive && values.put(key, value) != null) {
          throw unsafeBody();
        }
      }
      return values;
    }
    throw unsafeBody();
  }

  private Object snapshotStructured(
      Object value,
      boolean collectSecret,
      TradingLabTraceSecretRegistry secrets,
      TradingLabTraceBudget budget,
      IdentityHashMap<Object, Boolean> ancestors,
      int depth
  ) {
    budget.checkDepth(depth, TradingLabTraceBudget.Domain.BODY);
    budget.consumeNode(TradingLabTraceBudget.Domain.BODY);
    if (value == null || value instanceof Boolean) {
      return collectSecret ? null : value;
    }
    if (value instanceof String text) {
      budget.consumeText(text, TradingLabTraceBudget.Domain.BODY);
      if (collectSecret) {
        secrets.register(text);
        return null;
      }
      return text;
    }
    if (isJsonNumber(value)) {
      if (collectSecret) {
        return null;
      }
      long canonicalBytes = TradingLabJsonNumberBounds.requireCanonicalLength(
          value, maxTraceBytes);
      budget.consumeBytes(canonicalBytes, TradingLabTraceBudget.Domain.BODY);
      return value;
    }
    if (value instanceof byte[]) {
      throw unsafeBody();
    }
    if (value instanceof Map<?, ?> map) {
      enter(map, ancestors);
      try {
        Map<String, Object> copy = collectSecret ? null : new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          Object rawKey = entry.getKey();
          if (!(rawKey instanceof String key)) {
            throw unsafeBody();
          }
          budget.consumeText(key, TradingLabTraceBudget.Domain.BODY);
          boolean sensitive = credentialSanitizer.isSensitiveKey(key);
          Object child = entry.getValue();
          Object snapshot = snapshotStructured(
              child,
              collectSecret || sensitive,
              secrets,
              budget,
              ancestors,
              depth + 1);
          if (!collectSecret && !sensitive && copy.put(key, snapshot) != null) {
            throw unsafeBody();
          }
        }
        return copy;
      } finally {
        ancestors.remove(map);
      }
    }
    if (value instanceof List<?> list) {
      enter(list, ancestors);
      try {
        List<Object> copy = collectSecret ? null : new ArrayList<>();
        for (Object child : list) {
          Object snapshot = snapshotStructured(
              child, collectSecret, secrets, budget, ancestors, depth + 1);
          if (!collectSecret) {
            copy.add(snapshot);
          }
        }
        return copy;
      } finally {
        ancestors.remove(list);
      }
    }
    if (value.getClass().isArray()) {
      int length = Array.getLength(value);
      budget.requireContainerHint(length, TradingLabTraceBudget.Domain.BODY);
      enter(value, ancestors);
      try {
        List<Object> copy = collectSecret ? null : new ArrayList<>();
        for (int index = 0; index < length; index++) {
          Object snapshot = snapshotStructured(
              Array.get(value, index),
              collectSecret,
              secrets,
              budget,
              ancestors,
              depth + 1);
          if (!collectSecret) {
            copy.add(snapshot);
          }
        }
        return copy;
      } finally {
        ancestors.remove(value);
      }
    }
    throw unsafeBody();
  }

  private Map<String, Object> parseParameters(
      String rawParameters,
      boolean consumeRawText,
      TradingLabTraceSecretRegistry secrets,
      TradingLabTraceBudget budget,
      TradingLabTraceBudget.Domain domain
  ) {
    if (rawParameters == null || rawParameters.isEmpty()) {
      return new LinkedHashMap<>();
    }
    if (consumeRawText) {
      budget.consumeText(rawParameters, domain);
    }
    Map<String, Object> parameters = new LinkedHashMap<>();
    int start = 0;
    while (start <= rawParameters.length()) {
      int end = rawParameters.indexOf('&', start);
      if (end < 0) {
        end = rawParameters.length();
      }
      if (end > start) {
        budget.consumeNode(domain);
        String pair = rawParameters.substring(start, end);
        int separator = pair.indexOf('=');
        String rawKey = separator < 0 ? pair : pair.substring(0, separator);
        String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
        String key = decode(rawKey, domain);
        String value = decode(rawValue, domain);
        if (credentialSanitizer.isSensitiveKey(key)) {
          secrets.register(rawValue);
          secrets.register(value);
        } else {
          @SuppressWarnings("unchecked")
          List<String> values = (List<String>) parameters.computeIfAbsent(
              key, ignored -> new ArrayList<String>());
          values.add(value);
        }
      }
      if (end == rawParameters.length()) {
        break;
      }
      start = end + 1;
    }
    return parameters;
  }

  private Map<String, Object> sanitizeThrowable(
      Throwable throwable,
      TradingLabTraceSecretRegistry secrets,
      TradingLabTraceBudget budget
  ) {
    if (throwable == null) {
      return null;
    }
    return sanitizeThrowable(
        throwable, secrets, budget, new IdentityHashMap<>(), 0);
  }

  private Map<String, Object> sanitizeThrowable(
      Throwable throwable,
      TradingLabTraceSecretRegistry secrets,
      TradingLabTraceBudget budget,
      IdentityHashMap<Throwable, Boolean> ancestors,
      int depth
  ) {
    if (ancestors.put(throwable, Boolean.TRUE) != null) {
      throw unsafeTrace();
    }
    try {
      budget.consumeNode(TradingLabTraceBudget.Domain.TRACE);
      Map<String, Object> safe = new LinkedHashMap<>();
      safe.put("type", sanitizedThrowableText(
          throwable.getClass().getName(), secrets, budget));
      safe.put("message", sanitizedThrowableText(throwable.getMessage(), secrets, budget));

      // Throwable#getStackTrace always clones the complete attacker-sized array. There is no
      // bounded public accessor for an arbitrary throwable, so trace capture deliberately omits
      // it; full controlled stacks belong in the separately sanitized ERRORS section.
      budget.consumeNode(TradingLabTraceBudget.Domain.TRACE);
      safe.put("stackFrames", List.of());
      safe.put("stackTruncated", true);

      Throwable cause = throwable.getCause();
      if (cause != null) {
        if (depth + 1 >= MAX_THROWABLE_DEPTH || ancestors.containsKey(cause)) {
          budget.consumeNode(TradingLabTraceBudget.Domain.TRACE);
          safe.put("causeTruncated", true);
        } else {
          safe.put("cause", sanitizeThrowable(
              cause, secrets, budget, ancestors, depth + 1));
        }
      }
      return safe;
    } finally {
      ancestors.remove(throwable);
    }
  }

  private String sanitizedThrowableText(
      String value,
      TradingLabTraceSecretRegistry secrets,
      TradingLabTraceBudget budget
  ) {
    budget.consumeNode(TradingLabTraceBudget.Domain.TRACE);
    if (value == null) {
      return null;
    }
    budget.consumeText(value, TradingLabTraceBudget.Domain.TRACE);
    Object redacted = boundedCredentialSanitizer.redactKnownSecrets(
        value, secrets.snapshot());
    if (!(redacted instanceof String text)) {
      throw unsafeTrace();
    }
    return boundedCodePoints(text, MAX_THROWABLE_TEXT_CODE_POINTS);
  }

  private boolean isJsonNumber(Object value) {
    Class<?> type = value.getClass();
    if (!(type == Byte.class
        || type == Short.class
        || type == Integer.class
        || type == Long.class
        || type == Float.class
        || type == Double.class
        || type == BigInteger.class
        || type == BigDecimal.class)) {
      return false;
    }
    if (value instanceof Double doubleValue) {
      return Double.isFinite(doubleValue);
    }
    if (value instanceof Float floatValue) {
      return Float.isFinite(floatValue);
    }
    return true;
  }

  private boolean isJson(String contentType) {
    String normalized = mediaType(contentType);
    return "application/json".equals(normalized) || normalized.endsWith("+json");
  }

  private boolean isForm(String contentType) {
    return "application/x-www-form-urlencoded".equals(mediaType(contentType));
  }

  private String mediaType(String contentType) {
    if (contentType == null) {
      return "";
    }
    int parameters = contentType.indexOf(';');
    return (parameters < 0 ? contentType : contentType.substring(0, parameters))
        .trim()
        .toLowerCase(Locale.ROOT);
  }

  private String decode(String value, TradingLabTraceBudget.Domain domain) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException exception) {
      if (domain == TradingLabTraceBudget.Domain.BODY) {
        throw unsafeBody();
      }
      throw unsafeTrace();
    }
  }

  private String boundedCodePoints(String value, int maxCodePoints) {
    int count = value.codePointCount(0, value.length());
    if (count <= maxCodePoints) {
      return value;
    }
    int retained = Math.max(0, maxCodePoints - 3);
    return value.substring(0, value.offsetByCodePoints(0, retained)) + "...";
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> asMap(Object value) {
    if (!(value instanceof Map<?, ?>)) {
      throw unsafeTrace();
    }
    return (Map<String, Object>) value;
  }

  private Map<String, Object> nullableMap(Object value) {
    return value == null ? null : asMap(value);
  }

  private String asString(Object value) {
    if (value == null || value instanceof String) {
      return (String) value;
    }
    throw unsafeTrace();
  }

  private void enter(Object value, IdentityHashMap<Object, Boolean> ancestors) {
    if (ancestors.put(value, Boolean.TRUE) != null) {
      throw unsafeBody();
    }
  }

  private static JsonFactory strictJsonFactory(int maxTraceBytes) {
    StreamReadConstraints constraints = StreamReadConstraints.builder()
        .maxNestingDepth(TradingLabTraceBudget.MAX_DEPTH)
        .maxDocumentLength(maxTraceBytes)
        .maxStringLength(maxTraceBytes)
        .maxNameLength(MAX_HEADER_CHARS)
        .maxNumberLength(MAX_NUMBER_CHARS)
        .build();
    return JsonFactory.builder()
        .streamReadConstraints(constraints)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build();
  }

  private BusinessException unsafeTrace() {
    return new BusinessException(
        UNSAFE_TRACE,
        "Trading Lab HTTP trace could not be safely recorded");
  }

  private BusinessException unsafeBody() {
    return new BusinessException(
        UNSAFE_BODY,
        "Trading Lab HTTP body could not be safely recorded");
  }

  private record AuthenticationContext(String type, String credential) {
  }

  private record SanitizedUri(String url, Map<String, Object> queryParameters) {
  }

  private record SanitizedHeaders(
      Map<String, Object> safe,
      HeaderAuthentication authentication
  ) {
  }

  private record CookieCredential(String raw, String semantic) {
  }

  private static final class HeaderAuthentication {
    private String authorization;
    private int authorizationCount;
    private CookieCredential session;
    private int sessionCount;
  }

  @FunctionalInterface
  private interface CookieConsumer {
    void accept(String name, CookieCredential value);
  }
}
