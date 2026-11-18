package other.myse.coordinator.roledef;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.model.QueryResult;
import org.apache.commons.lang.StringUtils;
import other.myse.coordinator.api.UserPermissionBaseApi;
import other.myse.coordinator.utils.LabelPermissionHelper;
import other.myse.coordinator.utils.HttpUtil;

import java.util.*;

/**
 * 角色权限定义 - 角色的功能权限弹窗查询（other.myse.coordinator.roledef）
 *
 * <p>列表页点「编辑角色权限」打开弹窗，返回该角色的功能权限点勾选列表。
 * 2026-09-04 老板拍板：权限点用**扁平结构**（树形暂不需要）。</p>
 *
 * <p>权限点来源 = <b>generalRole__c.customItem2__c（角色默认权限池）</b>里的 roleDes__c，
 * checked = accountRole__c.accountRoleDes__c 是否包含。
 * 账户已勾选但不在角色权限池内的权限点（历史脏数据）不会出现在弹窗中，日志会 warn 提示数量。</p>
 *
 * <p><b>客户标签收窄（2026-10-20 新增）</b>：按 accountId 查客户标签，多个标签取并集后再与权限池求交集。</p>
 * <pre>
 *   accountId
 *     → accountLabel__c（客户标签中间表，label=客户标签）WHERE account__c = accountId
 *     → 每行的 label__c（指向 label__c 标签记录）
 *     → label__c.accountRoleDes__c（已选职能，多值引用 roleDes__c）
 *     → 多个标签的 accountRoleDes__c 取【并集】
 *     → 并集 ∩ generalRole__c.customItem2__c（角色权限池）= 最终返回的 permissions
 * </pre>
 * <p><b>判定口径（老板确认）</b>：只有当客户<b>确实挂了带权限点的标签</b>时才走交集逻辑 ——
 * 即标签的 {@code accountRoleDes__c}（已选职能）非空。以下情况都<b>保持原逻辑</b>（返回全量角色权限池）：</p>
 * <ul>
 *   <li>该客户在 accountLabel__c 中查不到有效记录（或记录已删除）；</li>
 *   <li>有记录但 {@code label__c} 全为空；</li>
 *   <li>标签存在但所有标签的 {@code accountRoleDes__c} 都为空 —— <b>认为没有标签数据</b>。</li>
 * </ul>
 *
 * <p>配套保存接口：`/coordinator/roledef/save`。</p>
 *
 * <pre>
 * 入参（二选一，accountRoleId 优先）:
 *   accountRoleId  String  accountRole__c 记录ID（列表行返回的 accountRoleId）
 *   accountId+roleId       兜底：按 purchaseId__c + basicRole__c 定位 accountRole
 *
 * 返回: {
 *   accountRoleId: "xxx",
 *   roleId: "xxx",
 *   roleName: "业务员",
 *   roleType: "基础角色",
 *   totalCount: 4,              // 权限点总数（弹窗标题"功能权限（N）"）
 *   permissions: [              // 角色权限池内的权限点，扁平
 *     { desId, desName, checked: true/false }
 *   ]
 * }
 * </pre>
 */
@RestApi(baseUrl = "/coordinator")
public class RoleDefPermissionsApi extends UserPermissionBaseApi {

    /** 日志标识：本接口所有日志统一前缀，便于线上按接口检索 */
    private static final String TAG = "RoleDefPermissions";

    @RestMapping(value = "/roledef/permissions", method = RequestMethod.POST)
    public String permissions(@RestBeanParam(name = "json") String body) {
        long startTs = System.currentTimeMillis();
        String resp;
        try {
            logIn(TAG, body);
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = checkPermissionChanged(TAG);
            if (guard != null) {
                return guard;
            }
            resp = doPermissions(body);
        } catch (Exception e) {
            logger.error(TAG + " 处理异常: " + e.getMessage(), e);
            resp = HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
        // 权限点列表是全量 roleDes__c，出参只打条数，不打全量
        boolean ok = false;
        int count = 0;
        try {
            JSONObject r = JSON.parseObject(resp);
            ok = Boolean.TRUE.equals(r.getBoolean("success"));
            JSONObject d = r.getJSONObject("data");
            JSONArray ps = d == null ? null : d.getJSONArray("permissions");
            count = ps == null ? 0 : ps.size();
        } catch (Exception ignore) {
            // 出参解析失败不影响业务，仅日志少打条数
        }
        logOutList(TAG, ok, count, startTs);
        return resp;
    }

    /** 业务逻辑（异常统一冒泡到 {@link #permissions(String)} 兜底，本方法不重复打异常日志） */
    private String doPermissions(String body) {
        if (body == null || body.isEmpty()) {
            logger.warn(TAG + " 请求体为空，返回 PARAM_EMPTY");
            return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
        }
        JSONObject req = JSON.parseObject(body);
        String accountRoleId = trimToNull(req.getString("accountRoleId"));
        String accountId = trimToNull(req.getString("accountId"));
        String roleId = trimToNull(req.getString("roleId"));

        // ===== 1. 定位 accountRole 配置（accountRoleId 优先，accountId+roleId 兜底）=====
        String arSql;
        String locateBy;
        if (accountRoleId != null) {
            if (!accountRoleId.matches("\\d+")) {
                logger.warn(TAG + " accountRoleId 非法: " + accountRoleId);
                return HttpUtil.fail("PARAM_ERROR", "accountRoleId 必须为数字").toJSONString();
            }
            locateBy = "accountRoleId=" + accountRoleId;
            arSql = "SELECT id, purchaseId__c, basicRole__c, accountRoleDes__c FROM accountRole__c "
                    + "WHERE id = " + accountRoleId + " LIMIT 1";
        } else if (accountId != null && roleId != null) {
            locateBy = "accountId=" + accountId + ", roleId=" + roleId;
            arSql = "SELECT id, purchaseId__c, basicRole__c, accountRoleDes__c FROM accountRole__c "
                    + "WHERE purchaseId__c = '" + escapeSql(accountId) + "' "
                    + "AND basicRole__c = '" + escapeSql(roleId) + "' LIMIT 1";
        } else {
            logger.warn(TAG + " 参数校验失败: accountRoleId 或 accountId+roleId 至少传一组, 入参=" + abbreviate(body));
            return HttpUtil.fail("PARAM_ERROR", "accountRoleId 或 accountId+roleId 至少传一组").toJSONString();
        }
        logger.info(TAG + " 定位方式: " + locateBy);

        QueryResult<JSONObject> arRt = queryWithLog(TAG, "accountRole__c", arSql);
        if (arRt == null || arRt.getRecords() == null || arRt.getRecords().isEmpty()) {
            logger.warn(TAG + " 未查到 accountRole 配置（该账户未配置此角色）: " + locateBy);
            return HttpUtil.fail("NOT_FOUND", "该账户未配置此角色，无法编辑权限").toJSONString();
        }
        JSONObject ar = arRt.getRecords().get(0);
        String basicRoleId = str(ar.get("basicRole__c"));
        // 标签查询用的 accountId：优先入参（前端显式传的更贴近意图），否则回退 accountRole__c.purchaseId__c
        String labelAccountId = accountId != null ? accountId : str(ar.get("purchaseId__c")).trim();

        // 当前已勾选权限点（accountRoleDes__c 多值 -> roleDes id 集合）
        Set<String> checkedDesIds = new HashSet<>();
        parseMultiIds(checkedDesIds, ar.get("accountRoleDes__c"));
        logger.info(TAG + " accountRole 命中: id=" + ar.get("id")
                + ", basicRole__c=" + (basicRoleId.isEmpty() ? "(空)" : basicRoleId)
                + ", 已勾选权限点=" + checkedDesIds.size()
                + (checkedDesIds.isEmpty() ? "" : " → " + abbreviate(joinIds(checkedDesIds))));

        // ===== 2. 查基础角色信息（名称/类型）+ 该角色的默认权限池 customItem2__c =====
        // ===== 3. 组装权限点（roleDes__c）+ 勾选状态 =====
        JSONArray permissions = new JSONArray();
        String roleName = "";
        String roleType = "";

        if (basicRoleId.isEmpty()) {
            logger.warn(TAG + " accountRole.basicRole__c 为空，无法取角色名称与权限池，返回空权限列表: accountRoleId=" + ar.get("id"));
        } else {
            Set<String> rids = new HashSet<>();
            rids.add(basicRoleId);
            String roleSql = "SELECT id, name, roleType__c, customItem2__c FROM generalRole__c WHERE id IN ("
                    + buildInClause(rids) + ")";
            QueryResult<JSONObject> roleRt = queryWithLog(TAG, "generalRole__c", roleSql);
            if (roleRt == null || roleRt.getRecords() == null || roleRt.getRecords().isEmpty()) {
                logger.warn(TAG + " 未查到基础角色 generalRole__c: id=" + basicRoleId);
            } else {
                JSONObject gr = roleRt.getRecords().get(0);
                roleName = str(gr.get("name"));
                roleType = parseFirstArrayItem(str(gr.get("roleType__c")));

                // 角色默认权限池 customItem2__c（多值 roleDes id）→ 取出合法的数字 id
                List<Long> desIds = new ArrayList<>();
                JSONArray pool = gr.getJSONArray("customItem2__c");
                if (pool != null) {
                    for (int i = 0; i < pool.size(); i++) {
                        String one = str(pool.get(i)).trim();
                        if (one.isEmpty()) {
                            continue;
                        }
                        if (!one.matches("\\d+")) {
                            logger.warn(TAG + " 角色权限池 customItem2__c 含非法值，已跳过: roleId=" + basicRoleId + ", 值=" + one);
                            continue;
                        }
                        desIds.add(Long.parseLong(one));
                    }
                }
                logger.info(TAG + " 角色信息: id=" + basicRoleId + ", name=" + roleName
                        + ", roleType=" + roleType + ", 权限池(customItem2__c)=" + (pool == null ? 0 : pool.size())
                        + " 项, 合法 " + desIds.size() + " 项");

                // ===== 2.5 客户标签收窄（2026-10-20）：多标签取并集，再与角色权限池求交集 =====
                //   标签已配权限点 → permissions = 标签权限 ∩ 角色权限池
                //   标签未配权限点 / 无标签 / 未取到 accountId → 保持原逻辑（全量角色权限池）
                boolean narrowed = false;
                if (labelAccountId.isEmpty()) {
                    logger.warn(TAG + " 未取到 accountId，跳过客户标签收窄（按角色权限池全量返回）");
                } else {
                    Set<String> labelDesIds = queryLabelPermissionIds(labelAccountId);
                    if (labelDesIds != null && !labelDesIds.isEmpty()) {
                        narrowed = true;
                        // 交集：角色权限池 ∩ 标签权限（多标签已在上游取并集）
                        List<String> poolStrIds = new ArrayList<>();
                        for (Long d : desIds) {
                            poolStrIds.add(String.valueOf(d));
                        }
                        Set<String> poolSet = new LinkedHashSet<>(poolStrIds);
                        poolSet.retainAll(labelDesIds);
                        List<Long> intersected = new ArrayList<>();
                        for (String id : poolStrIds) {
                            if (poolSet.contains(id)) {
                                intersected.add(Long.parseLong(id));
                            }
                        }
                        logger.info(TAG + " 客户标签收窄: accountId=" + labelAccountId
                                + ", 角色权限池 " + poolStrIds.size() + " 项"
                                + " ∩ 标签权限 " + labelDesIds.size() + " 项"
                                + " = " + intersected.size() + " 项"
                                + (intersected.size() < poolStrIds.size() ? "（已收窄）" : "（无收窄）"));
                        desIds = intersected;
                    }
                }

                if (!desIds.isEmpty()) {
                    String desSql = "SELECT id, name FROM roleDes__c WHERE id IN ("
                            + StringUtils.join(desIds, ",") + ")";
                    QueryResult<JSONObject> desRt = queryWithLog(TAG, "roleDes__c", desSql);
                    if (desRt != null && desRt.getRecords() != null) {
                        int checkedCount = 0;
                        for (JSONObject record : desRt.getRecords()) {
                            String desId = record.getString("id");
                            if (desId == null || desId.isEmpty()) {
                                continue;
                            }
                            boolean checked = checkedDesIds.contains(desId);
                            if (checked) {
                                checkedCount++;
                            }
                            JSONObject item = new JSONObject();
                            item.put("desId", desId);
                            item.put("desName", record.getString("name"));
                            item.put("checked", checked);
                            permissions.add(item);
                        }
                        logger.info(TAG + " 权限点组装完成: 共 " + permissions.size() + " 项, 其中已勾选 "
                                + checkedCount + " 项, 未勾选 " + (permissions.size() - checkedCount) + " 项");
                    }
                } else {
                    logger.warn(TAG + " 最终权限池为空（角色权限池=" + (narrowed ? "已被客户标签收窄" : "本身为空")
                            + "），permissions 返回空数组: roleId=" + basicRoleId
                            + ", roleName=" + roleName);
                }
            }
        }

        // 勾选但不在最终权限池里的（历史脏数据：账户实际分配超出角色池，或被客户标签收窄排除）
        // ——只提示不丢数据，方便发现"标签配少了导致已勾选权限看不见"这类问题
        Set<String> uncheckedOrphan = new HashSet<>(checkedDesIds);
        for (int i = 0; i < permissions.size(); i++) {
            uncheckedOrphan.remove(permissions.getJSONObject(i).getString("desId"));
        }
        if (!uncheckedOrphan.isEmpty()) {
            logger.warn(TAG + " 账户已勾选但不在角色权限池内的权限点 " + uncheckedOrphan.size()
                    + " 个（不会出现在弹窗中）: " + abbreviate(joinIds(uncheckedOrphan)));
        }

        // ===== 4. 组装返回 =====
        JSONObject result = new JSONObject();
        result.put("accountRoleId", str(ar.get("id")));
        result.put("roleId", basicRoleId);
        result.put("roleName", roleName);
        result.put("roleType", roleType);
        result.put("totalCount", permissions.size());
        result.put("permissions", permissions);
        return HttpUtil.success(result).toJSONString();
    }

    /**
     * 查该客户名下所有标签的权限点【并集】。
     *
     * <p>链路：{@code accountLabel__c}（客户标签中间表，label=客户标签）→ {@code label__c}（标签）→ {@code accountRoleDes__c}（已选职能，多值引用 roleDes__c）。</p>
     *
     * <p><b>过滤 deleteMark__c = 0</b>：只取未删除的标签关联记录
     * （XOQL 里布尔字段要用 {@code = 0}，写 {@code = false} 会报 {@code 290003 no such column}）。</p>
     *
     * <p><b>返回空集合 = 不收窄</b>（按原逻辑返回全量角色权限池），包括两种情况（老板 2026-10-20 确认）：</p>
     * <ul>
     *   <li>该客户在 accountLabel__c 中查不到任何有效记录；</li>
     *   <li>有记录，但所有标签的 {@code accountRoleDes__c} 都为空 —— <b>认为没有标签数据</b>。</li>
     * </ul>
     *
     * @param accountId 客户 id
     * @return 标签权限点并集；<b>空集合表示不收窄</b>（走原逻辑）
     */
    /**
     * 查该客户名下所有标签的权限点【并集】。
     *
     * <p>2026-10-21：实现已下沉到包级工具类 {@link LabelPermissionHelper}
     * （coordinator 包内 4 个接口共用），这里保留薄封装转发，避免本类其余调用点改动。</p>
     *
     * @return 标签权限点并集；<b>空集合表示不收窄</b>（走原逻辑）
     */
    private Set<String> queryLabelPermissionIds(String accountId) {
        return LabelPermissionHelper.queryLabelPermissionIds(accountId);
    }
    /** 日志截断（避免长 SQL/入参刷屏） */
    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        if (s.length() <= 1000) {
            return s;
        }
        return s.substring(0, 1000) + "...(共" + s.length() + "字符，已截断)";
    }

    /** id 集合拼成可读字符串（日志用） */
    private static String joinIds(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(id);
        }
        return sb.toString();
    }

}
