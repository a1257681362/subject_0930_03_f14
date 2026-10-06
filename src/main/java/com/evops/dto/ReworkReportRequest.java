package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 验收通过后发现问题，开立补修单（POST_PASS）：
 * 自动撤下开放展示标记（原开放记录保留），项目进入补修，完成后须重新验收。
 */
@Data
public class ReworkReportRequest {

    @NotBlank(message = "问题描述不能为空")
    private String reason;

    @NotBlank(message = "报告人不能为空")
    private String reportedBy;
}
