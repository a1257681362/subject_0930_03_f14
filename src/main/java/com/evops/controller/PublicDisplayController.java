package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.PublicGrantRequest;
import com.evops.dto.PublicGrantRevokeRequest;
import com.evops.entity.PublicImageGrant;
import com.evops.service.RepairAcceptanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 公众展示与影像授权（museum-14）。
 *
 * <p>公众页面 {@code GET /api/public/relics} 只列出当前开放展示中的文物，
 * 且只展示<b>已授权（最新授权状态 GRANTED）的有效影像与授权时登记的摘要</b>；
 * 未授权、授权已撤销、影像已作废或文物未开放的均不出现。
 * 授权与撤销只追加留痕，撤销不删除影像与授权记录。</p>
 */
@RestController
@RequestMapping("/api")
public class PublicDisplayController {

    private final RepairAcceptanceService acceptanceService;

    public PublicDisplayController(RepairAcceptanceService acceptanceService) {
        this.acceptanceService = acceptanceService;
    }

    /** 公众页面：开放中文物 + 已授权影像与摘要（仅此一个对外只读出口） */
    @GetMapping("/public/relics")
    public ApiResponse<List<Map<String, Object>>> publicCatalog() {
        return ApiResponse.ok(acceptanceService.publicCatalog());
    }

    /** 授权一张影像对公众展示（须登记公众摘要）；授权只追加 */
    @PostMapping("/relics/{relicId}/public-grants")
    public ApiResponse<PublicImageGrant> grant(
            @PathVariable Long relicId,
            @Valid @RequestBody PublicGrantRequest request) {
        return ApiResponse.ok(acceptanceService.grantImage(relicId, request));
    }

    /** 文物的全部公众影像授权记录（含已撤销，便于审计） */
    @GetMapping("/relics/{relicId}/public-grants")
    public ApiResponse<List<PublicImageGrant>> grants(@PathVariable Long relicId) {
        return ApiResponse.ok(acceptanceService.listGrants(relicId));
    }

    /** 撤销公众影像授权：记录保留为 REVOKED，公众页面即刻不再展示 */
    @PostMapping("/public-grants/{grantId}/revoke")
    public ApiResponse<PublicImageGrant> revoke(
            @PathVariable Long grantId,
            @Valid @RequestBody PublicGrantRevokeRequest request) {
        return ApiResponse.ok(acceptanceService.revokeGrant(grantId, request));
    }
}
