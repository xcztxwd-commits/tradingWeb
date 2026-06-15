package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * AdminUserNoteRequest 是后台新增用户备注的请求。
 *
 * @param note 备注正文，用于客服、风控或财务协作。
 */
public record AdminUserNoteRequest(
    @NotBlank String note
) {
}
