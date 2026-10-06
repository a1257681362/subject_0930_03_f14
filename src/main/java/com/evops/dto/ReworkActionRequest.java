package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 补修单推进：开工（OPEN->DOING）/ 完成（DOING->DONE）。
 * 完成时填写补修结果说明；验收整改类补修完成后方可提交新一轮成果报告，
 * 验收后补修完成后项目回到待验收，须重新双确认通过方可再次开放。
 */
@Data
public class ReworkActionRequest {

    @NotBlank(message = "操作人不能为空")
    private String operator;

    /** 完成补修时填写的结果说明（开工可不填） */
    private String note;
}
