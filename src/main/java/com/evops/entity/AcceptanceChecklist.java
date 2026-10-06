package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 修复成果验收批次：一个项目按轮次一行，整改重提产生新一轮。
 * 状态：REPORTED 已提交成果报告待双确认 -> RECTIFYING 整改中 -> PASSED 验收通过。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_repair_acceptance_batch")
public class AcceptanceChecklist extends BaseEntity {

    private Long projectId;

    /** 验收轮次，项目内从 1 递增；整改后重提递增 */
    private Integer roundNo;

    /** 生成清单时的文物材质快照 */
    private String materialSnapshot;

    /** 本轮报告涉及的工艺码，逗号分隔，如 CLEAN,REPAIR */
    private String crafts;

    private String reportSummary;

    private String reportDetail;

    /** 提交成果报告的修复师 */
    private String reportedBy;

    private LocalDateTime reportedAt;

    /** REPORTED / RECTIFYING / PASSED */
    private String status;

    /** 专家确认时提出的验收结论：PASS / CONDITIONAL */
    private String proposedResult;

    /** CONDITIONAL 时登记的验收后保护等级 ONE/TWO/THREE */
    private String proposedGradeAfter;

    private LocalDateTime passedAt;

    /** 验收通过后固化的 t_repair_acceptance 记录 id */
    private Long finalizedAcceptanceId;

    /** 本轮进入整改时开立的补修单 id */
    private Long reworkOrderId;
}
