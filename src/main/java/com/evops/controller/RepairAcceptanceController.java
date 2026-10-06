package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.AcceptanceConfirmRequest;
import com.evops.dto.AcceptanceReportRequest;
import com.evops.dto.OpenMarkRequest;
import com.evops.dto.ReworkActionRequest;
import com.evops.dto.ReworkReportRequest;
import com.evops.entity.AcceptanceChecklist;
import com.evops.entity.OpenDisplayMark;
import com.evops.entity.RepairOrder;
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
 * 修复成果验收与开放标记接口（museum-14），项目维度。
 *
 * <p>验收主链路：修复师 {@code POST /report} 提交成果报告（按材质+工艺生成清单）
 * -> 馆内专家、保管员分别 {@code POST /confirm} 逐项确认（缺一项即整改）
 * -> 双方全部合格才验收通过。验收通过后 {@code POST /open} 才能设置开放标记；
 * 发现问题 {@code POST /rework-orders} 开立补修单自动撤下开放。
 * 列表/导出见 {@code /api/repair-acceptances}，公众侧见 {@code /api/public}。</p>
 */
@RestController
@RequestMapping("/api/repair-projects/{projectId}/acceptance")
public class RepairAcceptanceController {

    private final RepairAcceptanceService acceptanceService;

    public RepairAcceptanceController(RepairAcceptanceService acceptanceService) {
        this.acceptanceService = acceptanceService;
    }

    /** 修复师提交成果报告：按文物材质与声明工艺生成本轮验收清单 */
    @PostMapping("/report")
    public ApiResponse<AcceptanceChecklist> submitReport(
            @PathVariable Long projectId,
            @Valid @RequestBody AcceptanceReportRequest request) {
        return ApiResponse.ok(acceptanceService.submitReport(projectId, request));
    }

    /** 馆内专家 / 保管员分别逐项确认；缺项或不合格即进入整改并返回补修单 */
    @PostMapping("/confirm")
    public ApiResponse<Map<String, Object>> confirm(
            @PathVariable Long projectId,
            @Valid @RequestBody AcceptanceConfirmRequest request) {
        return ApiResponse.ok(acceptanceService.confirm(projectId, request));
    }

    /** 验收详情：全部轮次清单与双确认、补修单、开放标记、验收记录 */
    @GetMapping
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long projectId) {
        return ApiResponse.ok(acceptanceService.detail(projectId));
    }

    /** 设置开放展示标记（仅验收通过后可设） */
    @PostMapping("/open")
    public ApiResponse<OpenDisplayMark> markOpen(
            @PathVariable Long projectId,
            @Valid @RequestBody OpenMarkRequest request) {
        return ApiResponse.ok(acceptanceService.markOpen(projectId, request));
    }

    /** 手动撤下开放展示标记（原开放记录保留） */
    @PostMapping("/open/revoke")
    public ApiResponse<OpenDisplayMark> markOff(
            @PathVariable Long projectId,
            @Valid @RequestBody OpenMarkRequest request) {
        return ApiResponse.ok(acceptanceService.markOff(projectId, request));
    }

    /** 开放标记历史（每次开放一段一行，撤下保留） */
    @GetMapping("/open-marks")
    public ApiResponse<List<OpenDisplayMark>> marks(@PathVariable Long projectId) {
        return ApiResponse.ok(acceptanceService.listMarks(projectId));
    }

    /** 验收通过后发现问题：开立补修单，自动撤下开放并保留原开放记录 */
    @PostMapping("/rework-orders")
    public ApiResponse<RepairOrder> reportIssue(
            @PathVariable Long projectId,
            @Valid @RequestBody ReworkReportRequest request) {
        return ApiResponse.ok(acceptanceService.reportPostPassIssue(projectId, request));
    }

    /** 项目下全部补修单（整改类 + 验收后类） */
    @GetMapping("/rework-orders")
    public ApiResponse<List<RepairOrder>> reworks(@PathVariable Long projectId) {
        return ApiResponse.ok(acceptanceService.listReworks(projectId));
    }

    /** 补修开工 */
    @PostMapping("/rework-orders/{reworkId}/start")
    public ApiResponse<RepairOrder> startRework(
            @PathVariable Long projectId,
            @PathVariable Long reworkId,
            @Valid @RequestBody ReworkActionRequest request) {
        return ApiResponse.ok(acceptanceService.startRework(reworkId, request));
    }

    /** 补修完成（验收后补修完成后项目回到待验收，须重新双确认方可再开放） */
    @PostMapping("/rework-orders/{reworkId}/finish")
    public ApiResponse<RepairOrder> finishRework(
            @PathVariable Long projectId,
            @PathVariable Long reworkId,
            @Valid @RequestBody ReworkActionRequest request) {
        return ApiResponse.ok(acceptanceService.finishRework(reworkId, request));
    }
}
