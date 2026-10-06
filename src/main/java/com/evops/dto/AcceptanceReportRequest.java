package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 修复师提交修复成果报告（museum-14）：按本次涉及的修复工艺与文物材质
 * 生成验收清单，进入馆内专家 + 保管员双确认。
 * 验收整改（RECTIFYING）补修完成后重新提交即产生新一轮（轮次递增）。
 */
@Data
public class AcceptanceReportRequest {

    /** 本次修复涉及的工艺码：CLEAN 清洗 / REPAIR 补配 / STABILIZE 加固 / PROTECT 封护 */
    @NotEmpty(message = "成果报告至少声明一项修复工艺")
    private List<String> crafts;

    @NotBlank(message = "成果报告摘要不能为空")
    private String reportSummary;

    /** 成果报告详述（材料、工序、自检情况等） */
    private String reportDetail;

    @NotBlank(message = "提交人（修复师）不能为空")
    private String reportedBy;
}
