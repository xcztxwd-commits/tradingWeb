package com.fxplatform.execution;

import java.time.Instant;

/** Selects the current time authority for one executable market provenance. */
public interface ExecutableMarketTimeAuthority {

  Instant currentTime(ExecutableMarketSnapshot snapshot);

  Instant currentTime(FullFillResult result);
}
