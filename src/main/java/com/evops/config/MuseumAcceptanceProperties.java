package com.evops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 修复成果验收配置（museum.acceptance.*）。
 *
 * <p>验收清单按「材质 + 工艺」生成：修复师提交成果报告时声明本次修复涉及的工艺，
 * 系统按文物材质与工艺从模板取条目；材质未配置专属模板时回落到该工艺的 DEFAULT
 * 通用模板。模板可在 application.yml 以 {@code museum.acceptance.templates} 整体覆盖。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "museum.acceptance")
public class MuseumAcceptanceProperties {

    /** 清单模板：同工艺下材质命中专属模板优先，否则取 material=DEFAULT 的通用模板 */
    private List<Template> templates = defaultTemplates();

    /**
     * 取某材质 + 工艺的清单条目：先精确匹配材质，再回落 DEFAULT；
     * 两者都没有时给出该工艺的兜底条目，保证清单一定有项可验。
     */
    public List<String> itemsFor(String material, String craft) {
        Template exact = null;
        Template fallback = null;
        for (Template t : templates) {
            if (!craft.equals(t.getCraft())) {
                continue;
            }
            if ("DEFAULT".equals(t.getMaterial())) {
                fallback = t;
            }
            if (material != null && material.equals(t.getMaterial())) {
                exact = t;
            }
        }
        Template chosen = exact != null ? exact : fallback;
        if (chosen != null && chosen.getItems() != null && !chosen.getItems().isEmpty()) {
            return chosen.getItems();
        }
        return Arrays.asList("修复工艺【" + craft + "】处理部位现状符合修复方案与馆藏规范");
    }

    @Data
    public static class Template {
        /** 工艺码：CLEAN 清洗 / REPAIR 补配 / STABILIZE 加固 / PROTECT 封护 */
        private String craft;
        /** 材质码：与文物档案材质一致；DEFAULT 为该工艺的通用模板 */
        private String material;
        /** 清单条目（验收要点），每条由专家与保管员分别逐项确认 */
        private List<String> items = new ArrayList<>();
    }

    private static Template tpl(String craft, String material, String... items) {
        Template t = new Template();
        t.setCraft(craft);
        t.setMaterial(material);
        t.setItems(Arrays.asList(items));
        return t;
    }

    /**
     * 内置默认模板：四类工艺 × 通用条目，另给青铜/书画/陶瓷三类高频材质的专属条目。
     */
    private static List<Template> defaultTemplates() {
        List<Template> list = new ArrayList<>();
        list.add(tpl("CLEAN", "DEFAULT",
                "表面附着物与污染物已清除干净",
                "清洗强度适当，未对本体造成过度清洗或材质损伤",
                "清洗范围与修复方案一致，无扩大处理"));
        list.add(tpl("REPAIR", "DEFAULT",
                "补配/修复部位与本体结合牢固、无空鼓翘边",
                "修复边界处理协调，随色随形可辨识",
                "补配材料与本体相容且具备可逆性"));
        list.add(tpl("STABILIZE", "DEFAULT",
                "裂隙、形变与脆弱部位已加固稳定",
                "加固材料与本体相容、具备可逆性",
                "加固后器物结构安全，无次生应力风险"));
        list.add(tpl("PROTECT", "DEFAULT",
                "封护层均匀完整，无漏封、气泡与眩光",
                "封护材料耐候稳定且具备可逆性",
                "封护后外观与处理前协调一致"));

        list.add(tpl("CLEAN", "青铜",
                "表面硬结物与有害锈清除到位，无活性氯化物残留",
                "除锈力度适当，基体与古铜色层未受损伤",
                "铭文、花纹等历史信息清晰可辨且无损伤"));
        list.add(tpl("REPAIR", "青铜",
                "补配合金与本体电化学相容，无加速腐蚀风险",
                "补配接口贴合严密、无翘曲错位",
                "随色作旧协调且可识别、可逆"));
        list.add(tpl("STABILIZE", "青铜",
                "裂隙矫形到位，焊接/粘接点位牢固",
                "缓释处理完整覆盖有害锈区域"));
        list.add(tpl("PROTECT", "青铜",
                "缓蚀封护层连续均匀，无漏封与流挂",
                "封护层对铜器表面色泽无明显改变"));

        list.add(tpl("CLEAN", "书画",
                "画面霉斑、污渍清除适度，无残留水痕",
                "纸绢质地未因清洗受损，墨色颜料不晕散",
                "经清洗后画心平整无皱褶变形"));
        list.add(tpl("REPAIR", "书画",
                "补缀、托裱平服，接缝隐密不硌眼",
                "补纸补绢材质、帘纹、色度与画心匹配",
                "全色接笔适度可识别，不覆盖原迹"));
        list.add(tpl("STABILIZE", "书画",
                "折裂、断裂处命纸加固到位",
                "装裱结构应力释放均匀，无新折痕"));

        list.add(tpl("REPAIR", "陶瓷",
                "黏接断面拼合严丝合缝，缝线规整",
                "黏合剂可逆且挤出余量清理干净",
                "缺肉补配随色随釉适度、可识别"));
        list.add(tpl("STABILIZE", "陶瓷",
                "碎片加固后无错位、无新增崩裂",
                "缺损部位补配牢固，承重与摆放稳定"));
        return list;
    }
}
