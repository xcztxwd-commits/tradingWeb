package com.fxplatform.admin.dto.request;

/**
 * 会员详情资料保存请求。
 */
public record AdminUserProfileRequest(
    String realName,
    String phone,
    String address,
    String remark
) {
}
