package other.myse.coordinator.approval;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.http.RkhdHttpClient;
import com.rkhd.platform.sdk.http.RkhdHttpData;
import com.rkhd.platform.sdk.model.QueryResult;
import other.myse.coordinator.api.UserPermissionBaseApi;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 待审批列表模块（approval 子包）共享基类
 *
 * <p>继承 {@link UserPermissionBaseApi} 复用全部关联查询/工具方法，
 * 本类只补充"账户实际权限描述"（accountRoleDes__c 口径）查询能力。</p>
 *
 * <p>包结构说明：coordinator（账户权限管理）按模块拆子包——
 * permission=权限管理（用户视角）、approval=待审批列表（本包）、
 * 后续 roledef=角色权限定义、account=账户访问管理。</p>
 */
public abstract class ApprovalBaseApi extends UserPermissionBaseApi {

    /** roleRequest__c.status__c 的选项集：显示名 → 选项 value（数字） */
    private static volatile Map<String, Integer> statusLabelToValue;

    /** 选项集缓存加载时间 */
    private static volatile long statusMapAt = 0L;

    /** 选项集缓存有效期（30 分钟） */
    private static final long PICKLIST_TTL = 30L * 60L * 1000L;

    /**
     * 把 status__c 的<b>显示名</b>换成<b>选项 value（数字）</b>。
     *
     * <p><b>为什么需要转</b>：XOQL 对 picklist 字段只返回显示名数组
     * （如 {@code ["审批"]}），取不到 value；而 {@code status__c.value} 语法平台不支持
     * （报 {@code no such relation column}）。所以读对象描述的选项集做反查。</p>
     *
     * <p><b>选项集接口</b>：{@code GET /rest/data/v2.0/xobjects/roleRequest__c/description}
     * → {@code data.fields[status__c].selectitem[]}，每项含 {@code label} 与 {@code value}。</p>
     *
     * @param label 显示名，如 "审批"
     * @return 选项 value（2/3/4…）；查不到返回 null
     */
    protected Integer statusValueOf(String label) {
        if (label == null || label.trim().isEmpty()) {
            return null;
        }
        Map<String, Integer> map = queryStatusLabelToValue();
        Integer value = map.get(label.trim());
        if (value == null) {
            logger.warn(TAG_BASE + " status__c 显示名未匹配到选项 value: " + label);
        }
        return value;
    }

    /** 读取 roleRequest__c.status__c 选项集，建立「显示名 → value」映射（静态缓存 30 分钟）。 */
    private Map<String, Integer> queryStatusLabelToValue() {
        long now = System.currentTimeMillis();
        Map<String, Integer> cached = statusLabelToValue;
        if (cached != null && statusMapAt > 0L && (now - statusMapAt) < PICKLIST_TTL) {
            return cached;
        }
        Map<String, Integer> map = new HashMap<String, Integer>();
        try {
            RkhdHttpData httpData = RkhdHttpData.newBuilder()
                    .callString("/rest/data/v2.0/xobjects/roleRequest__c/description")
                    .callType("GET")
                    .build();
            String respStr = RkhdHttpClient.instance().performRequest(httpData);
            if (respStr != null && !respStr.trim().isEmpty()) {
                JSONObject resp = JSON.parseObject(respStr);
                JSONObject data = resp.getJSONObject("data");
                JSONArray fields = data == null ? null : data.getJSONArray("fields");
                if (fields != null) {
                    for (int i = 0; i < fields.size(); i++) {
                        JSONObject f = fields.getJSONObject(i);
                        if (f == null || !"status__c".equals(f.getString("apiKey"))) {
                            continue;
                        }
                        JSONArray items = f.getJSONArray("selectitem");
                        if (items == null) {
                            continue;
                        }
                        for (int j = 0; j < items.size(); j++) {
                            JSONObject it = items.getJSONObject(j);
                            if (it == null) {
                                continue;
                            }
                            String label = it.getString("label");
                            Integer value = it.getInteger("value");
                            if (label != null && value != null) {
                                map.put(label.trim(), value);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error(TAG_BASE + " 读取 roleRequest__c.status__c 选项集失败: " + e.getMessage(), e);
        }
        logger.info(TAG_BASE + " status__c 选项集加载完成: " + map);
        statusLabelToValue = map;
        statusMapAt = now;
        return map;
    }

    /**
     * 查询指定账户下、指定角色集合的「账户实际分配权限描述」。
     *
     * <p>口径与 permission 模块一致：accountRole__c.accountRoleDes__c → roleDes__c.name 拼接；
     * 该角色在此账户下无 accountRole 配置时，回退 generalRole.customItem2__c（角色模板默认池），
     * 保证审批详情页有内容可显示（例如角色配置在申请提交后被删的场景）。</p>
     *
     * @param accountId 账户 id（roleRequest__c.account__c）
     * @param roleIds   generalRole id 集合
     * @return Map&lt;roleId, 权限描述（中文逗号拼接，可能为空串）&gt;
     */
    protected Map<String, String> queryAccountRoleDescriptions(String accountId, Set<String> roleIds) {
        Map<String, String> result = new HashMap<>();
        if (roleIds == null || roleIds.isEmpty()) {
            return result;
        }

        // 1) 账户实际配置：accountRole__c (purchaseId + basicRole IN roleIds) -> accountRoleDes__c
        Map<String, Object> accountDesRaw = new HashMap<>();
        if (accountId != null && !accountId.isEmpty()) {
            String q = "SELECT id, basicRole__c, accountRoleDes__c FROM accountRole__c "
                    + "WHERE purchaseId__c = '" + escapeSql(accountId) + "'"
                    + " AND basicRole__c IN (" + buildInClause(roleIds) + ")";
            QueryResult<JSONObject> rt = queryWithLog(TAG_BASE, "accountRole__c", q);
            if (rt != null && rt.getRecords() != null) {
                for (Object o : rt.getRecords()) {
                    JSONObject ar = (JSONObject) o;
                    String basicRoleId = str(ar.get("basicRole__c"));
                    if (!basicRoleId.isEmpty()) {
                        accountDesRaw.put(basicRoleId, ar.get("accountRoleDes__c"));
                    }
                }
            }
        }

        // 2) 无账户配置的角色：回退角色模板默认池 generalRole.customItem2__c
        Set<String> fallbackRoleIds = new java.util.HashSet<>();
        for (String rid : roleIds) {
            if (!accountDesRaw.containsKey(rid)) {
                fallbackRoleIds.add(rid);
            }
        }
        Map<String, Object> templateDesRaw = new HashMap<>();
        if (!fallbackRoleIds.isEmpty()) {
            String q = "SELECT id, customItem2__c FROM generalRole__c WHERE id IN ("
                    + buildInClause(fallbackRoleIds) + ")";
            QueryResult<JSONObject> rt = queryWithLog(TAG_BASE, "generalRole__c(模板权限池)", q);
            if (rt != null && rt.getRecords() != null) {
                for (Object o : rt.getRecords()) {
                    JSONObject gr = (JSONObject) o;
                    templateDesRaw.put(gr.getString("id"), gr.get("customItem2__c"));
                }
            }
        }

        // 3) 汇总所有 roleDes id，批量查名称后逐角色拼接
        Set<String> allDesIds = new java.util.HashSet<>();
        for (Object raw : accountDesRaw.values()) {
            parseMultiIds(allDesIds, raw);
        }
        for (Object raw : templateDesRaw.values()) {
            parseMultiIds(allDesIds, raw);
        }
        Map<String, String> desNameMap = queryRoleDesNames(allDesIds);

        for (String rid : roleIds) {
            Object raw = accountDesRaw.containsKey(rid) ? accountDesRaw.get(rid) : templateDesRaw.get(rid);
            result.put(rid, buildPermissionDescription(raw, desNameMap));
        }
        return result;
    }
}
