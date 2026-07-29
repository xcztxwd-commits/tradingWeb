package com.fxplatform.tradinglab.client;

import java.util.List;

public record ValidationEventPage(
    List<ValidationRunEvent> events,
    long highWatermark,
    boolean terminal,
    boolean hasMore
) {

  public ValidationEventPage {
    events = List.copyOf(events == null ? List.of() : events);
    if (highWatermark < 0L || (hasMore && events.isEmpty())) {
      throw new IllegalArgumentException("Validation event page metadata is inconsistent");
    }
  }
}
