package com.fxplatform.engagement.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.engagement.application.campaign.PopupCampaignService;
import com.fxplatform.engagement.application.content.ContentRevisionService;
import com.fxplatform.engagement.application.message.CampaignMessageSyncService;
import com.fxplatform.engagement.application.message.MessagePublicationService;
import com.fxplatform.engagement.application.message.MessageReceiptService;
import com.fxplatform.engagement.domain.campaign.PopupCampaign;
import com.fxplatform.engagement.persistence.entity.MessagePublicationEntity;
import com.fxplatform.engagement.persistence.entity.PopupCampaignEntity;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.DeviceScope;
import com.fxplatform.engagement.persistence.enums.DisplayScope;
import com.fxplatform.engagement.persistence.enums.MessageLifecycleStatus;
import com.fxplatform.engagement.persistence.enums.MessageSourceType;
import com.fxplatform.engagement.persistence.enums.PopupCampaignLifecycleStatus;
import com.fxplatform.engagement.persistence.enums.TemplateSize;
import com.fxplatform.engagement.persistence.repository.EngagementUserLockRepository;
import com.fxplatform.engagement.persistence.repository.MessagePublicationRepository;
import com.fxplatform.engagement.persistence.repository.MessageReceiptRepository;
import com.fxplatform.engagement.persistence.repository.MessageTargetRepository;
import com.fxplatform.engagement.persistence.repository.PopupCampaignRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

class EngagementSchedulingTest {

  private static final Instant DUE = Instant.parse("2026-07-20T10:00:00Z");
  private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000601");

  @Test
  void directDispatchKeepsScheduledWorkHiddenBeforeDueAndVisibleAtAndAfterDue()
      throws Exception {
    MutableClock clock = new MutableClock(DUE.minusSeconds(1));
    PopupCampaignEntity campaign = scheduledCampaign();
    MessagePublicationEntity message = scheduledMessage();
    PopupCampaignRepository campaignRepository = campaignRepository(clock, campaign);
    MessagePublicationRepository messageRepository = messageRepository(clock, message);

    PopupCampaignService campaignService = construct(
        PopupCampaignService.class,
        campaignRepository,
        clock,
        mock(CampaignMessageSyncService.class));
    MessagePublicationService messageService = construct(
        MessagePublicationService.class,
        mock(ContentRevisionService.class),
        messageRepository,
        mock(MessageTargetRepository.class),
        mock(UserRepository.class),
        clock);
    Object dispatcher = construct(
        requireType("com.fxplatform.engagement.scheduling.EngagementScheduledDispatcher"),
        campaignService,
        messageService);
    MessageReceiptService receiptService = receiptService(clock, message);

    assertThat(dispatch(dispatcher)).isZero();
    assertThat(campaign.getLifecycleStatus()).isEqualTo(PopupCampaignLifecycleStatus.SCHEDULED);
    assertThat(message.getLifecycleStatus()).isEqualTo(MessageLifecycleStatus.SCHEDULED);
    assertThat(receiptService.unreadCount(UUID.randomUUID())).isZero();

    clock.set(DUE);
    assertThat(dispatch(dispatcher)).isEqualTo(2);
    assertThat(campaign.getLifecycleStatus()).isEqualTo(PopupCampaignLifecycleStatus.ACTIVE);
    assertThat(message.getLifecycleStatus()).isEqualTo(MessageLifecycleStatus.SENT);
    assertThat(message.getSentAt()).isEqualTo(DUE);
    assertThat(receiptService.unreadCount(UUID.randomUUID())).isOne();

    clock.set(DUE.plusSeconds(1));
    assertThat(dispatch(dispatcher)).isZero();
    assertThat(receiptService.unreadCount(UUID.randomUUID())).isOne();
  }

  @Test
  void campaignDueQueryUsesTheSameInclusiveBoundaryAndSkipLockedOrdering() throws Exception {
    Method due = PopupCampaignRepository.class.getMethod("findDueForUpdate", Instant.class);
    Select select = due.getAnnotation(Select.class);
    String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").toUpperCase();

    assertThat(sql).contains(
        "LIFECYCLE_STATUS = 'SCHEDULED'",
        "START_AT <= #{NOW}",
        "ORDER BY START_AT ASC, ID ASC",
        "FOR UPDATE SKIP LOCKED");
  }

  @Test
  void campaignDomainOwnsTheScheduledBoundaryAndEndsAlreadyExpiredWork() throws Exception {
    Method activation = PopupCampaign.class.getMethod("activateScheduledAt", Instant.class);
    PopupCampaign scheduled = scheduledCampaignDomain(DUE.plusSeconds(3600));

    PopupCampaign before = (PopupCampaign) invoke(
        scheduled, activation, DUE.minusSeconds(1));
    PopupCampaign active = (PopupCampaign) invoke(scheduled, activation, DUE);
    PopupCampaign expired = (PopupCampaign) invoke(
        scheduledCampaignDomain(DUE.plusSeconds(30)),
        activation,
        DUE.plusSeconds(30));

    assertThat(before).isSameAs(scheduled);
    assertThat(active.lifecycleStatus()).isEqualTo(PopupCampaignLifecycleStatus.ACTIVE);
    assertThat(expired.lifecycleStatus()).isEqualTo(PopupCampaignLifecycleStatus.ENDED);
    assertThat(expired.endedAt()).isEqualTo(DUE.plusSeconds(30));
  }

  private static PopupCampaignRepository campaignRepository(
      MutableClock clock,
      PopupCampaignEntity campaign) {
    return mock(PopupCampaignRepository.class, invocation -> {
      if (invocation.getMethod().getName().equals("findDueForUpdate")) {
        Instant now = invocation.getArgument(0);
        return campaign.getLifecycleStatus() == PopupCampaignLifecycleStatus.SCHEDULED
                && !campaign.getStartAt().isAfter(now)
            ? List.of(campaign)
            : List.of();
      }
      if (invocation.getMethod().getName().equals("updateById")) {
        return 1;
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
  }

  private static MessagePublicationRepository messageRepository(
      MutableClock clock,
      MessagePublicationEntity message) {
    return mock(MessagePublicationRepository.class, invocation -> {
      if (invocation.getMethod().getName().equals("findDueForUpdate")) {
        Instant now = invocation.getArgument(0);
        return message.getLifecycleStatus() == MessageLifecycleStatus.SCHEDULED
                && !message.getScheduledAt().isAfter(now)
            ? List.of(message)
            : List.of();
      }
      if (invocation.getMethod().getName().equals("updateById")) {
        return 1;
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
  }

  private static MessageReceiptService receiptService(
      Clock clock,
      MessagePublicationEntity message) {
    MessageReceiptRepository receiptRepository = mock(
        MessageReceiptRepository.class,
        invocation -> {
          if (invocation.getMethod().getName().equals("countVisibleMessages")) {
            Instant now = invocation.getArgument(1);
            return message.getLifecycleStatus() == MessageLifecycleStatus.SENT
                    && !message.getSentAt().isAfter(now)
                ? 1L
                : 0L;
          }
          return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    return new MessageReceiptService(
        mock(EngagementUserLockRepository.class), receiptRepository, clock);
  }

  private static int dispatch(Object dispatcher) throws Exception {
    return (int) invoke(dispatcher, dispatcher.getClass().getMethod("dispatchDue"));
  }

  private static Object invoke(Object target, Method method) throws Exception {
    return invoke(target, method, new Object[0]);
  }

  private static Object invoke(Object target, Method method, Object... arguments) throws Exception {
    try {
      return method.invoke(target, arguments);
    } catch (InvocationTargetException exception) {
      if (exception.getCause() instanceof Exception cause) {
        throw cause;
      }
      throw exception;
    }
  }

  private static PopupCampaignEntity scheduledCampaign() {
    PopupCampaignEntity campaign = new PopupCampaignEntity();
    campaign.setId(UUID.randomUUID());
    campaign.setName("Scheduled campaign");
    campaign.setContentItemId(UUID.randomUUID());
    campaign.setLifecycleStatus(PopupCampaignLifecycleStatus.SCHEDULED);
    campaign.setAudienceType(AudienceType.ALL);
    campaign.setSyncToInbox(false);
    campaign.setPriority(1);
    campaign.setDisplayScope(DisplayScope.ALL_BUSINESS_PAGES);
    campaign.setPageKeys("[]");
    campaign.setDeviceScope(DeviceScope.ALL);
    campaign.setTemplateSize(TemplateSize.MEDIUM);
    campaign.setTimeZone("UTC");
    campaign.setStartAt(DUE);
    campaign.setEndAt(DUE.plusSeconds(3600));
    campaign.setMaxTotalImpressions(3);
    campaign.setMaxDailyImpressions(1);
    campaign.setMinIntervalSeconds(0);
    campaign.setFirstPublishedAt(DUE.minusSeconds(60));
    campaign.setLastPublishedAt(DUE.minusSeconds(60));
    campaign.setCreatedBy(ACTOR_ID);
    campaign.setUpdatedBy(ACTOR_ID);
    return campaign;
  }

  private static PopupCampaign scheduledCampaignDomain(Instant endAt) {
    return PopupCampaign.rehydrate(
        UUID.randomUUID(),
        PopupCampaignLifecycleStatus.SCHEDULED,
        AudienceType.ALL,
        false,
        ZoneId.of("UTC"),
        DUE,
        endAt,
        3,
        1,
        java.time.Duration.ZERO,
        DUE.minusSeconds(60),
        DUE.minusSeconds(60),
        null,
        null,
        null);
  }

  private static MessagePublicationEntity scheduledMessage() {
    MessagePublicationEntity message = new MessagePublicationEntity();
    message.setId(UUID.randomUUID());
    message.setContentItemId(UUID.randomUUID());
    message.setSourceType(MessageSourceType.MANUAL);
    message.setAudienceType(AudienceType.ALL);
    message.setLifecycleStatus(MessageLifecycleStatus.SCHEDULED);
    message.setCategory("NOTICE");
    message.setScheduledAt(DUE);
    message.setCreatedBy(ACTOR_ID);
    message.setUpdatedBy(ACTOR_ID);
    return message;
  }

  @SuppressWarnings("unchecked")
  private static <T> T construct(Class<T> type, Object... knownDependencies) throws Exception {
    Constructor<?> constructor = List.of(type.getDeclaredConstructors()).stream()
        .max(java.util.Comparator.comparingInt(Constructor::getParameterCount))
        .orElseThrow();
    constructor.setAccessible(true);
    Object[] arguments = List.of(constructor.getParameterTypes()).stream()
        .map(parameter -> dependency(parameter, knownDependencies))
        .toArray();
    return (T) constructor.newInstance(arguments);
  }

  private static Object dependency(Class<?> type, Object[] knownDependencies) {
    for (Object dependency : knownDependencies) {
      if (type.isInstance(dependency)) {
        return dependency;
      }
    }
    return mock(type, Answers.RETURNS_DEFAULTS);
  }

  private static Class<?> requireType(String className) {
    try {
      return Class.forName(className);
    } catch (ClassNotFoundException exception) {
      return fail("Missing engagement scheduler dispatcher " + className, exception);
    }
  }

  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void set(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
