package com.evops.dto;

import lombok.Data;

/**
 * 修复成果验收列表/导出统一过滤口径。
 */
@Data
public class AcceptanceQuery {

    private Long relicId;

    private Long projectId;

    /** 按批次状态过滤：REPORTED / RECTIFYING / PASSED */
    private String status;

    /** 仅看当前开放中的项目 */
    private Boolean openOnly;

    /** 仅看存在在途补修单的项目 */
    private Boolean reworkOnly;
}
