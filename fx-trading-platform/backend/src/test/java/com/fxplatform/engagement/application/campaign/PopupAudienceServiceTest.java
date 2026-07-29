package com.fxplatform.engagement.application.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.enums.UserRole;
import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.engagement.persistence.entity.PopupCampaignEntity;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.repository.PopupCampaignRepository;
import com.fxplatform.engagement.persistence.repository.PopupCampaignTargetRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class PopupAudienceServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-19T04:00:00Z");

  @Mock
  private PopupCampaignRepository campaignRepository;
  @Mock
  private UserRepository userRepository;
  @Mock
  private PopupCampaignTargetRepository targetRepository;
  private PopupAudienceService service;

  @BeforeEach
  void setUp() {
    service = new PopupAudienceService(campaignRepository, userRepository, targetRepository);
  }

  @Test
  void locksCanonicalDraftBeforeReplacingDeduplicatedValidatedBusinessUsers() {
    UUID campaignId = UUID.randomUUID();
    UserEntity active = user(UserRole.USER, UserStatus.ACTIVE);
    UserEntity frozen = user(UserRole.USER, UserStatus.FROZEN);
    UserEntity disabled = user(UserRole.USER, UserStatus.DISABLED);
    Set<UUID> uniqueIds = Set.of(active.getId(), frozen.getId(), disabled.getId());
    when(campaignRepository.selectByIdForUpdate(campaignId)).thenReturn(selectedCampaign(campaignId));
    when(userRepository.selectBatchIds(eq(uniqueIds)))
        .thenReturn(List.of(active, frozen, disabled));
    when(targetRepository.insertIfAbsent(eq(campaignId), any(UUID.class))).thenReturn(1);

    service.replaceTargets(
        campaignId,
        List.of(active.getId(), frozen.getId(), active.getId(), disabled.getId()));

    InOrder order = inOrder(campaignRepository, userRepository, targetRepository);
    order.verify(campaignRepository).selectByIdForUpdate(campaignId);
    order.verify(userRepository).selectBatchIds(eq(uniqueIds));
    order.verify(targetRepository).deleteByCampaignId(campaignId);
    verify(targetRepository).insertIfAbsent(campaignId, active.getId());
    verify(targetRepository).insertIfAbsent(campaignId, frozen.getId());
    verify(targetRepository).insertIfAbsent(campaignId, disabled.getId());
  }

  @Test
  void rejectsCanonicalPublishedCampaignWithoutReadingUsersOrWritingTargets() {
    UUID campaignId = UUID.randomUUID();
    PopupCampaignEntity published = selectedCampaign(campaignId);
    published.setFirstPublishedAt(NOW);
    when(campaignRepository.selectByIdForUpdate(campaignId)).thenReturn(published);

    assertThatIllegalStateException()
        .isThrownBy(() -> service.replaceTargets(campaignId, Set.of(UUID.randomUUID())))
        .withMessageContaining("frozen");

    verify(campaignRepository).selectByIdForUpdate(campaignId);
    verifyNoInteractions(userRepository, targetRepository);
  }

  @Test
  void rejectsAllAudienceCampaignWithoutReadingUsersOrWritingTargets() {
    UUID campaignId = UUID.randomUUID();
    PopupCampaignEntity allAudience = selectedCampaign(campaignId);
    allAudience.setAudienceType(AudienceType.ALL);
    when(campaignRepository.selectByIdForUpdate(campaignId)).thenReturn(allAudience);

    assertThatIllegalStateException()
        .isThrownBy(() -> service.replaceTargets(campaignId, Set.of(UUID.randomUUID())))
        .withMessageContaining("SELECTED");

    verify(campaignRepository).selectByIdForUpdate(campaignId);
    verifyNoInteractions(userRepository, targetRepository);
  }

  @Test
  void rejectsMissingUsersBeforeDeletingExistingTargets() {
    UUID campaignId = UUID.randomUUID();
    UserEntity existing = user(UserRole.USER, UserStatus.ACTIVE);
    UUID missingId = UUID.randomUUID();
    Set<UUID> requested = Set.of(existing.getId(), missingId);
    when(campaignRepository.selectByIdForUpdate(campaignId)).thenReturn(selectedCampaign(campaignId));
    when(userRepository.selectBatchIds(eq(requested))).thenReturn(List.of(existing));

    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.replaceTargets(campaignId, requested));

    verify(targetRepository, never()).deleteByCampaignId(campaignId);
    verify(targetRepository, never()).insertIfAbsent(eq(campaignId), any(UUID.class));
  }

  @Test
  void rejectsAdminsBeforeDeletingExistingTargets() {
    UUID campaignId = UUID.randomUUID();
    UserEntity businessUser = user(UserRole.USER, UserStatus.ACTIVE);
    UserEntity admin = user(UserRole.ADMIN, UserStatus.ACTIVE);
    Set<UUID> requested = Set.of(businessUser.getId(), admin.getId());
    when(campaignRepository.selectByIdForUpdate(campaignId)).thenReturn(selectedCampaign(campaignId));
    when(userRepository.selectBatchIds(eq(requested))).thenReturn(List.of(businessUser, admin));

    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.replaceTargets(campaignId, requested));

    verify(targetRepository, never()).deleteByCampaignId(campaignId);
    verify(targetRepository, never()).insertIfAbsent(eq(campaignId), any(UUID.class));
  }

  @Test
  void locksCampaignThenRejectsNullTargetBeforeAnyTargetWrite() {
    UUID campaignId = UUID.randomUUID();
    when(campaignRepository.selectByIdForUpdate(campaignId)).thenReturn(selectedCampaign(campaignId));

    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.replaceTargets(campaignId, Arrays.asList(UUID.randomUUID(), null)));

    verify(campaignRepository).selectByIdForUpdate(campaignId);
    verifyNoInteractions(userRepository, targetRepository);
  }

  @Test
  void failsWhenCanonicalCampaignDoesNotExist() {
    UUID campaignId = UUID.randomUUID();
    when(campaignRepository.selectByIdForUpdate(campaignId)).thenReturn(null);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.replaceTargets(campaignId, Set.of()))
        .withMessageContaining("not found");

    verifyNoInteractions(userRepository, targetRepository);
  }

  @Test
  void clearsUnpublishedSelectedCampaignWithoutUserLookup() {
    UUID campaignId = UUID.randomUUID();
    when(campaignRepository.selectByIdForUpdate(campaignId)).thenReturn(selectedCampaign(campaignId));

    service.replaceTargets(campaignId, Set.of());

    verifyNoInteractions(userRepository);
    verify(targetRepository).deleteByCampaignId(campaignId);
    verify(targetRepository, never()).insertIfAbsent(eq(campaignId), any(UUID.class));
  }

  @Test
  void treatsDuplicateInsertAsWriteConflict() {
    UUID campaignId = UUID.randomUUID();
    UserEntity user = user(UserRole.USER, UserStatus.ACTIVE);
    when(campaignRepository.selectByIdForUpdate(campaignId)).thenReturn(selectedCampaign(campaignId));
    when(userRepository.selectBatchIds(Set.of(user.getId()))).thenReturn(List.of(user));
    when(targetRepository.insertIfAbsent(campaignId, user.getId())).thenReturn(0);

    assertThatIllegalStateException()
        .isThrownBy(() -> service.replaceTargets(campaignId, Set.of(user.getId())))
        .withMessageContaining("conflict");

    verify(targetRepository).deleteByCampaignId(campaignId);
  }

  @Test
  void replacementIsTransactional() throws NoSuchMethodException {
    Transactional annotation = PopupAudienceService.class
        .getMethod("replaceTargets", UUID.class, java.util.Collection.class)
        .getAnnotation(Transactional.class);

    assertThat(annotation).isNotNull();
  }

  private static PopupCampaignEntity selectedCampaign(UUID campaignId) {
    PopupCampaignEntity campaign = new PopupCampaignEntity();
    campaign.setId(campaignId);
    campaign.setAudienceType(AudienceType.SELECTED);
    return campaign;
  }

  private static UserEntity user(UserRole role, UserStatus status) {
    UserEntity user = new UserEntity();
    user.setId(UUID.randomUUID());
    user.setRole(role);
    user.setStatus(status);
    return user;
  }
}
