package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 撤销公众影像授权：授权记录保留为 REVOKED，不删行、不影响影像留档。
 */
@Data
public class PublicGrantRevokeRequest {

    @NotBlank(message = "撤销人不能为空")
    private String revokedBy;

    @NotBlank(message = "撤销原因不能为空")
    private String reason;
}
