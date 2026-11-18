package other.myse.coordinator.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.XoqlService;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 客户标签功能权限收窄（coordinator 包内公共工具，2026-10-21）。
 *
 * <p><b>业务含义</b>：客户的「功能权限点」受其名下标签约束。标签配了已选职能
 * （{@code label__c.accountRoleDes__c}）时，展示给用户的权限点要收窄到
 * <b>标签权限 ∩ 原有权限来源</b>；未配标签或标签未配权限点 → 保持原口径。</p>
 *
 * <p><b>数据链路</b>：</p>
 * <pre>
 *   accountId
 *     → accountLabel__c（客户标签中间表，label=客户标签）WHERE account__c = ? AND deleteMark__c = 0
 *        · label__c → label__c（标签记录 id）
 *     → label__c.accountRoleDes__c（已选职能，multireference → roleDes__c）
 *     → 多个标签的权限点取【并集】
 * </pre>
 *
 * <p><b>⚠️ 两条 XOQL 坑（改代码前务必看）</b>：</p>
 * <ul>
 *   <li>布尔字段过滤必须写 {@code deleteMark__c = 0}，写 {@code = false} 报
 *       {@code 290003 no such column [false]}；</li>
 *   <li>{@code accountRoleDes__c} 是多值关联字段，SELECT 出来可能是 id 数组 /
 *       {@code {id,name}} 对象数组 / 逗号串 / JSON 数组字符串，用 {@link #collectIds} 统一解析。</li>
 * </ul>
 *
 * <p><b>⚠️ 为什么不放成全局公共类</b>：销售易平台各代码包（other.myse.coordinator /
 * other.myse.messagecenter / ...）<b>分别独立编译，跨包类不可见</b>，且打包时每个包只收自己的目录。
 * 所以 coordinator 与 messagecenter 各需一份，本文件是 coordinator 那份
 * （messagecenter 侧见 {@code other.myse.messagecenter.utils.LabelPermissionHelper}，
 * 逻辑保持一致，改动请同步）。</p>
 */
public class LabelPermissionHelper {

    private static final Logger logger = LoggerFactory.getLogger();

    private static final String TAG = "LabelPermission";

    /** accountLabel__c 单次上限 */
    private static final int MAX_LABEL_ROWS = 500;

    /** label__c 单次上限 */
    private static final int MAX_LABELS = 500;

    private LabelPermissionHelper() {
    }

    /**
     * 查该客户名下所有标签的权限点【并集】。
     *
     * <p><b>返回空集合 = 不收窄</b>（调用方保持原口径），三种情况都归为空集合：</p>
     * <ul>
     *   <li>该客户在 accountLabel__c 中查不到有效记录（或记录已删除）；</li>
     *   <li>有记录但 {@code label__c} 全为空；</li>
     *   <li>标签存在但所有标签的 {@code accountRoleDes__c} 都为空 —— <b>认为没有标签数据</b>。</li>
     * </ul>
     *
     * @param accountId 客户 id（null / 空 → 直接返回空集合）
     * @return 标签权限点并集；<b>空集合表示不收窄</b>
     */
    public static Set<String> queryLabelPermissionIds(String accountId) {
        Set<String> empty = new LinkedHashSet<String>();
        if (accountId == null || accountId.trim().isEmpty()) {
            return empty;
        }
        try {
            // ① 入参客户 → 标签关联记录（只取未删除）
            String sql1 = "SELECT id, label__c FROM accountLabel__c WHERE account__c = '"
                    + escapeSql(accountId) + "' AND deleteMark__c = 0 LIMIT 0, " + MAX_LABEL_ROWS;
            QueryResult<JSONObject> rt = query(sql1);
            if (rt == null || rt.getRecords() == null || rt.getRecords().isEmpty()) {
                logger.info(TAG + " 客户无有效标签记录（accountLabel__c 未命中或全部已删除），"
                        + "认为没有标签数据，不收窄: accountId=" + accountId);
                return empty;
            }

            Set<String> labelIds = new LinkedHashSet<String>();
            for (JSONObject o : rt.getRecords()) {
                collectIds(labelIds, o.get("label__c"));
            }
            boolean hasBlank = labelIds.contains("");
            labelIds.remove("");
            logger.info(TAG + " 客户标签命中: accountId=" + accountId + ", accountLabel__c "
                    + rt.getRecords().size() + " 条, 有效标签 " + labelIds.size() + " 个"
                    + (hasBlank ? "（含空 label__c，已跳过）" : ""));
            if (labelIds.isEmpty()) {
                logger.info(TAG + " 客户标签的 label__c 全为空，认为没有标签数据，不收窄: accountId=" + accountId);
                return empty;
            }

            // ② 标签 → 已选职能，多个标签取并集
            String sql2 = "SELECT id, name, accountRoleDes__c FROM label__c WHERE id IN ("
                    + buildInClause(labelIds) + ") LIMIT 0, " + MAX_LABELS;
            QueryResult<JSONObject> labelRt = query(sql2);
            Set<String> union = new LinkedHashSet<String>();
            int noDes = 0;
            if (labelRt != null && labelRt.getRecords() != null) {
                for (JSONObject o : labelRt.getRecords()) {
                    Set<String> one = new LinkedHashSet<String>();
                    collectIds(one, o.get("accountRoleDes__c"));
                    if (one.isEmpty()) {
                        noDes++;
                    }
                    union.addAll(one);
                }
            }
            logger.info(TAG + " 标签权限并集: accountId=" + accountId + ", 标签 " + labelIds.size()
                    + " 个（其中 " + noDes + " 个未配权限点）→ 并集 " + union.size() + " 项");
            if (union.isEmpty()) {
                logger.info(TAG + " 标签下均未配置已选职能(accountRoleDes__c)，认为没有标签数据，不收窄: accountId="
                        + accountId);
            }
            return union;
        } catch (Exception e) {
            // 容错：查标签失败不该让整个业务失败，退化为不收窄
            logger.error(TAG + " 查客户标签权限失败，退化为不收窄, accountId=" + accountId, e);
            return empty;
        }
    }

    /** 执行查询（失败返回 null），并打条数日志 */
    private static QueryResult<JSONObject> query(String sql) {
        logger.info(TAG + " SQL: " + sql);
        try {
            QueryResult<JSONObject> rt = XoqlService.instance().query(sql);
            int n = (rt == null || rt.getRecords() == null) ? 0 : rt.getRecords().size();
            logger.info(TAG + " 查询返回 " + n + " 条");
            return rt;
        } catch (Exception e) {
            logger.error(TAG + " 查询失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 解析多值关联字段，兼容：id 数组 / {@code {id,name}} 对象数组 / 逗号串 / JSON 数组字符串 / 单标量。
     */
    public static void collectIds(Set<String> out, Object raw) {
        if (raw == null) {
            return;
        }
        if (raw instanceof JSONArray) {
            for (Object o : (JSONArray) raw) {
                collectIds(out, o);
            }
            return;
        }
        if (raw instanceof Iterable) {
            for (Object o : (Iterable<?>) raw) {
                collectIds(out, o);
            }
            return;
        }
        if (raw instanceof JSONObject) {
            String id = str(((JSONObject) raw).get("id"));
            if (!id.isEmpty()) {
                out.add(id);
            }
            return;
        }
        String s = str(raw).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return;
        }
        if (s.startsWith("[")) {
            try {
                JSONArray arr = JSON.parseArray(s);
                if (arr != null && !arr.isEmpty()) {
                    collectIds(out, arr);
                    return;
                }
            } catch (Exception ignored) {
                // 不是 JSON 数组，按逗号串处理
            }
        }
        if (s.indexOf(',') >= 0) {
            for (String part : s.split(",")) {
                String one = part.trim();
                if (!one.isEmpty()) {
                    out.add(one);
                }
            }
            return;
        }
        out.add(s);
    }

    /** 构建带引号的 IN 子句，如 {@code ('1','2')} */
    private static String buildInClause(Set<String> ids) {
        StringBuilder sb = new StringBuilder("(");
        for (String id : ids) {
            if (sb.length() > 1) {
                sb.append(",");
            }
            sb.append("'").append(escapeSql(id)).append("'");
        }
        return sb.append(")").toString();
    }

    /** SQL 转义防注入 */
    private static String escapeSql(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }
}