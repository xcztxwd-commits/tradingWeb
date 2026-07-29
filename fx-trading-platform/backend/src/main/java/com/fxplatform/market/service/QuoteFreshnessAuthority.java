package com.fxplatform.market.service;

import com.fxplatform.market.dto.QuoteResponse;

/** Owns the complete freshness decision for a quote in the active runtime. */
public interface QuoteFreshnessAuthority {

  boolean isStale(QuoteResponse quote, long quoteStaleMs);
}
