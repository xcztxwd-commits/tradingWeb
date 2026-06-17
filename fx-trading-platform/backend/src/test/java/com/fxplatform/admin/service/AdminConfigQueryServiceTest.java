package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.config.entity.SystemDictionaryEntity;
import com.fxplatform.config.entity.SystemSettingEntity;
import com.fxplatform.config.repository.SystemDictionaryRepository;
import com.fxplatform.config.repository.SystemSettingRepository;
import com.fxplatform.config.service.SensitiveSettingService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminConfigQueryServiceTest {

  @Mock
  private SystemDictionaryRepository dictionaryRepository;

  @Mock
  private SystemSettingRepository settingRepository;

  private final SensitiveSettingService sensitiveSettingService =
      new SensitiveSettingService("unit-test-config-encryption-key-32chars");

  @Test
  void dictionariesReturnPagedDtos() {
    SystemDictionaryEntity item = new SystemDictionaryEntity();
    item.setId(UUID.randomUUID());
    item.setGroupKey("member_status");
    item.setItemKey("active");
    item.setItemValue("Active");
    item.setEnabled(true);
    item.setDisplayOrder(1);
    when(dictionaryRepository.findAll(any(), eq(Map.of("groupKey", "group_key")), eq("groupKey"), eq(true)))
        .thenReturn(page(item));

    var page = new AdminConfigQueryService(dictionaryRepository, settingRepository, sensitiveSettingService)
        .dictionaries(0, 20);

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).groupKey()).isEqualTo("member_status");
    assertThat(page.items().get(0).itemKey()).isEqualTo("active");
  }

  @Test
  void settingsReturnPagedDtos() {
    SystemSettingEntity setting = new SystemSettingEntity();
    setting.setId(UUID.randomUUID());
    setting.setSettingKey("withdrawal.review.required");
    setting.setSettingValue("true");
    setting.setValueType("BOOLEAN");
    setting.setEditable(true);
    when(settingRepository.findAll(any(), eq(Map.of("settingKey", "setting_key")), eq("settingKey"), eq(true)))
        .thenReturn(page(setting));

    var page = new AdminConfigQueryService(dictionaryRepository, settingRepository, sensitiveSettingService)
        .settings(0, 20);

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).settingKey()).isEqualTo("withdrawal.review.required");
    assertThat(page.items().get(0).settingValue()).isEqualTo("true");
  }

  @Test
  void settingsMaskSensitiveValuesInPagedDtos() {
    SystemSettingEntity setting = new SystemSettingEntity();
    setting.setId(UUID.randomUUID());
    setting.setSettingKey("settings.mailSecretKey");
    setting.setSettingValue("enc:v1:ciphertext");
    setting.setValueType("STRING");
    setting.setEditable(true);
    when(settingRepository.findAll(any(), eq(Map.of("settingKey", "setting_key")), eq("settingKey"), eq(true)))
        .thenReturn(page(setting));

    var page = new AdminConfigQueryService(dictionaryRepository, settingRepository, sensitiveSettingService)
        .settings(0, 20);

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).settingValue()).isEqualTo("********");
  }

  private static <T> Page<T> page(T item) {
    Page<T> page = Page.of(1, 20);
    page.setRecords(List.of(item));
    page.setTotal(1);
    return page;
  }
}
