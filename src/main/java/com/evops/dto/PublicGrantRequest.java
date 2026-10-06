package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 公众影像授权：把一张已归档影像授权为对公众展示，并登记公众可见摘要。
 */
@Data
public class PublicGrantRequest {

    /** t_relic_image.id，必须属于该文物且为有效影像 */
    private Long imageId;

    @NotBlank(message = "公众摘要不能为空")
    private String publicSummary;

    @NotBlank(message = "授权人不能为空")
    private String grantedBy;
}
