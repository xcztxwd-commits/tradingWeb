package com.fxplatform.market.funding;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FundingRateProvider {

  FundingSource source();

  Optional<FundingRateSnapshot> current(Query query);

  List<FundingRateSnapshot> history(
      Query query,
      Instant afterExclusive,
      Instant atOrBefore
  );

  record Query(
      String symbol,
      String providerSymbol,
      BigDecimal fixedRate,
      int fixedIntervalMinutes
  ) {
  }
}
