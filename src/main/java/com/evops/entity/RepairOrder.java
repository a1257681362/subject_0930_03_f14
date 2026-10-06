package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 补修单：验收整改（RECTIFY，缺项/不合格自动开立）或验收通过后发现问题
 * （POST_PASS）。只追加，OPEN -> DOING -> DONE；POST_PASS 开立时自动撤下
 * 开放展示标记，完成后须重新验收，原开放记录保留。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_repair_rework_order")
public class RepairOrder extends BaseEntity {

    private String reworkNo;

    private Long projectId;

    private Long relicId;

    /** RECTIFY 验收整改 / POST_PASS 验收通过后发现问题 */
    private String source;

    /** RECTIFY 时记录触发的验收轮次 */
    private Integer roundNo;

    private Long triggerBatchId;

    private String reason;

    private String reportedBy;

    /** OPEN / DOING / DONE */
    private String status;

    /** POST_PASS 未完成时 = projectId 的稀疏唯一键 */
    private Long postOpenKey;

    private String startedBy;

    private LocalDateTime startedAt;

    private String finishedBy;

    private LocalDateTime finishedAt;

    private String resultNote;
}
