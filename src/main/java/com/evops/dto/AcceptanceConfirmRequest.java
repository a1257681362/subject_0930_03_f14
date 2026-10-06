package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 馆内专家 / 保管员分别提交的逐项确认（museum-14）。
 * 必须对清单全部条目逐条给出结论：缺一项即进入整改；
 * 任一条目不合格同样进入整改。双方全部合格的轮次才验收通过。
 *
 * <p>馆内专家（EXPERT）全部合格时提出验收结论：
 * result=PASS/CONDITIONAL，CONDITIONAL（附条件通过）必带 gradeAfter。</p>
 */
@Data
public class AcceptanceConfirmRequest {

    /** EXPERT 馆内专家 / KEEPER 保管员，两类确认分别提交、不可互代 */
    @NotBlank(message = "确认角色不能为空")
    private String confirmerRole;

    @NotBlank(message = "确认人姓名不能为空")
    private String confirmerName;

    @NotEmpty(message = "必须逐项确认，缺一项即进入整改")
    @Valid
    private List<AcceptanceItemResult> items;

    /** 仅 EXPERT 全部合格时使用：验收结论 PASS / CONDITIONAL，默认 PASS */
    private String result;

    /** CONDITIONAL 时必填：验收后保护等级 ONE/TWO/THREE */
    private String gradeAfter;
}
