package com.fxplatform.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.response.AdminDictionaryResponse;
import com.fxplatform.admin.dto.response.AdminSystemSettingResponse;
import com.fxplatform.config.entity.SystemDictionaryEntity;
import com.fxplatform.config.entity.SystemSettingEntity;
import com.fxplatform.config.repository.SystemDictionaryRepository;
import com.fxplatform.config.repository.SystemSettingRepository;
import com.fxplatform.config.service.SensitiveSettingService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AdminConfigQueryService 提供后台字典和系统设置查询能力。
 */
@Service
@RequiredArgsConstructor
public class AdminConfigQueryService {

  /** 字典 Mapper，用于分页读取字典项。 */
  private final SystemDictionaryRepository dictionaryRepository;
  /** 设置 Mapper，用于分页读取系统设置。 */
  private final SystemSettingRepository settingRepository;
  private final SensitiveSettingService sensitiveSettingService;

  /** 分页查询字典项。 */
  public AdminPageResponse<AdminDictionaryResponse> dictionaries(int page, int size) {
    return AdminPageResponse.from(dictionaryRepository
        .findAll(AdminPageRequests.page(page, size), Map.of("groupKey", "group_key"), "groupKey", true)
        .convert(AdminDictionaryResponse::from));
  }

  /** 按截图字典列表协议分页筛选字典项。 */
  public AdminPageResponse<AdminDictionaryResponse> dictionaries(AdminFeaturePageQuery query) {
    QueryWrapper<SystemDictionaryEntity> wrapper = new QueryWrapper<>();
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "groupKey", "group_key");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "itemKey", "item_key");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "itemValue", "item_value");
    AdminFeatureQuerySupport.eqBooleanIfPresent(wrapper, query, "enabled", "enabled");
    AdminFeatureQuerySupport.eqBooleanIfPresent(wrapper, query, "status", "enabled");
    AdminFeatureQuerySupport.applyOrder(wrapper, query, Map.of(
        "groupKey", "group_key",
        "itemKey", "item_key",
        "itemValue", "item_value",
        "displayOrder", "display_order",
        "enabled", "enabled",
        "status", "enabled"), "group_key", true);
    return AdminPageResponse.from(dictionaryRepository
        .selectPage(AdminFeatureQuerySupport.page(query), wrapper)
        .convert(AdminDictionaryResponse::from));
  }

  /** 分页查询系统设置。 */
  public AdminPageResponse<AdminSystemSettingResponse> settings(int page, int size) {
    return AdminPageResponse.from(settingRepository
        .findAll(AdminPageRequests.page(page, size), Map.of("settingKey", "setting_key"), "settingKey", true)
        .convert(setting -> AdminSystemSettingResponse.from(setting, sensitiveSettingService)));
  }

  /** 按截图管理配置列表协议分页筛选系统配置。 */
  public AdminPageResponse<AdminSystemSettingResponse> settings(AdminFeaturePageQuery query) {
    QueryWrapper<SystemSettingEntity> wrapper = new QueryWrapper<>();
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "settingKey", "setting_key");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "configKey", "setting_key");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "settingValue", "setting_value");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "description", "description");
    AdminFeatureQuerySupport.eqBooleanIfPresent(wrapper, query, "editable", "editable");
    AdminFeatureQuerySupport.applyOrder(wrapper, query, Map.of(
        "settingKey", "setting_key",
        "configKey", "setting_key",
        "valueType", "value_type",
        "editable", "editable",
        "createdAt", "created_at",
        "updatedAt", "updated_at"), "setting_key", true);
    return AdminPageResponse.from(settingRepository
        .selectPage(AdminFeatureQuerySupport.page(query), wrapper)
        .convert(setting -> AdminSystemSettingResponse.from(setting, sensitiveSettingService)));
  }
}
