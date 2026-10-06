package com.evops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 验收逐项确认：馆内专家 EXPERT 与保管员 KEEPER 分别确认，不可互代。
 * 一方一轮一条目一行，只追加；任一条目 FAIL（或缺项）该轮进入整改。
 */
@Data
@TableName("t_repair_acceptance_confirm")
public class AcceptanceConfirmRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long batchId;

    private Long itemId;

    private Long projectId;

    private Integer roundNo;

    /** EXPERT 馆内专家 / KEEPER 保管员 */
    private String confirmerRole;

    private String confirmerName;

    /** PASS 合格 / FAIL 不合格 */
    private String result;

    private String opinion;

    private LocalDateTime confirmedAt;

    private Long createBy;

    private LocalDateTime createTime;
}
