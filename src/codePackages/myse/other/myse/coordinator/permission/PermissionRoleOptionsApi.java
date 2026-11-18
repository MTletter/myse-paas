package other.myse.coordinator.permission;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.model.QueryResult;
import other.myse.coordinator.utils.HttpUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限管理 - 权限变更弹窗数据（other.myse.coordinator.permission）
 *
 * <p>对应页面：用户权限详情 - 编辑用户权限 - 权限变更弹窗。
 * 展示该用户"已选择/未选择"的角色，分 普通角色 / 协调员角色 两个 Tab。</p>
 *
 * <p>选项范围 = 该账户 accountRole__c 已配置的角色（未配置的角色不显示）；
 * 权限描述取 accountRoleDes__c（账户实际分配的权限点）；
 * checked = 该用户 userRole.role__c 当前是否已分配。</p>
 *
 * <p>注意：仅按角色类型分组展示，角色互斥不校验（保存接口的互斥校验已下线，由前端控制）。</p>
 *
 * <pre>
 * 入参:
 *   id  String  必填  userRole 记录 ID（列表行 id）
 *
 * 返回: {
 *   id,              // userRole 记录ID（保存时原样回传）
 *   userName,
 *   selectedCount,   // 当前已选角色数（弹窗标题"账户角色权限（N）"）
 *   regularRoles:     [ { roleId, roleName, description, checked } ],   // 普通角色 Tab
 *   coordinatorRoles: [ { roleId, roleName, description, checked } ]    // 协调员角色 Tab
 * }
 * </pre>
 */
@RestApi(baseUrl = "/coordinator")
public class PermissionRoleOptionsApi extends PermissionBaseApi {

    /** 日志标识：本接口所有日志统一前缀，便于线上按接口检索 */
    private static final String TAG = "PermissionRoleOptions";

    @RestMapping(value = "/permission/role-options", method = RequestMethod.POST)
    public String roleOptions(@RestBeanParam(name = "json") String body) {
        String resp;
        try {
            logIn(TAG, body);
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = checkPermissionChanged(TAG);
            if (guard != null) {
                return guard;
            }
            resp = doRoleOptions(body);
        } catch (Exception e) {
            logger.error(TAG + " 处理异常: " + e.getMessage(), e);
            resp = HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
        logOut(TAG, resp);
        return resp;
    }

    /** 业务逻辑（异常统一冒泡到 {@link #roleOptions(String)} 兜底，本方法不重复打异常日志） */
    private String doRoleOptions(String body) {
        if (body == null || body.isEmpty()) {
            return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
        }
        JSONObject req = JSON.parseObject(body);
        String id = req.getString("id");
        if (id == null || id.trim().isEmpty()) {
            return HttpUtil.fail("PARAM_ERROR", "id 不能为空").toJSONString();
        }

        // ===== 1. 查 userRole 主记录 =====
        String sql = "SELECT id, user__c, purchaseId__c, role__c FROM userRole__c WHERE id = '"
                + escapeSql(id.trim()) + "'";
        QueryResult<JSONObject> rt = queryWithLog(TAG, "userRole__c", sql);
        if (rt == null || rt.getRecords() == null || rt.getRecords().isEmpty()) {
            return HttpUtil.fail("NOT_FOUND", "用户权限记录不存在").toJSONString();
        }
        JSONObject record = (JSONObject) rt.getRecords().get(0);
        String userId = str(record.get("user__c"));
        String accountId = str(record.get("purchaseId__c"));
        Set<String> checkedRoleIds = new HashSet<>();
        parseMultiIds(checkedRoleIds, record.get("role__c"));

        // ===== 2. 查该账户 accountRole 已配置的角色 =====
        String arSql = "SELECT id, basicRole__c, accountRoleDes__c FROM accountRole__c "
                + "WHERE purchaseId__c = '" + escapeSql(accountId) + "' LIMIT 0, 200";
        QueryResult<JSONObject> arRt = queryWithLog(TAG, "accountRole__c", arSql);
        Map<String, String> roleDesRawMap = new HashMap<>(); // basicRoleId -> accountRoleDes__c raw
        if (arRt != null && arRt.getRecords() != null) {
            for (Object o : arRt.getRecords()) {
                JSONObject ar = (JSONObject) o;
                String basicRoleId = str(ar.get("basicRole__c"));
                if (!basicRoleId.isEmpty()) {
                    roleDesRawMap.put(basicRoleId, str(ar.get("accountRoleDes__c")));
                }
            }
        }

        JSONArray regularRoles = new JSONArray();
        JSONArray coordinatorRoles = new JSONArray();
        int selectedCount = 0; // 弹窗中已勾选的角色数（= 标题"账户角色权限（N）"）
        if (!roleDesRawMap.isEmpty()) {
            // ===== 3. 批量查角色名/类型 =====
            Set<String> roleIds = roleDesRawMap.keySet();
            String grSql = "SELECT id, name, roleType__c FROM generalRole__c WHERE id IN ("
                    + buildInClause(roleIds) + ")";
            QueryResult<JSONObject> grRt = queryWithLog(TAG, "generalRole__c", grSql);
            List<JSONObject> roles = new ArrayList<>();
            if (grRt != null && grRt.getRecords() != null) {
                for (Object o : grRt.getRecords()) {
                    roles.add((JSONObject) o);
                }
            }

            // ===== 4. 批量查权限点名称（所有 accountRoleDes 的并集）=====
            Set<String> allDesIds = new HashSet<>();
            for (String raw : roleDesRawMap.values()) {
                parseMultiIds(allDesIds, raw);
            }
            Map<String, String> desNameMap = queryRoleDesNames(allDesIds);

            // ===== 5. 分组组装 =====
            for (JSONObject gr : roles) {
                String rid = str(gr.get("id"));
                boolean checked = checkedRoleIds.contains(rid);
                if (checked) {
                    selectedCount++;
                }
                JSONObject item = new JSONObject();
                item.put("roleId", rid);
                item.put("roleName", str(gr.get("name")));
                item.put("description",
                        buildPermissionDescription(roleDesRawMap.get(rid), desNameMap));
                item.put("checked", checked);

                String roleType = parseFirstArrayItem(str(gr.get("roleType__c")));
                // 协调员判定统一用基类常量，避免三处口径不一致
                if (roleType.contains(ROLE_TYPE_COORDINATOR)
                        || ROLE_TYPE_COORDINATOR_EN.equalsIgnoreCase(roleType)) {
                    coordinatorRoles.add(item);
                } else {
                    regularRoles.add(item);
                }
            }
        }

        // ===== 6. 用户名 =====
        String userName = "";
        if (!userId.isEmpty()) {
            Set<String> uids = new HashSet<>();
            uids.add(userId);
            Map<String, JSONObject> userMap = queryUsers(uids);
            JSONObject ui = userMap.get(userId);
            if (ui != null) {
                userName = str(ui.get("name"));
            }
        }

        // ===== 7. 组装返回 =====
        JSONObject result = new JSONObject();
        result.put("id", str(record.get("id")));
        result.put("userName", userName);
        result.put("selectedCount", selectedCount);
        result.put("regularRoles", regularRoles);
        result.put("coordinatorRoles", coordinatorRoles);
        return HttpUtil.success(result).toJSONString();
    }
}
