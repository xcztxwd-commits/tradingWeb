package com.fxplatform.engagement.admin.support;

import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.engagement.admin.support.repository.AdminUserSearchRepository;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminUserSearchService {

  private static final int MAX_PAGE_SIZE = 100;
  private final AdminUserSearchRepository repository;

  public AdminUserSearchService(AdminUserSearchRepository repository) {
    this.repository = Objects.requireNonNull(repository);
  }

  public AdminPageResponse<AdminUserSearchResponse> search(String query, int page, int size) {
    if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
      throw invalid("User search pagination is invalid");
    }
    SearchTerm term = searchTerm(query);
    long offset = (long) page * size;
    List<AdminUserSearchResponse> items = repository.findUsers(
            term.exactId(), term.likePattern(), size, offset).stream()
        .map(row -> new AdminUserSearchResponse(
            row.id(), row.email(), row.phone(), row.status()))
        .toList();
    long total = repository.countUsers(term.exactId(), term.likePattern());
    int totalPages = total == 0 ? 0 : Math.toIntExact((total + size - 1) / size);
    return new AdminPageResponse<>(items, page, size, total, totalPages);
  }

  private static SearchTerm searchTerm(String query) {
    if (query == null || query.isBlank()) {
      return new SearchTerm(null, null);
    }
    String normalized = query.trim();
    if (normalized.length() > 200) {
      throw invalid("User search query is too long");
    }
    UUID exactId = null;
    try {
      exactId = UUID.fromString(normalized);
    } catch (IllegalArgumentException ignored) {
      // Email and phone searches are intentionally non-UUID.
    }
    String escaped = normalized.toLowerCase(Locale.ROOT)
        .replace("!", "!!")
        .replace("%", "!%")
        .replace("_", "!_");
    return new SearchTerm(exactId, "%" + escaped + "%");
  }

  private static BusinessException invalid(String message) {
    return new BusinessException("ADMIN_USER_SEARCH_INVALID", message);
  }

  public record AdminUserSearchResponse(
      UUID id,
      String email,
      String phone,
      UserStatus status
  ) {
  }

  private record SearchTerm(UUID exactId, String likePattern) {
  }
}
