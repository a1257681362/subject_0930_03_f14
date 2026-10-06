package com.evops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 验收清单条目：批次轮次内按「材质 + 工艺」展开的验收要点，随轮次快照，
 * 旧轮次条目不随重提改写（只追加，无更新列）。
 */
@Data
@TableName("t_repair_acceptance_item")
public class AcceptanceChecklistItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long batchId;

    private Long projectId;

    private Integer roundNo;

    /** 工艺码：CLEAN / REPAIR / STABILIZE / PROTECT */
    private String craft;

    /** 轮次内条目序号，从 1 递增 */
    private Integer itemNo;

    private String itemContent;

    private Long createBy;

    private LocalDateTime createTime;
}
