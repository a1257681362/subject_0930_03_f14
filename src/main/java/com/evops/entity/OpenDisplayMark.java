package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 开放展示标记：每次开放一段一行。只能在验收通过后设置；
 * 验收通过后开立补修单时自动撤下（status=OFF 并回填 reworkOrderId），
 * 原开放人/开放时间永久保留；重新验收通过后再次开放另起新行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_open_display_mark")
public class OpenDisplayMark extends BaseEntity {

    private Long projectId;

    private Long relicId;

    /** ON 开放中 / OFF 已撤下 */
    private String status;

    /** ON 时 = projectId 的稀疏唯一键 */
    private Long openKey;

    private String markedBy;

    private LocalDateTime markedAt;

    private String turnedOffBy;

    private LocalDateTime turnedOffAt;

    private String turnOffReason;

    /** 撤下时关联的补修单 id（补修开立自动撤下场景） */
    private Long reworkOrderId;
}
