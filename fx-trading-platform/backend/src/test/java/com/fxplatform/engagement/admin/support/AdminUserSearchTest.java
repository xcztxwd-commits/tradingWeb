package com.fxplatform.engagement.admin.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.service.AdminPermissionCatalog;
import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.engagement.admin.support.repository.AdminUserSearchRepository;
import com.fxplatform.engagement.admin.support.repository.AdminUserSearchRepository.AdminUserSearchRow;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@ExtendWith(MockitoExtension.class)
class AdminUserSearchTest {

  private static final UUID USER_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000001");

  @Mock private AdminUserSearchRepository repository;

  @Test
  void controllerRequiresAdminAndEitherCampaignOrMessageEdit() throws Exception {
    RequestMapping root = AdminUserSearchController.class.getAnnotation(RequestMapping.class);
    GetMapping route = AdminUserSearchController.class
        .getMethod("search", String.class, int.class, int.class)
        .getAnnotation(GetMapping.class);
    PreAuthorize security = AdminUserSearchController.class
        .getMethod("search", String.class, int.class, int.class)
        .getAnnotation(PreAuthorize.class);

    assertThat(root.value()).containsExactly("/api/admin/users");
    assertThat(route.value()).containsExactly("/search");
    assertThat(security.value()).isEqualTo(
        "hasRole('ADMIN') and (hasAuthority('"
            + AdminPermissionCatalog.CONTENT_CAMPAIGN_EDIT
            + "') or hasAuthority('"
            + AdminPermissionCatalog.CONTENT_MESSAGE_EDIT
            + "'))");
  }

  @Test
  void searchEscapesLikeMetacharactersCapsTheBoundaryAndReturnsOnlySafeFields() {
    AdminUserSearchService service = new AdminUserSearchService(repository);
    when(repository.findUsers(null, "%a!!!%!_@example.com%", 100, 200L))
        .thenReturn(List.of(new AdminUserSearchRow(
            USER_ID, "a!%_@example.com", "+8613800000000", UserStatus.FROZEN)));
    when(repository.countUsers(null, "%a!!!%!_@example.com%"))
        .thenReturn(1L);

    var result = service.search(" A!%_@Example.COM ", 2, 100);

    assertThat(result.page()).isEqualTo(2);
    assertThat(result.size()).isEqualTo(100);
    assertThat(result.total()).isOne();
    assertThat(result.items()).singleElement().satisfies(user -> {
      assertThat(user.id()).isEqualTo(USER_ID);
      assertThat(user.email()).isEqualTo("a!%_@example.com");
      assertThat(user.phone()).isEqualTo("+8613800000000");
      assertThat(user.status()).isEqualTo(UserStatus.FROZEN);
    });
    assertThat(Arrays.stream(result.items().getFirst().getClass().getRecordComponents())
        .map(RecordComponent::getName))
        .containsExactly("id", "email", "phone", "status");
  }

  @Test
  void uuidSearchUsesAnExactParameterizedIdAndInvalidPaginationNeverQueries() {
    AdminUserSearchService service = new AdminUserSearchService(repository);
    String uuid = USER_ID.toString();
    when(repository.findUsers(USER_ID, "%" + uuid + "%", 20, 0L)).thenReturn(List.of());
    when(repository.countUsers(USER_ID, "%" + uuid + "%")).thenReturn(0L);

    service.search(uuid, 0, 20);
    clearInvocations(repository);

    assertThatThrownBy(() -> service.search("user", 0, 101))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("pagination");
    assertThatThrownBy(() -> service.search("user", -1, 20))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("pagination");
    verifyNoInteractions(repository);
  }

  @Test
  void repositoryIsParameterizedRoleAndStatusBoundedAndStablySorted() throws Exception {
    String sql = String.join(" ", AdminUserSearchRepository.class
            .getMethod(
                "findUsers", UUID.class, String.class, int.class, long.class)
            .getAnnotation(Select.class)
            .value())
        .replaceAll("\\s+", " ")
        .toUpperCase();

    assertThat(sql).contains(
        "SELECT USER_ROW.ID, USER_ROW.EMAIL, USER_ROW.PHONE, USER_ROW.STATUS",
        "USER_ROW.ROLE = 'USER'",
        "USER_ROW.STATUS IN ('ACTIVE', 'FROZEN', 'DISABLED')",
        "USER_ROW.ID = #{EXACTID}",
        "USER_ROW.EMAIL ILIKE #{LIKEPATTERN} ESCAPE '!'",
        "USER_ROW.PHONE ILIKE #{LIKEPATTERN} ESCAPE '!'",
        "ORDER BY LOWER(USER_ROW.EMAIL) ASC, USER_ROW.ID ASC",
        "LIMIT #{LIMIT} OFFSET #{OFFSET}");
    assertThat(sql).doesNotContain("${", "PASSWORD_HASH", "KYC_STATUS", "RISK_LEVEL");
  }
}
