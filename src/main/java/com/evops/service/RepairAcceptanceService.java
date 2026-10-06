package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.config.MuseumAcceptanceProperties;
import com.evops.dto.AcceptanceConfirmRequest;
import com.evops.dto.AcceptanceItemResult;
import com.evops.dto.AcceptanceQuery;
import com.evops.dto.AcceptanceReportRequest;
import com.evops.dto.AcceptRequest;
import com.evops.dto.OpenMarkRequest;
import com.evops.dto.PublicGrantRequest;
import com.evops.dto.PublicGrantRevokeRequest;
import com.evops.dto.ReworkActionRequest;
import com.evops.dto.ReworkReportRequest;
import com.evops.entity.AcceptanceChecklist;
import com.evops.entity.AcceptanceChecklistItem;
import com.evops.entity.AcceptanceConfirmRecord;
import com.evops.entity.OpenDisplayMark;
import com.evops.entity.PhotoFile;
import com.evops.entity.PublicImageGrant;
import com.evops.entity.Relic;
import com.evops.entity.RelicImage;
import com.evops.entity.RepairAcceptance;
import com.evops.entity.RepairOrder;
import com.evops.entity.RepairProject;
import com.evops.mapper.AcceptanceChecklistItemMapper;
import com.evops.mapper.AcceptanceChecklistMapper;
import com.evops.mapper.AcceptanceConfirmRecordMapper;
import com.evops.mapper.OpenDisplayMarkMapper;
import com.evops.mapper.PhotoFileMapper;
import com.evops.mapper.PublicImageGrantMapper;
import com.evops.mapper.RelicImageMapper;
import com.evops.mapper.RepairOrderMapper;
import com.evops.mapper.RepairProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.evops.constant.MuseumConstants.*;

/**
 * 修复成果验收与开放标记（museum-14）。
 *
 * <p>验收主链路（项目处于 REPAIRED 待验收）：</p>
 * <ol>
 *   <li>修复师提交成果报告，声明本次修复涉及工艺；系统按<b>文物材质 + 工艺</b>
 *       生成验收清单（批次按轮次快照，整改重提产生新一轮，旧轮次永不改写）。</li>
 *   <li>馆内专家（EXPERT）与保管员（KEEPER）<b>分别逐项确认</b>：缺一项即视为
 *       不合格，任一方存在不合格条目，本轮进入整改并自动开立 RECTIFY 补修单；
 *       补修完成后方可提交新一轮报告重新双确认。</li>
 *   <li>双方对全部条目均合格的轮次才验收通过：固化验收记录、项目闭环为
 *       ACCEPTED；附条件通过（CONDITIONAL）仅首轮允许，须登记验收后保护等级。</li>
 * </ol>
 *
 * <p>开放标记与补修：开放展示标记只能在验收通过后设置；验收通过后发现问题
 * 开立 POST_PASS 补修单时自动撤下开放标记（原开放行保留），项目进入补修，
 * 补修完成回到待验收、重新双确认通过后方可再次开放（另起新标记行）。</p>
 *
 * <p>公众侧：影像授权只追加，撤销不删行；公众目录只取开放中文物且最新授权为
 * GRANTED 的有效影像与登记摘要。列表 / CSV 导出共用 {@link #queryRows} 同一口径，
 * 与详情、台账可交叉核对。</p>
 */
@Service
public class RepairAcceptanceService {

    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> CRAFTS =
            Arrays.asList(CRAFT_CLEAN, CRAFT_REPAIR, CRAFT_STABILIZE, CRAFT_PROTECT);

    private final AcceptanceChecklistMapper batchMapper;
    private final AcceptanceChecklistItemMapper itemMapper;
    private final AcceptanceConfirmRecordMapper confirmMapper;
    private final RepairOrderMapper reworkMapper;
    private final OpenDisplayMarkMapper markMapper;
    private final PublicImageGrantMapper grantMapper;
    private final RelicImageMapper imageMapper;
    private final PhotoFileMapper fileMapper;
    private final RepairProjectMapper projectMapper;
    private final RepairService repairService;
    private final RelicService relicService;
    private final LedgerService ledgerService;
    private final MuseumAcceptanceProperties properties;

    public RepairAcceptanceService(AcceptanceChecklistMapper batchMapper,
                                   AcceptanceChecklistItemMapper itemMapper,
                                   AcceptanceConfirmRecordMapper confirmMapper,
                                   RepairOrderMapper reworkMapper,
                                   OpenDisplayMarkMapper markMapper,
                                   PublicImageGrantMapper grantMapper,
                                   RelicImageMapper imageMapper,
                                   PhotoFileMapper fileMapper,
                                   RepairProjectMapper projectMapper,
                                   RepairService repairService,
                                   RelicService relicService,
                                   LedgerService ledgerService,
                                   MuseumAcceptanceProperties properties) {
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.confirmMapper = confirmMapper;
        this.reworkMapper = reworkMapper;
        this.markMapper = markMapper;
        this.grantMapper = grantMapper;
        this.imageMapper = imageMapper;
        this.fileMapper = fileMapper;
        this.projectMapper = projectMapper;
        this.repairService = repairService;
        this.relicService = relicService;
        this.ledgerService = ledgerService;
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // 成果报告与验收清单
    // ------------------------------------------------------------------

    /**
     * 修复师提交成果报告：按材质 + 工艺生成本轮验收清单。
     * 首轮从轮次 1 开始；上一轮进入整改的，须其补修单完成后才能提交新一轮。
     */
    @Transactional
    public AcceptanceChecklist submitReport(Long projectId, AcceptanceReportRequest req) {
        projectMapper.lockRow(projectId);
        RepairProject project = repairService.requireProject(projectId);
        if (!PROJECT_REPAIRED.equals(project.getStatus())) {
            throw new IllegalArgumentException("项目未处于待验收状态，不能提交成果报告: "
                    + project.getProjectNo() + "（当前 " + project.getStatus() + "）");
        }
        AcceptanceChecklist latest = latestBatch(projectId);
        if (latest != null) {
            if (ACC_BATCH_REPORTED.equals(latest.getStatus())) {
                throw new IllegalArgumentException("第" + latest.getRoundNo()
                        + "轮成果报告已提交，待馆内专家与保管员确认，不能重复提交");
            }
            if (ACC_BATCH_RECTIFYING.equals(latest.getStatus())) {
                // RECTIFYING：必须先完成整改补修单
                RepairOrder rework = latest.getReworkOrderId() == null ? null
                        : reworkMapper.selectById(latest.getReworkOrderId());
                if (rework == null || !REWORK_DONE.equals(rework.getStatus())) {
                    throw new IllegalArgumentException("第" + latest.getRoundNo()
                            + "轮验收整改补修尚未完成，不能重新提交成果报告");
                }
            }
            // latest == PASSED：仅当项目已被验收后补修回到 REPAIRED（入口已校验状态）
            // 时才允许另起新一轮重新双确认；正常 ACCEPTED 项目到不了这里。
        }

        List<String> crafts = normalizeCrafts(req.getCrafts());
        Relic relic = relicService.requireRelic(project.getRelicId());
        int roundNo = (latest == null ? 0 : latest.getRoundNo()) + 1;
        LocalDateTime now = LocalDateTime.now();

        AcceptanceChecklist batch = new AcceptanceChecklist();
        batch.setProjectId(projectId);
        batch.setRoundNo(roundNo);
        batch.setMaterialSnapshot(relic.getMaterial());
        batch.setCrafts(String.join(",", crafts));
        batch.setReportSummary(req.getReportSummary().trim());
        batch.setReportDetail(req.getReportDetail());
        batch.setReportedBy(req.getReportedBy().trim());
        batch.setReportedAt(now);
        batch.setStatus(ACC_BATCH_REPORTED);
        batchMapper.insert(batch);

        int itemNo = 0;
        for (String craft : crafts) {
            for (String content : properties.itemsFor(relic.getMaterial(), craft)) {
                itemNo++;
                AcceptanceChecklistItem item = new AcceptanceChecklistItem();
                item.setBatchId(batch.getId());
                item.setProjectId(projectId);
                item.setRoundNo(roundNo);
                item.setCraft(craft);
                item.setItemNo(itemNo);
                item.setItemContent(content);
                itemMapper.insert(item);
            }
        }

        ledgerService.append(relic.getId(), projectId, null,
                ACTION_ACCEPT_CHECKLIST, project.getProjectNo(),
                "第" + roundNo + "轮验收清单已按材质【" + relic.getMaterial()
                        + "】与工艺【" + batch.getCrafts() + "】生成，共 " + itemNo + " 项", now);
        ledgerService.append(relic.getId(), projectId, null,
                ACTION_ACCEPT_REPORT, project.getProjectNo(),
                "修复师" + batch.getReportedBy() + "提交第" + roundNo
                        + "轮修复成果报告：" + batch.getReportSummary(), now);
        return batch;
    }

    /**
     * 馆内专家 / 保管员分别逐项确认。
     * 缺项（未出现在提交列表）与结论 FAIL 均按不合格处理；一方有任一不合格，
     * 本轮即进入整改并开立 RECTIFY 补修单。双方全部合格才验收通过。
     */
    @Transactional
    public Map<String, Object> confirm(Long projectId, AcceptanceConfirmRequest req) {
        projectMapper.lockRow(projectId);
        RepairProject project = repairService.requireProject(projectId);
        if (!PROJECT_REPAIRED.equals(project.getStatus())) {
            throw new IllegalArgumentException("项目未处于待验收状态，不能提交验收确认: "
                    + project.getProjectNo());
        }
        AcceptanceChecklist batch = requireLatestBatch(projectId);
        if (!ACC_BATCH_REPORTED.equals(batch.getStatus())) {
            throw new IllegalArgumentException("第" + batch.getRoundNo() + "轮验收不在待确认状态（"
                    + batch.getStatus() + "），不能重复确认");
        }
        String role = normalizeRole(req.getConfirmerRole());
        String name = req.getConfirmerName().trim();

        List<AcceptanceChecklistItem> items = listItems(batch.getId());
        Long existing = confirmMapper.selectCount(new LambdaQueryWrapper<AcceptanceConfirmRecord>()
                .eq(AcceptanceConfirmRecord::getBatchId, batch.getId())
                .eq(AcceptanceConfirmRecord::getConfirmerRole, role)
                .gt(AcceptanceConfirmRecord::getId, 0));
        // selectCount 返回行数；>0 表示该方已确认过
        if (existing != null && existing > 0) {
            throw new IllegalArgumentException(roleLabel(role) + "已完成第" + batch.getRoundNo()
                    + "轮确认，不能重复确认");
        }

        Map<Long, AcceptanceItemResult> byItem = new HashMap<>();
        if (req.getItems() != null) {
            for (AcceptanceItemResult r : req.getItems()) {
                if (r.getItemId() == null) {
                    throw new IllegalArgumentException("确认条目缺少 itemId");
                }
                if (byItem.put(r.getItemId(), r) != null) {
                    throw new IllegalArgumentException("同一条目重复确认: itemId=" + r.getItemId());
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        int passCount = 0;
        List<String> failures = new ArrayList<>();
        for (AcceptanceChecklistItem item : items) {
            AcceptanceItemResult r = byItem.get(item.getId());
            String result;
            String opinion;
            if (r == null) {
                result = ITEM_FAIL;
                opinion = "未确认（缺项）";
            } else {
                result = ITEM_PASS.equals(r.getResult()) ? ITEM_PASS : ITEM_FAIL;
                opinion = r.getOpinion();
                if (ITEM_FAIL.equals(result) && (opinion == null || opinion.trim().isEmpty())) {
                    opinion = "验收不合格";
                }
            }
            AcceptanceConfirmRecord record = new AcceptanceConfirmRecord();
            record.setBatchId(batch.getId());
            record.setItemId(item.getId());
            record.setProjectId(projectId);
            record.setRoundNo(batch.getRoundNo());
            record.setConfirmerRole(role);
            record.setConfirmerName(name);
            record.setResult(result);
            record.setOpinion(opinion);
            record.setConfirmedAt(now);
            confirmMapper.insert(record);
            if (ITEM_PASS.equals(result)) {
                passCount++;
            } else {
                failures.add("第" + item.getItemNo() + "项「" + item.getItemContent() + "」："
                        + opinion);
            }
        }

        boolean roleAllPass = passCount == items.size();
        // 馆内专家全部合格时提出验收结论（先于保管员确认也先登记在批次上）
        if (ACC_ROLE_EXPERT.equals(role) && roleAllPass) {
            String proposed = req.getResult() == null || req.getResult().isEmpty()
                    ? ACCEPT_PASS : req.getResult();
            if (!ACCEPT_PASS.equals(proposed) && !ACCEPT_CONDITIONAL.equals(proposed)) {
                throw new IllegalArgumentException("验收结论只能为 PASS/CONDITIONAL");
            }
            String gradeAfter = req.getGradeAfter() == null ? null : req.getGradeAfter().trim();
            if (ACCEPT_CONDITIONAL.equals(proposed)
                    && (gradeAfter == null || !Arrays.asList(GRADE_ONE, GRADE_TWO, GRADE_THREE)
                    .contains(gradeAfter))) {
                throw new IllegalArgumentException(
                        "验收附条件通过（部分成功）必须登记验收后保护等级 ONE/TWO/THREE");
            }
            // 补修重新验收只允许通过：首轮 CONDITIONAL 已开立过项目级等级补偿
            if (ACCEPT_CONDITIONAL.equals(proposed) && hasPriorAcceptance(projectId)) {
                throw new IllegalArgumentException(
                        "附条件通过仅适用于首次验收，补修后重新验收结论只能为 PASS");
            }
            batch.setProposedResult(proposed);
            batch.setProposedGradeAfter(ACCEPT_CONDITIONAL.equals(proposed) ? gradeAfter : null);
            batchMapper.updateById(batch);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("batchId", batch.getId());
        response.put("roundNo", batch.getRoundNo());
        response.put("confirmerRole", role);
        response.put("totalItems", items.size());
        response.put("passCount", passCount);
        response.put("failCount", failures.size());

        if (!roleAllPass) {
            // 缺一项就进入整改：批次置整改中，自动开立 RECTIFY 补修单
            return enterRectification(project, batch, role, name, failures, now, response);
        }

        ledgerService.append(project.getRelicId(), projectId, null,
                ACTION_ACCEPT_CONFIRM, project.getProjectNo(),
                roleLabel(role) + name + "完成第" + batch.getRoundNo() + "轮逐项确认，全部合格",
                now);

        // 双方是否都已全部合格
        if (bothRolesAllPass(batch.getId(), items.size())) {
            finalizePassedRound(project, batch, name, now);
            response.put("passed", true);
            response.put("status", ACC_BATCH_PASSED);
        } else {
            response.put("passed", false);
            response.put("status", ACC_BATCH_REPORTED);
            response.put("waitingFor", ACC_ROLE_EXPERT.equals(role) ? "KEEPER" : "EXPERT");
        }
        return response;
    }

    /** 进入整改：批次 RECTIFYING + RECTIFY 补修单 + 台账 */
    private Map<String, Object> enterRectification(RepairProject project, AcceptanceChecklist batch,
                                                   String role, String name, List<String> failures,
                                                   LocalDateTime now,
                                                   Map<String, Object> response) {
        String reason = "第" + batch.getRoundNo() + "轮验收" + roleLabel(role) + name
                + "确认存在 " + failures.size() + " 项不合格（含缺项）："
                + String.join("；", truncate(failures, 5));
        RepairOrder rework = openReworkOrder(project, REWORK_RECTIFY, batch.getRoundNo(),
                batch.getId(), reason, name, now);
        batch.setStatus(ACC_BATCH_RECTIFYING);
        batch.setReworkOrderId(rework.getId());
        batchMapper.updateById(batch);

        ledgerService.append(project.getRelicId(), project.getId(), null,
                ACTION_ACCEPT_CONFIRM, project.getProjectNo(),
                roleLabel(role) + name + "第" + batch.getRoundNo() + "轮逐项确认：合格 "
                        + response.get("passCount") + " 项、不合格 " + failures.size() + " 项",
                now);
        ledgerService.append(project.getRelicId(), project.getId(), null,
                ACTION_ACCEPT_RECTIFY, project.getProjectNo(), reason, now);

        response.put("passed", false);
        response.put("status", ACC_BATCH_RECTIFYING);
        response.put("reworkOrder", rework);
        response.put("failures", failures);
        return response;
    }

    /** 双方全部条目均合格：固化验收记录、项目闭环 */
    private void finalizePassedRound(RepairProject project, AcceptanceChecklist batch,
                                     String currentConfirmer, LocalDateTime now) {
        String result = batch.getProposedResult() == null ? ACCEPT_PASS : batch.getProposedResult();
        AcceptRequest acceptReq = new AcceptRequest();
        acceptReq.setAcceptedBy("馆内专家组");
        acceptReq.setResult(result);
        acceptReq.setGradeAfter(batch.getProposedGradeAfter());
        acceptReq.setOpinion("第" + batch.getRoundNo() + "轮验收馆内专家与保管员逐项确认全部合格"
                + (ACCEPT_CONDITIONAL.equals(result)
                ? "，附条件通过，验收后保护等级 " + batch.getProposedGradeAfter() : ""));
        RepairAcceptance acceptance =
                repairService.finalizeAcceptance(project.getId(), acceptReq, batch.getRoundNo());

        batch.setStatus(ACC_BATCH_PASSED);
        batch.setPassedAt(now);
        batch.setFinalizedAcceptanceId(acceptance.getId());
        batchMapper.updateById(batch);
    }

    // ------------------------------------------------------------------
    // 补修单
    // ------------------------------------------------------------------

    /** 验收通过后发现问题：开立 POST_PASS 补修单，自动撤下开放标记，项目转补修中 */
    @Transactional
    public RepairOrder reportPostPassIssue(Long projectId, ReworkReportRequest req) {
        projectMapper.lockRow(projectId);
        RepairProject project = repairService.requireProject(projectId);
        if (!PROJECT_ACCEPTED.equals(project.getStatus())) {
            throw new IllegalArgumentException("项目尚未验收通过，不能按「验收后发现问题」开立补修单: "
                    + project.getProjectNo() + "（当前 " + project.getStatus() + "）");
        }
        LocalDateTime now = LocalDateTime.now();
        RepairOrder rework = openReworkOrder(project, REWORK_POST_PASS, null, null,
                req.getReason().trim(), req.getReportedBy().trim(), now);

        project.setStatus(PROJECT_REWORKING);
        projectMapper.updateById(project);

        // 自动撤下开放中标记：原行保留，记录撤下人/时间/原因/补修单
        turnOffOpenMark(project, "系统（开立补修单 " + rework.getReworkNo() + "）",
                "验收通过后发现问题开立补修单，自动撤下开放展示：" + req.getReason().trim(),
                rework.getId(), now);

        ledgerService.append(project.getRelicId(), projectId, null,
                ACTION_REWORK_OPEN, rework.getReworkNo(),
                "验收通过后发现问题开立补修单，报告人：" + req.getReportedBy().trim()
                        + "，问题：" + req.getReason().trim(), now);
        return rework;
    }

    /** 补修开工：OPEN -> DOING */
    @Transactional
    public RepairOrder startRework(Long reworkId, ReworkActionRequest req) {
        RepairOrder rework = requireRework(reworkId);
        projectMapper.lockRow(rework.getProjectId());
        if (!REWORK_OPEN.equals(rework.getStatus())) {
            throw new IllegalArgumentException("补修单不是待补修状态，不能开工: "
                    + rework.getReworkNo() + "（当前 " + rework.getStatus() + "）");
        }
        LocalDateTime now = LocalDateTime.now();
        rework.setStatus(REWORK_DOING);
        rework.setStartedBy(req.getOperator().trim());
        rework.setStartedAt(now);
        reworkMapper.updateById(rework);
        ledgerService.append(rework.getRelicId(), rework.getProjectId(), null,
                ACTION_REWORK_DOING, rework.getReworkNo(),
                "补修开工，操作人：" + req.getOperator().trim(), now);
        return rework;
    }

    /** 补修完成：DOING -> DONE；验收后补修完成的项目回到待验收，须重新双确认 */
    @Transactional
    public RepairOrder finishRework(Long reworkId, ReworkActionRequest req) {
        RepairOrder rework = requireRework(reworkId);
        projectMapper.lockRow(rework.getProjectId());
        if (!REWORK_DOING.equals(rework.getStatus())) {
            throw new IllegalArgumentException("补修单不在补修中，不能完成: " + rework.getReworkNo()
                    + "（当前 " + rework.getStatus() + "）");
        }
        if (req.getNote() == null || req.getNote().trim().isEmpty()) {
            throw new IllegalArgumentException("完成补修必须填写补修结果说明");
        }
        LocalDateTime now = LocalDateTime.now();
        rework.setStatus(REWORK_DONE);
        rework.setFinishedBy(req.getOperator().trim());
        rework.setFinishedAt(now);
        rework.setResultNote(req.getNote().trim());
        // updateById 不更新 null，稀疏唯一键须显式置空，释放 POST_PASS 在途占用
        markPostOpenKeyNull(rework.getId());
        rework.setPostOpenKey(null);
        reworkMapper.updateById(rework);

        String summary = "补修完成，操作人：" + req.getOperator().trim()
                + "，结果：" + req.getNote().trim();
        if (REWORK_POST_PASS.equals(rework.getSource())) {
            RepairProject project = repairService.requireProject(rework.getProjectId());
            if (PROJECT_REWORKING.equals(project.getStatus())) {
                project.setStatus(PROJECT_REPAIRED);
                projectMapper.updateById(project);
            }
            summary += "；项目回到待验收，须重新提交成果报告并经双确认通过后方可再次开放";
        } else {
            summary += "；修复师可重新提交新一轮成果报告";
        }
        ledgerService.append(rework.getRelicId(), rework.getProjectId(), null,
                ACTION_REWORK_DONE, rework.getReworkNo(), summary, now);
        return rework;
    }

    private RepairOrder openReworkOrder(RepairProject project, String source, Integer roundNo,
                                        Long batchId, String reason, String reportedBy,
                                        LocalDateTime now) {
        RepairOrder rework = new RepairOrder();
        rework.setReworkNo(String.format("RW-%06d", reworkMapper.nextReworkSeq()));
        rework.setProjectId(project.getId());
        rework.setRelicId(project.getRelicId());
        rework.setSource(source);
        rework.setRoundNo(roundNo);
        rework.setTriggerBatchId(batchId);
        rework.setReason(reason);
        rework.setReportedBy(reportedBy);
        rework.setStatus(REWORK_OPEN);
        rework.setPostOpenKey(REWORK_POST_PASS.equals(source) ? project.getId() : null);
        reworkMapper.insert(rework);
        return rework;
    }

    // ------------------------------------------------------------------
    // 开放展示标记
    // ------------------------------------------------------------------

    /** 设置开放展示标记：仅验收通过（ACCEPTED）且当前无开放中标记时可设 */
    @Transactional
    public OpenDisplayMark markOpen(Long projectId, OpenMarkRequest req) {
        projectMapper.lockRow(projectId);
        RepairProject project = repairService.requireProject(projectId);
        if (!PROJECT_ACCEPTED.equals(project.getStatus())) {
            throw new IllegalArgumentException("只有验收通过的项目才能设置开放展示标记: "
                    + project.getProjectNo() + "（当前 " + project.getStatus() + "）");
        }
        OpenDisplayMark on = findOnMark(projectId);
        if (on != null) {
            throw new IllegalArgumentException("项目已处于开放展示中，不能重复开放: "
                    + project.getProjectNo());
        }
        LocalDateTime now = LocalDateTime.now();
        OpenDisplayMark mark = new OpenDisplayMark();
        mark.setProjectId(projectId);
        mark.setRelicId(project.getRelicId());
        mark.setStatus(OPEN_MARK_ON);
        mark.setOpenKey(projectId);
        mark.setMarkedBy(req.getOperator().trim());
        mark.setMarkedAt(now);
        markMapper.insert(mark);
        ledgerService.append(project.getRelicId(), projectId, null,
                ACTION_OPEN_MARK_ON, project.getProjectNo(),
                "设置开放展示标记，操作人：" + req.getOperator().trim(), now);
        return mark;
    }

    /** 手动撤下开放标记：行保留，仅置 OFF 并清空开放键 */
    @Transactional
    public OpenDisplayMark markOff(Long projectId, OpenMarkRequest req) {
        projectMapper.lockRow(projectId);
        RepairProject project = repairService.requireProject(projectId);
        return turnOffOpenMark(project, req.getOperator().trim(),
                req.getReason() == null || req.getReason().trim().isEmpty()
                        ? "手动撤下开放展示" : req.getReason().trim(),
                null, LocalDateTime.now());
    }

    /** 撤下开放中标记（补修单自动撤下 / 手动撤下共用）；无开放中标记时不产生新行 */
    private OpenDisplayMark turnOffOpenMark(RepairProject project, String operator, String reason,
                                            Long reworkOrderId, LocalDateTime now) {
        OpenDisplayMark on = findOnMark(project.getId());
        if (on == null) {
            return null;
        }
        on.setStatus(OPEN_MARK_OFF);
        on.setOpenKey(null);
        on.setTurnedOffBy(operator);
        on.setTurnedOffAt(now);
        on.setTurnOffReason(reason);
        on.setReworkOrderId(reworkOrderId);
        // updateById 不更新 null，开放稀疏键须显式置空，撤下后才允许再次开放
        markMapper.update(null, new LambdaUpdateWrapper<OpenDisplayMark>()
                .eq(OpenDisplayMark::getId, on.getId())
                .set(OpenDisplayMark::getStatus, OPEN_MARK_OFF)
                .set(OpenDisplayMark::getOpenKey, null)
                .set(OpenDisplayMark::getTurnedOffBy, operator)
                .set(OpenDisplayMark::getTurnedOffAt, now)
                .set(OpenDisplayMark::getTurnOffReason, reason)
                .set(OpenDisplayMark::getReworkOrderId, reworkOrderId));
        ledgerService.append(project.getRelicId(), project.getId(), null,
                ACTION_OPEN_MARK_OFF, project.getProjectNo(),
                "撤下开放展示标记，操作人：" + operator + "，原因：" + reason
                        + (reworkOrderId == null ? "" : "，关联补修单 id=" + reworkOrderId)
                        + "；原开放记录（开放人：" + on.getMarkedBy()
                        + "，开放时间：" + on.getMarkedAt().format(CSV_TIME) + "）保留", now);
        return on;
    }

    public List<OpenDisplayMark> listMarks(Long projectId) {
        return markMapper.selectList(new LambdaQueryWrapper<OpenDisplayMark>()
                .eq(OpenDisplayMark::getProjectId, projectId)
                .orderByAsc(OpenDisplayMark::getId));
    }

    // ------------------------------------------------------------------
    // 公众影像授权
    // ------------------------------------------------------------------

    /** 授权一张影像对公众展示：影像须有效且属于该文物；授权只追加 */
    @Transactional
    public PublicImageGrant grantImage(Long relicId, PublicGrantRequest req) {
        Relic relic = relicService.requireRelic(relicId);
        RelicImage image = imageMapper.selectById(req.getImageId());
        if (image == null) {
            throw new IllegalArgumentException("影像不存在: id=" + req.getImageId());
        }
        if (!relicId.equals(image.getRelicId())) {
            throw new IllegalArgumentException("影像不属于该文物，不能授权");
        }
        if (!IMAGE_ACTIVE.equals(image.getStatus())) {
            throw new IllegalArgumentException("影像已作废，不能授权对公众展示");
        }
        LocalDateTime now = LocalDateTime.now();
        PublicImageGrant grant = new PublicImageGrant();
        grant.setRelicId(relicId);
        grant.setImageId(image.getId());
        grant.setProjectId(image.getProjectId());
        grant.setPublicSummary(req.getPublicSummary().trim());
        grant.setStatus(GRANT_ACTIVE);
        grant.setGrantedBy(req.getGrantedBy().trim());
        grant.setGrantedAt(now);
        grantMapper.insert(grant);
        ledgerService.append(relicId, image.getProjectId(), null,
                ACTION_PUBLIC_GRANT, "IMAGE#" + image.getId(),
                "影像授权对公众展示，授权人：" + req.getGrantedBy().trim()
                        + "，公众摘要：" + req.getPublicSummary().trim(), now);
        return grant;
    }

    /** 撤销授权：只置 REVOKED，授权行与影像留档保留 */
    @Transactional
    public PublicImageGrant revokeGrant(Long grantId, PublicGrantRevokeRequest req) {
        PublicImageGrant grant = grantMapper.selectById(grantId);
        if (grant == null) {
            throw new IllegalArgumentException("授权记录不存在: id=" + grantId);
        }
        if (GRANT_REVOKED.equals(grant.getStatus())) {
            throw new IllegalArgumentException("授权已撤销，不能重复撤销");
        }
        LocalDateTime now = LocalDateTime.now();
        grant.setStatus(GRANT_REVOKED);
        grant.setRevokedBy(req.getRevokedBy().trim());
        grant.setRevokedAt(now);
        grant.setRevokeReason(req.getReason().trim());
        grantMapper.updateById(grant);
        ledgerService.append(grant.getRelicId(), grant.getProjectId(), null,
                ACTION_PUBLIC_GRANT_REVOKE, "IMAGE#" + grant.getImageId(),
                "撤销影像公众展示授权，撤销人：" + req.getRevokedBy().trim()
                        + "，原因：" + req.getReason().trim() + "（授权记录保留）", now);
        return grant;
    }

    public List<PublicImageGrant> listGrants(Long relicId) {
        relicService.requireRelic(relicId);
        return grantMapper.selectList(new LambdaQueryWrapper<PublicImageGrant>()
                .eq(PublicImageGrant::getRelicId, relicId)
                .orderByAsc(PublicImageGrant::getId));
    }

    /**
     * 公众目录：仅当前开放展示中（开放标记 ON）的文物，且只收录最新授权为 GRANTED
     * 的有效影像与授权时登记的摘要；内部修复报告、未授权/撤销授权影像一律不出现。
     */
    public List<Map<String, Object>> publicCatalog() {
        List<OpenDisplayMark> ons = markMapper.selectList(
                new LambdaQueryWrapper<OpenDisplayMark>()
                        .eq(OpenDisplayMark::getStatus, OPEN_MARK_ON)
                        .orderByAsc(OpenDisplayMark::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (OpenDisplayMark mark : ons) {
            Relic relic = relicService.requireRelic(mark.getRelicId());
            if (!ACTIVE.equals(relic.getStatus())) {
                continue;
            }
            List<Map<String, Object>> imageViews = new ArrayList<>();
            List<RelicImage> images = imageMapper.selectList(new LambdaQueryWrapper<RelicImage>()
                    .eq(RelicImage::getRelicId, relic.getId())
                    .eq(RelicImage::getStatus, IMAGE_ACTIVE)
                    .orderByAsc(RelicImage::getId));
            for (RelicImage image : images) {
                PublicImageGrant latest = latestGrant(image.getId());
                if (latest == null || !GRANT_ACTIVE.equals(latest.getStatus())) {
                    continue; // 未授权或授权已撤销：公众不可见
                }
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("imageId", image.getId());
                view.put("viewType", image.getViewType());
                view.put("viewLabel", viewLabel(image.getViewType()));
                view.put("shotAt", image.getShotAt());
                view.put("publicSummary", latest.getPublicSummary());
                PhotoFile file = fileMapper.selectById(image.getFileId());
                if (file != null) {
                    view.put("fileName", file.getFileName());
                }
                imageViews.add(view);
            }
            if (imageViews.isEmpty()) {
                continue; // 无已授权影像的开放文物不出现在公众目录
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("relicId", relic.getId());
            row.put("relicNo", relic.getRelicNo());
            row.put("name", relic.getName());
            row.put("category", relic.getCategory());
            row.put("era", relic.getEra());
            row.put("openMarkedAt", mark.getMarkedAt());
            row.put("images", imageViews);
            result.add(row);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 详情 / 列表 / 导出（统一口径，可交叉核对）
    // ------------------------------------------------------------------

    /** 验收详情：项目 + 全部轮次（清单与双确认）+ 补修单 + 开放标记 + 验收记录 */
    public Map<String, Object> detail(Long projectId) {
        RepairProject project = repairService.requireProject(projectId);
        List<AcceptanceChecklist> batches = batchMapper.selectList(
                new LambdaQueryWrapper<AcceptanceChecklist>()
                        .eq(AcceptanceChecklist::getProjectId, projectId)
                        .orderByAsc(AcceptanceChecklist::getId));
        List<Map<String, Object>> batchViews = new ArrayList<>();
        for (AcceptanceChecklist batch : batches) {
            batchViews.add(batchView(batch));
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("project", project);
        detail.put("batches", batchViews);
        detail.put("reworkOrders", listReworks(projectId));
        detail.put("openMarks", listMarks(projectId));
        detail.put("acceptances", repairService.listAcceptances(projectId));
        return detail;
    }

    /**
     * 验收列表行：页面列表与 CSV 导出共用本查询，口径一致。
     * 一行对应一个验收批次（轮次），携带项目/文物、双确认齐备情况、
     * 整改补修、开放标记与最新验收结论，便于与详情、台账交叉核对。
     */
    public List<Map<String, Object>> queryRows(AcceptanceQuery query) {
        List<AcceptanceChecklist> batches = batchMapper.selectList(
                new LambdaQueryWrapper<AcceptanceChecklist>()
                        .eq(query.getProjectId() != null,
                                AcceptanceChecklist::getProjectId, query.getProjectId())
                        .eq(query.getStatus() != null && !query.getStatus().isEmpty(),
                                AcceptanceChecklist::getStatus, query.getStatus())
                        .orderByDesc(AcceptanceChecklist::getProjectId)
                        .orderByAsc(AcceptanceChecklist::getRoundNo));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (AcceptanceChecklist batch : batches) {
            RepairProject project = projectMapper.selectById(batch.getProjectId());
            if (project == null) {
                continue;
            }
            Relic relic = relicService.requireRelic(project.getRelicId());
            if (query.getRelicId() != null && !query.getRelicId().equals(relic.getId())) {
                continue;
            }
            boolean openNow = findOnMark(project.getId()) != null;
            if (Boolean.TRUE.equals(query.getOpenOnly()) && !openNow) {
                continue;
            }
            RepairOrder activeRework = activeRework(project.getId());
            if (Boolean.TRUE.equals(query.getReworkOnly()) && activeRework == null) {
                continue;
            }
            rows.add(buildRow(batch, project, relic, openNow, activeRework));
        }
        return rows;
    }

    /** CSV 导出：与列表同一行集、同一顺序 */
    public String exportCsv(AcceptanceQuery query) {
        List<Map<String, Object>> rows = queryRows(query);
        StringBuilder csv = new StringBuilder("﻿");
        csv.append("项目编号,文物编号,文物名称,材质,验收轮次,涉及工艺,批次状态,报告人,报告时间,"
                + "清单项数,专家合格项,保管员合格项,专家已齐,保管员已齐,在途补修单,补修单号,"
                + "开放展示,最新验收结论,验收通过时间\n");
        for (Map<String, Object> r : rows) {
            csv.append(cell(str(r.get("projectNo")))).append(',')
                    .append(cell(str(r.get("relicNo")))).append(',')
                    .append(cell(str(r.get("relicName")))).append(',')
                    .append(cell(str(r.get("material")))).append(',')
                    .append(r.get("roundNo")).append(',')
                    .append(cell(str(r.get("crafts")))).append(',')
                    .append(cell(str(r.get("status")))).append(',')
                    .append(cell(str(r.get("reportedBy")))).append(',')
                    .append(cell(time(r.get("reportedAt")))).append(',')
                    .append(r.get("totalItems")).append(',')
                    .append(r.get("expertPassCount")).append(',')
                    .append(r.get("keeperPassCount")).append(',')
                    .append(cell(Boolean.TRUE.equals(r.get("expertComplete")) ? "是" : "否")).append(',')
                    .append(cell(Boolean.TRUE.equals(r.get("keeperComplete")) ? "是" : "否")).append(',')
                    .append(cell(Boolean.TRUE.equals(r.get("hasActiveRework")) ? "是" : "否")).append(',')
                    .append(cell(str(r.get("activeReworkNo")))).append(',')
                    .append(cell(Boolean.TRUE.equals(r.get("openDisplay")) ? "开放中" : "未开放")).append(',')
                    .append(cell(str(r.get("latestAcceptResult")))).append(',')
                    .append(cell(time(r.get("passedAt")))).append('\n');
        }
        return csv.toString();
    }

    private Map<String, Object> buildRow(AcceptanceChecklist batch, RepairProject project,
                                         Relic relic, boolean openNow, RepairOrder activeRework) {
        List<AcceptanceChecklistItem> items = listItems(batch.getId());
        int expertPass = countPass(batch.getId(), ACC_ROLE_EXPERT);
        int keeperPass = countPass(batch.getId(), ACC_ROLE_KEEPER);
        RepairAcceptance latest = repairService.getAcceptance(project.getId());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("batchId", batch.getId());
        row.put("projectId", project.getId());
        row.put("projectNo", project.getProjectNo());
        row.put("projectStatus", project.getStatus());
        row.put("relicId", relic.getId());
        row.put("relicNo", relic.getRelicNo());
        row.put("relicName", relic.getName());
        row.put("material", batch.getMaterialSnapshot());
        row.put("roundNo", batch.getRoundNo());
        row.put("crafts", batch.getCrafts());
        row.put("status", batch.getStatus());
        row.put("reportSummary", batch.getReportSummary());
        row.put("reportedBy", batch.getReportedBy());
        row.put("reportedAt", batch.getReportedAt());
        row.put("passedAt", batch.getPassedAt());
        row.put("totalItems", items.size());
        row.put("expertPassCount", expertPass);
        row.put("keeperPassCount", keeperPass);
        row.put("expertComplete", ACC_BATCH_PASSED.equals(batch.getStatus())
                || expertPass == items.size() && items.size() > 0);
        row.put("keeperComplete", ACC_BATCH_PASSED.equals(batch.getStatus())
                || keeperPass == items.size() && items.size() > 0);
        row.put("hasActiveRework", activeRework != null);
        row.put("activeReworkNo", activeRework == null ? null : activeRework.getReworkNo());
        row.put("openDisplay", openNow);
        row.put("latestAcceptResult", latest == null ? null : latest.getResult());
        return row;
    }

    private Map<String, Object> batchView(AcceptanceChecklist batch) {
        List<AcceptanceChecklistItem> items = listItems(batch.getId());
        List<AcceptanceConfirmRecord> confirms = confirmMapper.selectList(
                new LambdaQueryWrapper<AcceptanceConfirmRecord>()
                        .eq(AcceptanceConfirmRecord::getBatchId, batch.getId())
                        .orderByAsc(AcceptanceConfirmRecord::getConfirmerRole)
                        .orderByAsc(AcceptanceConfirmRecord::getItemId));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("batch", batch);
        view.put("items", items);
        view.put("confirms", confirms);
        view.put("totalItems", items.size());
        view.put("expertPassCount", countPass(batch.getId(), ACC_ROLE_EXPERT));
        view.put("keeperPassCount", countPass(batch.getId(), ACC_ROLE_KEEPER));
        view.put("expertComplete",
                countPass(batch.getId(), ACC_ROLE_EXPERT) == items.size() && !items.isEmpty());
        view.put("keeperComplete",
                countPass(batch.getId(), ACC_ROLE_KEEPER) == items.size() && !items.isEmpty());
        if (batch.getReworkOrderId() != null) {
            view.put("reworkOrder", reworkMapper.selectById(batch.getReworkOrderId()));
        }
        return view;
    }

    public List<RepairOrder> listReworks(Long projectId) {
        return reworkMapper.selectList(new LambdaQueryWrapper<RepairOrder>()
                .eq(RepairOrder::getProjectId, projectId)
                .orderByAsc(RepairOrder::getId));
    }

    public RepairOrder requireRework(Long reworkId) {
        RepairOrder rework = reworkMapper.selectById(reworkId);
        if (rework == null) {
            throw new IllegalArgumentException("补修单不存在: id=" + reworkId);
        }
        return rework;
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private List<String> normalizeCrafts(List<String> input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("成果报告至少声明一项修复工艺");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String c : input) {
            if (c == null || c.trim().isEmpty()) {
                continue;
            }
            String code = c.trim().toUpperCase();
            if (!CRAFTS.contains(code)) {
                throw new IllegalArgumentException("工艺码非法: " + c
                        + "，只能为 CLEAN/REPAIR/STABILIZE/PROTECT（清洗/补配/加固/封护）");
            }
            seen.add(code);
        }
        if (seen.isEmpty()) {
            throw new IllegalArgumentException("成果报告至少声明一项修复工艺");
        }
        return new ArrayList<>(seen);
    }

    private AcceptanceChecklist latestBatch(Long projectId) {
        return batchMapper.selectList(new LambdaQueryWrapper<AcceptanceChecklist>()
                        .eq(AcceptanceChecklist::getProjectId, projectId)
                        .orderByDesc(AcceptanceChecklist::getRoundNo)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private AcceptanceChecklist requireLatestBatch(Long projectId) {
        AcceptanceChecklist batch = latestBatch(projectId);
        if (batch == null) {
            throw new IllegalArgumentException("修复师尚未提交成果报告，不能进行验收确认");
        }
        return batch;
    }

    private List<AcceptanceChecklistItem> listItems(Long batchId) {
        return itemMapper.selectList(new LambdaQueryWrapper<AcceptanceChecklistItem>()
                .eq(AcceptanceChecklistItem::getBatchId, batchId)
                .orderByAsc(AcceptanceChecklistItem::getItemNo));
    }

    private int countPass(Long batchId, String role) {
        Long c = confirmMapper.selectCount(new LambdaQueryWrapper<AcceptanceConfirmRecord>()
                .eq(AcceptanceConfirmRecord::getBatchId, batchId)
                .eq(AcceptanceConfirmRecord::getConfirmerRole, role)
                .eq(AcceptanceConfirmRecord::getResult, ITEM_PASS));
        return c == null ? 0 : c.intValue();
    }

    private boolean bothRolesAllPass(Long batchId, int totalItems) {
        return totalItems > 0
                && countPass(batchId, ACC_ROLE_EXPERT) == totalItems
                && countPass(batchId, ACC_ROLE_KEEPER) == totalItems;
    }

    private boolean hasPriorAcceptance(Long projectId) {
        return !repairService.listAcceptances(projectId).isEmpty();
    }

    private OpenDisplayMark findOnMark(Long projectId) {
        return markMapper.selectOne(new LambdaQueryWrapper<OpenDisplayMark>()
                .eq(OpenDisplayMark::getOpenKey, projectId)
                .last("LIMIT 1"));
    }

    private RepairOrder activeRework(Long projectId) {
        return reworkMapper.selectList(new LambdaQueryWrapper<RepairOrder>()
                        .eq(RepairOrder::getProjectId, projectId)
                        .in(RepairOrder::getStatus, REWORK_OPEN, REWORK_DOING)
                        .orderByDesc(RepairOrder::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private PublicImageGrant latestGrant(Long imageId) {
        return grantMapper.selectList(new LambdaQueryWrapper<PublicImageGrant>()
                        .eq(PublicImageGrant::getImageId, imageId)
                        .orderByDesc(PublicImageGrant::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private void markPostOpenKeyNull(Long reworkId) {
        reworkMapper.update(null, new LambdaUpdateWrapper<RepairOrder>()
                .eq(RepairOrder::getId, reworkId)
                .set(RepairOrder::getPostOpenKey, null));
    }

    private String normalizeRole(String role) {
        if (role == null || role.trim().isEmpty()) {
            throw new IllegalArgumentException("确认角色不能为空，仅支持 EXPERT 馆内专家 / KEEPER 保管员");
        }
        String r = role.trim().toUpperCase();
        if (!ACC_ROLE_EXPERT.equals(r) && !ACC_ROLE_KEEPER.equals(r)) {
            throw new IllegalArgumentException("确认角色非法: " + role
                    + "，仅支持 EXPERT 馆内专家 / KEEPER 保管员");
        }
        return r;
    }

    private String roleLabel(String role) {
        return ACC_ROLE_EXPERT.equals(role) ? "馆内专家" : "保管员";
    }

    private String craftLabel(String craft) {
        switch (craft) {
            case CRAFT_CLEAN: return "清洗";
            case CRAFT_REPAIR: return "补配";
            case CRAFT_STABILIZE: return "加固";
            case CRAFT_PROTECT: return "封护";
            default: return craft;
        }
    }

    private String viewLabel(String viewType) {
        if (viewType == null) {
            return "未知";
        }
        switch (viewType) {
            case VIEW_FRONT: return "正面";
            case VIEW_BACK: return "背面";
            case VIEW_BOTTOM: return "底部";
            case VIEW_DETAIL: return "细部";
            default: return viewType;
        }
    }

    private List<String> truncate(List<String> input, int max) {
        if (input.size() <= max) {
            return input;
        }
        List<String> out = new ArrayList<>(input.subList(0, max));
        out.add("等共 " + input.size() + " 项");
        return out;
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private String time(Object o) {
        return o instanceof LocalDateTime ? ((LocalDateTime) o).format(CSV_TIME) : "";
    }

    private String cell(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
