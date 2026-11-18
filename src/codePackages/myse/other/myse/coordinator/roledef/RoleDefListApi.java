package other.myse.coordinator.roledef;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.model.QueryResult;
import other.myse.coordinator.api.UserPermissionBaseApi;
import other.myse.coordinator.utils.HttpUtil;

import java.util.*;

/**
 * 角色权限定义 - 列表页（other.myse.coordinator.roledef）
 *
 * <p>角色权限定义 Tab 的列表：当前账户下已配置的角色权限概览。
 * 列：角色名 / 权限描述 / 相关账号（关联用户数）/ 操作（编辑角色权限）。</p>
 *
 * <p>第一维度 = accountRole__c（该账户实际配置的角色），
 * 权限描述取 accountRoleDes__c → roleDes.name（该账户实际分配的权限点，
 * 不是 generalRole.customItem2__c 的全局默认权限池）。</p>
 *
 * <pre>
 * 入参:
 *   accountId  String  必填  当前账户ID
 *
 * 返回: {
 *   list: [
 *     {
 *       id: "xxx",                    // accountRole__c 记录ID（= accountRoleId，兼容字段）
 *       accountRoleId: "xxx",         // accountRole__c 记录ID（弹窗查询/保存接口用）
 *       roleId: "xxx",                // 基础角色 generalRole id (basicRole__c)
 *       roleName: "业务员",
 *       roleType: "基础角色",          // 或 "协调员角色"
 *       permissionDescription: "价格货期查询，查询报价单/订单",
 *       relatedUserCount: 23          // 该角色配置下已关联的用户数(userRole记录数)
 *     }
 *   ]
 * }
 * </pre>
 */
@RestApi(baseUrl = "/coordinator")
public class RoleDefListApi extends UserPermissionBaseApi {

    /** 日志标识：本接口所有日志统一前缀，便于线上按接口检索 */
    private static final String TAG = "RoleDefList";

    @RestMapping(value = "/roledef/list", method = RequestMethod.POST)
    public String list(@RestBeanParam(name = "json") String body) {
        long startTs = System.currentTimeMillis();
        String resp;
        try {
            logIn(TAG, body);
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = checkPermissionChanged(TAG);
            if (guard != null) {
                return guard;
            }
            resp = doList(body, startTs);
        } catch (Exception e) {
            logger.error(TAG + " 处理异常: " + e.getMessage(), e);
            resp = HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
            logger.error(TAG + " 出参: " + resp);
        }
        return resp;
    }

    /** 业务逻辑（异常统一冒泡到 {@link #list(String)} 兜底，本方法不重复打异常日志） */
    private String doList(String body, long startTs) {
        if (body == null || body.isEmpty()) {
            String r = HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            logger.warn(TAG + " 出参: " + r);
            return r;
        }
        JSONObject req = JSON.parseObject(body);
        String accountId = req.getString("accountId");
        if (accountId == null || accountId.trim().isEmpty()) {
            String r = HttpUtil.fail("PARAM_ERROR", "accountId 不能为空").toJSONString();
            logger.warn(TAG + " 出参: " + r);
            return r;
        }

        // ===== 1. 第一维度：查该账户下的 accountRole 记录 =====
        String arSql = "SELECT id, basicRole__c, accountRoleDes__c FROM accountRole__c "
                + "WHERE purchaseId__c = " + Long.parseLong(accountId.trim());
        QueryResult<JSONObject> arRt = queryWithLog(TAG, "accountRole__c", arSql);

        JSONArray list = new JSONArray();

        if (arRt != null && arRt.getRecords() != null && !arRt.getRecords().isEmpty()) {
            // ---- 批量收集角色ID + 权限描述ID ----
            Set<String> roleIds = new HashSet<>();
            Set<String> allDesIds = new HashSet<>();
            for (Object o : arRt.getRecords()) {
                JSONObject ar = (JSONObject) o;
                collectId(roleIds, ar.get("basicRole__c"));
                parseMultiIds(allDesIds, ar.get("accountRoleDes__c"));
            }

            // ---- 批量查角色名称/类型（generalRole）----
            Map<String, JSONObject> roleMap = queryRoleInfo(roleIds);

            // ---- 批量查权限点名称（roleDes）----
            Map<String, String> desNameMap = queryRoleDesNames(allDesIds);

            // ---- 统计每个基础角色被该账户 userRole 引用的次数（关联用户数）----
            Map<String, Long> userCountMap = queryRoleUserCounts(Long.parseLong(accountId.trim()));

            // ---- 组装每行 ----
            for (Object o : arRt.getRecords()) {
                JSONObject ar = (JSONObject) o;
                String arId = str(ar.get("id"));
                String rid = str(ar.get("basicRole__c"));

                JSONObject roleInfo = roleMap.get(rid);
                String rname = roleInfo != null ? str(roleInfo.get("name")) : "";
                String rtype = roleInfo != null ? str(roleInfo.get("roleType__c")) : "";
                // roleType__c 可能是 JSON 数组字符串如 ["基础角色"]，解析取首值
                rtype = parseFirstArrayItem(rtype);

                JSONObject item = new JSONObject();
                item.put("id", arId);
                item.put("accountRoleId", arId);
                item.put("roleId", rid);
                item.put("roleName", rname);
                item.put("roleType", rtype);

                // 权限描述：accountRoleDes__c -> roleDes.name 拼接（该账户实际配置）
                item.put("permissionDescription",
                        buildPermissionDescription(ar.get("accountRoleDes__c"), desNameMap));

                // 相关账号：userRole 中引用此基础角色(role__c 多值含 basicRole id)的记录数
                item.put("relatedUserCount",
                        userCountMap.getOrDefault(rid, 0L));

                list.add(item);
            }
        }

        JSONObject result = new JSONObject();
        result.put("list", list);
        logOutList(TAG, true, list.size(), startTs);
        return HttpUtil.success(result).toJSONString();
    }

    /**
     * 批量查基础角色信息（id -> {name, roleType__c}）
     */
    private Map<String, JSONObject> queryRoleInfo(Set<String> roleIds) {
        Map<String, JSONObject> map = new HashMap<>();
        if (roleIds == null || roleIds.isEmpty()) {
            return map;
        }
        String sql = "SELECT id, name, roleType__c FROM generalRole__c WHERE id IN ("
                + buildInClause(roleIds) + ")";
        QueryResult<JSONObject> rt = queryWithLog(TAG, "generalRole__c", sql);
        if (rt != null && rt.getRecords() != null) {
            for (Object o : rt.getRecords()) {
                JSONObject gr = (JSONObject) o;
                map.put(str(gr.get("id")), gr);
            }
        }
        return map;
    }

    /**
     * 统计每个基础角色被该账户 userRole 引用的次数（即关联用户数）。
     * 注意：userRole.role__c 是多值字段，存的是 generalRole id（非 accountRole id），
     * 无法 GROUP BY，故全量取出后内存计数。
     * 返回 Map<basicRoleId(generalRoleId), count>
     */
    private Map<String, Long> queryRoleUserCounts(Long account) {
        Map<String, Long> map = new HashMap<>();
        String sql = "SELECT id, role__c FROM userRole__c WHERE purchaseId__c = " + account;
        QueryResult<JSONObject> rt = queryWithLog(TAG, "userRole__c", sql);
        if (rt != null && rt.getRecords() != null) {
            for (Object o : rt.getRecords()) {
                JSONObject row = (JSONObject) o;
                Set<String> rids = new HashSet<>();
                parseMultiIds(rids, row.get("role__c"));
                for (String rid : rids) {
                    map.put(rid, map.getOrDefault(rid, 0L) + 1);
                }
            }
        }
        return map;
    }
}
