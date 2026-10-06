package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 验收逐项确认中的单条结论。
 */
@Data
public class AcceptanceItemResult {

    /** 验收清单条目 id（t_repair_acceptance_item.id） */
    private Long itemId;

    /** PASS 合格 / FAIL 不合格；未出现在提交列表中的条目按「缺项」处理 */
    @NotBlank(message = "条目结论不能为空")
    private String result;

    private String opinion;
}
