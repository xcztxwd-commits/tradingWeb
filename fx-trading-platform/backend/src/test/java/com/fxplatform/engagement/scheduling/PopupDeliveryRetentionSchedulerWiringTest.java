package com.fxplatform.engagement.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.engagement.application.popup.PopupDeliveryRetentionService;
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

class PopupDeliveryRetentionSchedulerWiringTest {

  @Test
  void retentionSchedulerIsAbsentUnlessIndependentlyEnabled() {
    ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(PopupDeliveryRetentionService.class,
            () -> mock(PopupDeliveryRetentionService.class))
        .withUserConfiguration(PopupDeliveryRetentionScheduler.class);

    runner.run(context -> assertThat(
        context.getBeansOfType(PopupDeliveryRetentionScheduler.class)).isEmpty());
    runner.withPropertyValues("app.engagement.retention.enabled=false")
        .run(context -> assertThat(
            context.getBeansOfType(PopupDeliveryRetentionScheduler.class)).isEmpty());
    runner.withPropertyValues("app.engagement.retention.enabled=true")
        .run(context -> assertThat(
            context.getBeansOfType(PopupDeliveryRetentionScheduler.class)).hasSize(1));
  }

  @Test
  void schedulerIsAClosedOptInBoundaryDelegatingToTheTransactionalService()
      throws Exception {
    ConditionalOnProperty condition =
        PopupDeliveryRetentionScheduler.class.getAnnotation(ConditionalOnProperty.class);
    assertThat(condition).isNotNull();
    assertThat(condition.prefix()).isEqualTo("app.engagement.retention");
    assertThat(condition.name()).containsExactly("enabled");
    assertThat(condition.havingValue()).isEqualTo("true");
    assertThat(condition.matchIfMissing()).isFalse();

    Method cleanup = PopupDeliveryRetentionScheduler.class.getDeclaredMethod("cleanup");
    assertThat(cleanup.getAnnotation(Scheduled.class)).isNotNull();
    assertThat(cleanup.getReturnType()).isEqualTo(int.class);
  }

  @Test
  void cleanupDelegatesOnceAndReturnsTheDeletedCount() {
    PopupDeliveryRetentionService service = mock(PopupDeliveryRetentionService.class);
    when(service.deleteExpiredRawDeliveries()).thenReturn(4);

    assertThat(new PopupDeliveryRetentionScheduler(service).cleanup()).isEqualTo(4);

    verify(service).deleteExpiredRawDeliveries();
  }

  @Test
  void baseAndDevDefaultOffWhileProductionExplicitlyOptsIn() throws Exception {
    assertYaml("src/main/resources/application.yml", "${ENGAGEMENT_RETENTION_ENABLED:false}");
    assertYaml(
        "src/main/resources/application-dev.yml", "${ENGAGEMENT_RETENTION_ENABLED:false}");
    assertYaml(
        "src/main/resources/application-prod.yml", "${ENGAGEMENT_RETENTION_ENABLED:true}");
  }

  private static void assertYaml(String path, String expected) throws Exception {
    List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
        path,
        new FileSystemResource(Path.of(path)));
    assertThat(sources).anySatisfy(source -> assertThat(
        source.getProperty("app.engagement.retention.enabled")).isEqualTo(expected));
  }
}
