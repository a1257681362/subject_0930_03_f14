package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 设置开放展示标记 / 手动撤下 的操作人。
 */
@Data
public class OpenMarkRequest {

    @NotBlank(message = "操作人不能为空")
    private String operator;

    /** 手动撤下时的原因（补修单自动撤下由系统填写） */
    private String reason;
}
