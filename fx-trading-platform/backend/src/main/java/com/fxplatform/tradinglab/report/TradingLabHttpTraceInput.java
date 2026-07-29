package com.fxplatform.tradinglab.report;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Raw HTTP exchange input. Collection references are intentionally retained until the sanitizer
 * performs its single bounded snapshot; this constructor must never copy attacker-sized data.
 */
public record TradingLabHttpTraceInput(
    URI uri,
    Map<String, List<String>> requestHeaders,
    Map<String, List<String>> responseHeaders,
    String requestContentType,
    Object requestBody,
    String responseContentType,
    Object responseBody,
    Throwable exception,
    UUID actorId,
    List<String> scopes,
    Instant expiresAt
) {

  public TradingLabHttpTraceInput {
    requestHeaders = requestHeaders == null ? Map.of() : requestHeaders;
    responseHeaders = responseHeaders == null ? Map.of() : responseHeaders;
    scopes = scopes == null ? List.of() : scopes;
  }
}
