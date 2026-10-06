package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.CancelRequest;
import com.evops.dto.ProjectCreateRequest;
import com.evops.dto.StageRequest;
import com.evops.entity.RepairAcceptance;
import com.evops.entity.RepairOperationAttempt;
import com.evops.entity.RepairProject;
import com.evops.entity.RepairStage;
import com.evops.service.RepairOperationService;
import com.evops.service.RepairAcceptanceService;
import com.evops.service.RepairService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 修复项目链路：立项 -> 开工 -> 阶段结果 -> 完成 -> 验收；另支持作废。
 * 开工/阶段/完成/验收/作废五类操作经并发安全入口：同一操作并发发起时，
 * 项目行锁串行化，谁生效、谁被拒绝逐条落尝试审计。
 */
@RestController
@RequestMapping("/api/repair-projects")
public class RepairController {

    private final RepairService repairService;
    private final RepairOperationService operationService;
    private final RepairAcceptanceService acceptanceService;

    public RepairController(RepairService repairService,
                            RepairOperationService operationService,
                            RepairAcceptanceService acceptanceService) {
        this.repairService = repairService;
        this.operationService = operationService;
        this.acceptanceService = acceptanceService;
    }

    /** 立项：委托人、修复方案；项目编号重复会被拒绝 */
    @PostMapping
    public ApiResponse<RepairProject> create(@Valid @RequestBody ProjectCreateRequest request) {
        return ApiResponse.ok(repairService.createProject(request));
    }

    /** 开工（并发安全：同项目并发开工只一个生效，其余拒绝并审计） */
    @PostMapping("/{id}/start")
    public ApiResponse<RepairProject> start(@PathVariable Long id,
                                            @RequestBody(required = false) Map<String, String> body) {
        String requestNo = body == null ? null : body.get("requestNo");
        String operatorName = body == null ? null : body.get("operatorName");
        return ApiResponse.ok(operationService.start(id, requestNo, operatorName));
    }

    /** 登记阶段结果（可多次，序号项目内递增；并发安全） */
    @PostMapping("/{id}/stages")
    public ApiResponse<RepairStage> recordStage(@PathVariable Long id,
                                                @Valid @RequestBody StageRequest request) {
        return ApiResponse.ok(operationService.recordStage(id, request));
    }

    /** 修复完成，进入待验收（并发安全） */
    @PostMapping("/{id}/finish")
    public ApiResponse<RepairProject> finish(@PathVariable Long id,
                                             @RequestBody(required = false) Map<String, String> body) {
        String requestNo = body == null ? null : body.get("requestNo");
        String operatorName = body == null ? null : body.get("operatorName");
        return ApiResponse.ok(operationService.finish(id, requestNo, operatorName));
    }

    // museum-14 起验收不再单步完成：修复完成后由修复师提交成果报告、
    // 馆内专家与保管员分别逐项确认，接口见 /api/repair-projects/{id}/acceptance。

    /** 作废：只改状态（并发安全） */
    @PostMapping("/{id}/cancel")
    public ApiResponse<RepairProject> cancel(@PathVariable Long id,
                                             @Valid @RequestBody CancelRequest request) {
        return ApiResponse.ok(operationService.cancel(id, request));
    }

    /** 并发尝试审计：该项目每个操作谁 EFFECTIVE、谁 REJECTED 及胜出尝试 */
    @GetMapping("/{id}/operation-attempts")
    public ApiResponse<List<RepairOperationAttempt>> operationAttempts(
            @PathVariable Long id,
            @RequestParam(required = false) String operation) {
        return ApiResponse.ok(operationService.listAttempts(id, operation));
    }

    /** 项目详情：项目 + 阶段结果 + 验收记录 + 验收流程（报告/双确认/整改/开放/补修） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        RepairProject project = repairService.requireProject(id);
        List<RepairStage> stages = repairService.listStages(id);
        RepairAcceptance acceptance = repairService.getAcceptance(id);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("project", project);
        detail.put("stages", stages);
        detail.put("acceptance", acceptance);
        // museum-14：验收清单轮次、双方逐项确认、补修单、开放标记一并可交叉核对
        detail.putAll(acceptanceService.detail(id));
        return ApiResponse.ok(detail);
    }
}
