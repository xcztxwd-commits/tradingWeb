package com.fxplatform.admin.service;

import com.fxplatform.admin.dto.request.AdminSystemSettingRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SettingsFeatureActionHandler implements AdminFeatureActionHandler {

  private final AdminConfigCommandService configCommandService;

  @Override
  public boolean supports(String pageKey, String action) {
    return pageKey.startsWith("settings-") && ("submit".equals(action) || "edit".equals(action));
  }

  @Override
  public String handle(AdminFeatureActionContext context) {
    for (Map.Entry<String, Object> entry : context.payload().entrySet()) {
      if (entry.getValue() == null) {
        continue;
      }
      configCommandService.updateSetting(context.actorUserId(), new AdminSystemSettingRequest(
          entry.getKey(),
          String.valueOf(entry.getValue()),
          AdminFeaturePayloads.valueType(entry.getValue()),
          context.pageKey() + "." + entry.getKey(),
          true));
    }
    return null;
  }
}
