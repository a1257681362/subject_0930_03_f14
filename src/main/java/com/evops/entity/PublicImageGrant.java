package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 公众影像授权：只追加。授权时必须登记对公众展示的摘要；撤销只置 REVOKED，
 * 不删授权行、不影响影像留档。公众页面只展示最新状态为 GRANTED 的影像与摘要。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_public_image_grant")
public class PublicImageGrant extends BaseEntity {

    private Long relicId;

    private Long imageId;

    private Long projectId;

    /** 对公众展示的摘要（不含修复细节等内部信息） */
    private String publicSummary;

    /** GRANTED 已授权 / REVOKED 已撤销 */
    private String status;

    private String grantedBy;

    private LocalDateTime grantedAt;

    private String revokedBy;

    private LocalDateTime revokedAt;

    private String revokeReason;
}
