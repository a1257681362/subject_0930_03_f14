package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.AcceptanceQuery;
import com.evops.service.RepairAcceptanceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 修复成果验收列表与导出（museum-14）：页面列表与 CSV 导出共用
 * {@link RepairAcceptanceService#queryRows} 同一行集、同一顺序与列口径，
 * 可与项目详情（/api/repair-projects/{id}/acceptance）和台账流水交叉核对，
 * 不能只看详情。
 */
@RestController
@RequestMapping("/api/repair-acceptances")
public class RepairAcceptanceListController {

    private final RepairAcceptanceService acceptanceService;

    public RepairAcceptanceListController(RepairAcceptanceService acceptanceService) {
        this.acceptanceService = acceptanceService;
    }

    /** 验收批次列表：可按文物/项目/批次状态过滤，或仅看开放中、在途补修 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(AcceptanceQuery query) {
        return ApiResponse.ok(acceptanceService.queryRows(query));
    }

    /** CSV 导出：与列表同一行集同一顺序 */
    @GetMapping("/export")
    public ResponseEntity<String> export(AcceptanceQuery query) {
        String csv = acceptanceService.exportCsv(query);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDispositionFormData("attachment", "repair-acceptances.csv");
        return new ResponseEntity<>(csv, headers, org.springframework.http.HttpStatus.OK);
    }
}
