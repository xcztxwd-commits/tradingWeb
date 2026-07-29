package com.fxplatform.engagement.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

import com.fxplatform.engagement.application.campaign.PopupCampaignService;
import com.fxplatform.engagement.application.message.MessagePublicationService;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Scheduled;

class EngagementSchedulerWiringTest {

  private static final String DISPATCHER_TYPE =
      "com.fxplatform.engagement.scheduling.EngagementScheduledDispatcher";

  @Test
  void schedulerBeanIsAbsentUnlessExplicitlyEnabled() {
    Class<?> dispatcher = requireType(DISPATCHER_TYPE);
    ApplicationContextRunner runner = contextRunner(dispatcher);

    runner.run(context -> assertThat(context.getBeanNamesForType(dispatcher)).isEmpty());
    runner.withPropertyValues("app.engagement.scheduler.enabled=false")
        .run(context -> assertThat(context.getBeanNamesForType(dispatcher)).isEmpty());
    runner.withPropertyValues("app.engagement.scheduler.enabled=true")
        .run(context -> assertThat(context.getBeanNamesForType(dispatcher)).hasSize(1));
  }

  @Test
  void dispatcherIsAClosedOptInScheduledBoundary() throws Exception {
    Class<?> dispatcher = requireType(DISPATCHER_TYPE);
    ConditionalOnProperty condition = dispatcher.getAnnotation(ConditionalOnProperty.class);

    assertThat(condition).isNotNull();
    assertThat(condition.prefix()).isEqualTo("app.engagement.scheduler");
    assertThat(condition.name()).containsExactly("enabled");
    assertThat(condition.havingValue()).isEqualTo("true");
    assertThat(condition.matchIfMissing()).isFalse();

    Method dispatch = dispatcher.getDeclaredMethod("dispatchDue");
    assertThat(dispatch.getAnnotation(Scheduled.class)).isNotNull();
    assertThat(dispatch.getReturnType()).isEqualTo(int.class);
  }

  @Test
  void baseAndDevDefaultOffWhileProductionExplicitlyOptsIn() throws Exception {
    assertYaml("src/main/resources/application.yml",
        "${ENGAGEMENT_SCHEDULER_ENABLED:false}");
    assertYaml("src/main/resources/application-dev.yml",
        "${ENGAGEMENT_SCHEDULER_ENABLED:false}");
    assertYaml("src/main/resources/application-prod.yml",
        "${ENGAGEMENT_SCHEDULER_ENABLED:true}");
  }

  private static ApplicationContextRunner contextRunner(Class<?> dispatcher) {
    return new ApplicationContextRunner()
        .withBean(PopupCampaignService.class, () -> mock(PopupCampaignService.class))
        .withBean(MessagePublicationService.class, () -> mock(MessagePublicationService.class))
        .withUserConfiguration(dispatcher);
  }

  private static void assertYaml(String path, String expected) throws Exception {
    List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
        path,
        new FileSystemResource(Path.of(path)));
    assertThat(sources).anySatisfy(source -> assertThat(
        source.getProperty("app.engagement.scheduler.enabled")).isEqualTo(expected));
  }

  private static Class<?> requireType(String className) {
    try {
      return Class.forName(className);
    } catch (ClassNotFoundException exception) {
      return fail("Missing engagement scheduler dispatcher " + className, exception);
    }
  }
}
