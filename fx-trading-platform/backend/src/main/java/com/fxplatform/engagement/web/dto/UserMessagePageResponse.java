package com.fxplatform.engagement.web.dto;

import java.util.List;

public record UserMessagePageResponse(
    List<UserMessageResponse> items,
    int page,
    int size,
    long total,
    int totalPages
) {

  public UserMessagePageResponse {
    items = List.copyOf(items);
  }
}
