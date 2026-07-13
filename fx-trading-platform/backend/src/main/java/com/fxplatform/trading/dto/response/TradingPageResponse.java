package com.fxplatform.trading.dto.response;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;

/** Zero-based, bounded page used by authenticated trading history queries. */
public record TradingPageResponse<T>(
    List<T> items,
    int page,
    int size,
    long total,
    int totalPages
) {

  private static final int MAX_PAGE_SIZE = 100;

  public static <T> Page<T> request(int page, int size) {
    return Page.of((long) Math.max(0, page) + 1, Math.max(1, Math.min(size, MAX_PAGE_SIZE)));
  }

  public static <T> TradingPageResponse<T> fromBoundedItems(
      List<T> sortedItems,
      int page,
      int size
  ) {
    Page<T> request = request(page, size);
    long offset = (request.getCurrent() - 1) * request.getSize();
    int from = offset >= sortedItems.size() ? sortedItems.size() : (int) offset;
    int to = Math.min(sortedItems.size(), from + (int) request.getSize());
    return of(sortedItems.subList(from, to), page, size, sortedItems.size());
  }

  public static <T> TradingPageResponse<T> of(
      List<T> items,
      int page,
      int size,
      long total
  ) {
    Page<T> request = request(page, size);
    int normalizedPage = (int) request.getCurrent() - 1;
    int normalizedSize = (int) request.getSize();
    long pages = total == 0 ? 0 : 1 + (total - 1) / normalizedSize;
    return new TradingPageResponse<>(
        List.copyOf(items),
        normalizedPage,
        normalizedSize,
        total,
        (int) Math.min(Integer.MAX_VALUE, pages));
  }

  public static <T> TradingPageResponse<T> from(IPage<T> result) {
    return new TradingPageResponse<>(
        List.copyOf(result.getRecords()),
        Math.max(0, (int) result.getCurrent() - 1),
        (int) result.getSize(),
        result.getTotal(),
        (int) result.getPages());
  }
}
