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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限管理 - 用户权限详情（other.myse.coordinator.permission）
 *
 * <p>对应页面：用户权限详情页（基本信息 + 角色权限列表）。</p>
 *
 * <pre>
 * 入参:
 *   id  String  必填  userRole 记录 ID（列表行 id）
 *
 * 返回: {
 *   basicInfo: { userName, phone, email, accountName },
 *   rolePermissions: [ {
 *     roleId, roleName,
 *     permissions: ["价格货期查询","查看报价/订单"],   // 该账户此角色实际分配的权限点
 *     description: "价格货期查询，查看报价/订单"        // 拼接好的展示文本（前端可直接渲染"角色名-描述"行）
 *   } ]
 * }
 * </pre>
 */
@RestApi(baseUrl = "/coordinator")
public class PermissionDetailApi extends PermissionBaseApi {

    /** 日志标识：本接口所有日志统一前缀，便于线上按接口检索 */
    private static final String TAG = "PermissionDetail";

    @RestMapping(value = "/permission/detail", method = RequestMethod.POST)
    public String detail(@RestBeanParam(name = "json") String body) {
        String resp;
        try {
            logIn(TAG, body);
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = checkPermissionChanged(TAG);
            if (guard != null) {
                return guard;
            }
            resp = doDetail(body);
        } catch (Exception e) {
            logger.error(TAG + " 处理异常: " + e.getMessage(), e);
            resp = HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
        logOut(TAG, resp);
        return resp;
    }

    /** 业务逻辑（异常统一冒泡到 {@link #detail(String)} 兜底，本方法不重复打异常日志） */
    private String doDetail(String body) {
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

        // ===== 2. 基本信息 =====
        JSONObject basicInfo = new JSONObject();
        String userId = str(record.get("user__c"));
        String accountId = str(record.get("purchaseId__c"));

        if (!userId.isEmpty()) {
            Set<String> uids = new HashSet<>();
            uids.add(userId);
            Map<String, JSONObject> userMap = queryUsers(uids);
            JSONObject ui = userMap.get(userId);
            if (ui != null) {
                basicInfo.put("userName", str(ui.get("name")));
                basicInfo.put("phone", str(ui.get("phone")));
                basicInfo.put("email", str(ui.get("personalEmail")));
            }
        }
        if (!accountId.isEmpty()) {
            Set<String> aids = new HashSet<>();
            aids.add(accountId);
            Map<String, String> accMap = queryAccountNames(aids);
            basicInfo.put("accountName", accMap.getOrDefault(accountId, accountId));
        }

        // ===== 3. 角色权限列表（角色名 + 该账户实际分配的权限点）=====
        JSONArray rolePermissions = new JSONArray();
        Set<String> roleIds = new HashSet<>();
        parseMultiIds(roleIds, record.get("role__c"));

        if (!roleIds.isEmpty()) {
            Map<String, String> roleNameMap = queryRoleNames(roleIds);
            for (String rid : roleIds) {
                JSONObject rp = new JSONObject();
                rp.put("roleId", rid);
                rp.put("roleName", roleNameMap.getOrDefault(rid, rid));

                // accountRole(purchaseId + basicRole) -> accountRoleDes__c -> roleDes.name
                List<String> permNames = queryAccountRolePermissions(accountId, rid);
                rp.put("permissions", permNames);
                rp.put("description", String.join("，", permNames));

                rolePermissions.add(rp);
            }
        }

        // ===== 4. 组装返回 =====
        JSONObject result = new JSONObject();
        result.put("basicInfo", basicInfo);
        result.put("rolePermissions", rolePermissions);
        return HttpUtil.success(result).toJSONString();
    }
}
