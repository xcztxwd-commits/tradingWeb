package com.fxplatform.execution;

import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.model.SpotMarketBundle;
import java.math.BigDecimal;
import java.time.Instant;

/** Immutable executable projection of one whole market-data provider bundle. */
public record ExecutableMarketSnapshot(
    String platformSymbol,
    ProductType productType,
    String providerCode,
    String providerSymbol,
    MarketSourceMode sourceMode,
    BigDecimal bid,
    BigDecimal ask,
    BigDecimal last,
    BigDecimal mark,
    BigDecimal index,
    Instant asOf,
    Instant expiresAt
) {

  public static ExecutableMarketSnapshot from(SpotMarketBundle bundle) {
    return new ExecutableMarketSnapshot(
        bundle.platformSymbol(),
        ProductType.CRYPTO_SPOT,
        bundle.providerCode(),
        bundle.providerSymbol(),
        bundle.sourceMode(),
        bundle.bid(),
        bundle.ask(),
        bundle.last(),
        null,
        null,
        bundle.asOf(),
        bundle.expiresAt());
  }

  public static ExecutableMarketSnapshot from(PerpetualMarketBundle bundle) {
    return new ExecutableMarketSnapshot(
        bundle.platformSymbol(),
        ProductType.LINEAR_PERP,
        bundle.providerCode(),
        bundle.providerSymbol(),
        bundle.sourceMode(),
        bundle.bid(),
        bundle.ask(),
        bundle.last(),
        bundle.mark(),
        bundle.index(),
        bundle.asOf(),
        bundle.expiresAt());
  }
}
