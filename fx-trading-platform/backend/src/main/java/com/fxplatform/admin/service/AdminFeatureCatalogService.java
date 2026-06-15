package com.fxplatform.admin.service;

import com.fxplatform.admin.dto.response.AdminFeatureActionResponse;
import com.fxplatform.admin.dto.response.AdminFeatureOperationResponse;
import com.fxplatform.admin.dto.response.AdminFeaturePageResponse;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * 按 WH 后台截图沉淀页面、字段、列和动作目录。
 */
@Service
public class AdminFeatureCatalogService {

  private final Map<String, AdminFeaturePageResponse> pageMap;

  public AdminFeatureCatalogService() {
    this.pageMap = buildPages();
  }

  /**
   * 返回全部后台页面配置，供前端菜单和通用页面渲染使用。
   */
  public List<AdminFeaturePageResponse> pages() {
    return List.copyOf(pageMap.values());
  }

  /**
   * 返回单个后台页面完整配置。
   */
  public AdminFeaturePageResponse page(String key) {
    AdminFeaturePageResponse page = pageMap.get(key);
    if (page == null) {
      throw new IllegalArgumentException("未知后台页面：" + key);
    }
    return page;
  }

  /**
   * 目录级动作预览。真实落库和领域 Service 分派由 AdminFeatureOperationService 执行。
   */
  public AdminFeatureOperationResponse performAction(String pageKey, String actionKey, String rowId, String reason) {
    AdminFeaturePageResponse page = page(pageKey);
    AdminFeatureActionResponse action = Stream.concat(page.toolbarActions().stream(), page.rowActions().stream())
        .filter(item -> item.key().equals(actionKey))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("未知后台动作：" + actionKey));
    String target = rowId == null || rowId.isBlank() ? "所选记录" : rowId;
    String suffix = reason == null || reason.isBlank() ? "" : "，原因：" + reason;
    return new AdminFeatureOperationResponse(
        pageKey,
        actionKey,
        target,
        true,
        page.title() + "已执行" + action.label() + "操作，目标：" + target + suffix);
  }

  private static Map<String, AdminFeaturePageResponse> buildPages() {
    LinkedHashMap<String, AdminFeaturePageResponse> pages = new LinkedHashMap<>();

    Stream.of(
            AdminPermissionFeaturePages.pages(),
            AdminProductFeaturePages.pages(),
            AdminFinanceFeaturePages.pages(),
            AdminMemberFeaturePages.pages(),
            AdminOrderFeaturePages.pages(),
            AdminContentFeaturePages.pages(),
            AdminSettingsFeaturePages.pages())
        .flatMap(List::stream)
        .forEach(page -> pages.put(page.key(), page));

    return Collections.unmodifiableMap(pages);
  }
}
