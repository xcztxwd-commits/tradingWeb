package com.fxplatform.engagement.domain.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.enums.UserRole;
import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PopupAudiencePolicyTest {

  private static final Instant PUBLISHED_AT = Instant.parse("2026-07-19T04:00:00Z");
  private static final Instant END_AT = PUBLISHED_AT.plus(Duration.ofDays(7));
  private final PopupAudiencePolicy policy = new PopupAudiencePolicy();

  @Test
  void allDynamicallyIncludesBusinessUsersRegisteredAfterPublicationBeforeTheEnd() {
    PopupCampaign campaign = publishedCampaign(AudienceType.ALL, END_AT);
    UserEntity newUser = user(
        UserRole.USER,
        UserStatus.ACTIVE,
        PUBLISHED_AT.plus(Duration.ofDays(1)));

    assertThat(policy.isMember(campaign, newUser, false)).isTrue();
  }

  @Test
  void allIncludesTheCutoffInstantAndExcludesUsersRegisteredAfterIt() {
    PopupCampaign campaign = publishedCampaign(AudienceType.ALL, END_AT);

    assertThat(policy.isMember(campaign, user(UserRole.USER, UserStatus.ACTIVE, END_AT), false))
        .isTrue();
    assertThat(policy.isMember(
        campaign,
        user(UserRole.USER, UserStatus.ACTIVE, END_AT.plusNanos(1)),
        false))
        .isFalse();
  }

  @Test
  void allAlwaysExcludesAdminsAndIgnoresSelectedTargetRows() {
    PopupCampaign campaign = publishedCampaign(AudienceType.ALL, END_AT);
    UserEntity admin = user(UserRole.ADMIN, UserStatus.ACTIVE, PUBLISHED_AT.minusSeconds(1));
    UserEntity businessUser = user(UserRole.USER, UserStatus.ACTIVE, PUBLISHED_AT.minusSeconds(1));

    assertThat(policy.isMember(campaign, admin, true)).isFalse();
    assertThat(policy.isMember(campaign, businessUser, false)).isTrue();
    assertThat(policy.isMember(campaign, businessUser, true)).isTrue();
  }

  @ParameterizedTest
  @EnumSource(UserStatus.class)
  void allMembershipDoesNotDependOnAccountStatus(UserStatus status) {
    PopupCampaign campaign = publishedCampaign(AudienceType.ALL, END_AT);

    assertThat(policy.isMember(
        campaign,
        user(UserRole.USER, status, PUBLISHED_AT.plusSeconds(1)),
        false))
        .isTrue();
  }

  @Test
  void allUsesTheCampaignsCurrentEndAsItsDynamicCutoff() {
    UserEntity user = user(UserRole.USER, UserStatus.ACTIVE, END_AT.plusSeconds(1));

    assertThat(policy.isMember(publishedCampaign(AudienceType.ALL, END_AT), user, false))
        .isFalse();
    assertThat(policy.isMember(
        publishedCampaign(AudienceType.ALL, END_AT.plusSeconds(2)),
        user,
        false))
        .isTrue();
  }

  @Test
  void selectedIncludesOnlyFrozenBusinessUserTargetsAndDoesNotApplyTheAllCutoff() {
    PopupCampaign campaign = publishedCampaign(AudienceType.SELECTED, END_AT);
    UserEntity selected = user(UserRole.USER, UserStatus.ACTIVE, END_AT.plusSeconds(1));

    assertThat(policy.isMember(campaign, selected, true)).isTrue();
    assertThat(policy.isMember(campaign, selected, false)).isFalse();
    assertThat(policy.isMember(
        campaign,
        user(UserRole.ADMIN, UserStatus.ACTIVE, PUBLISHED_AT.minusSeconds(1)),
        true))
        .isFalse();
  }

  @ParameterizedTest
  @EnumSource(UserStatus.class)
  void selectedTargetsRemainMembersAcrossAccountStatusChanges(UserStatus status) {
    PopupCampaign campaign = publishedCampaign(AudienceType.SELECTED, END_AT);

    assertThat(policy.isMember(
        campaign,
        user(UserRole.USER, status, PUBLISHED_AT.minusSeconds(1)),
        true))
        .isTrue();
  }

  private static PopupCampaign publishedCampaign(AudienceType audienceType, Instant endAt) {
    PopupCampaign draft = PopupCampaign.draft(
        UUID.randomUUID(),
        audienceType,
        false,
        ZoneId.of("Asia/Shanghai"),
        PUBLISHED_AT.minusSeconds(1),
        endAt,
        3,
        1,
        Duration.ofHours(4));
    return draft.publishAt(PUBLISHED_AT);
  }

  private static UserEntity user(UserRole role, UserStatus status, Instant createdAt) {
    UserEntity user = new UserEntity();
    user.setId(UUID.randomUUID());
    user.setRole(role);
    user.setStatus(status);
    user.setCreatedAt(createdAt);
    return user;
  }
}
