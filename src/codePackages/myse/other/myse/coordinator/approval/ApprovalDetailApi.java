package other.myse.coordinator.approval;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.model.QueryResult;
import other.myse.coordinator.utils.HttpUtil;

import java.util.*;

/**
 * 待审批列表模块 - 用户权限审批详情（approval 子包）
 *
 * <p>对应页面：账户权限管理 - 待审批列表 - 去处理 -「用户权限审批」页：
 * 基本信息（姓名/电子邮箱/电话号/申请账户）+ 变更前/变更后角色权限列表。
 * 页面底部驳回/通过按钮走 {@code /coordinator/approval/action}。</p>
 *
 * <pre>
 * 入参:
 *   id  String  必填  roleRequest__c 记录 ID
 *
 * 返回: {
 *   basicInfo: {
 *     userName:    String,  // 申请人姓名
 *     email:       String,  // 电子邮箱(user.personalEmail)
 *     phone:       String,  // 电话（优先 roleRequest.mobile__c 快照，回退 user.phone）
 *     accountName: String,  // 申请账户
 *     applyDate:   String,  // 申请日期 yyyy-MM-dd
 *     status:      Object,  // 业务状态显示名（审批/驳回/同意/待提交）
 *     editable:    Boolean  // 是否可审批（status__c=2 审批中才为 true），前端控制按钮显隐
 *   },
 *   before: [ { roleId, roleName, description } ],  // 变更前（description=该账户实际分配的权限点）
 *   after:  [ { roleId, roleName, description } ]   // 变更后
 * }
 * </pre>
 *
 * <p><b>权限描述口径（2026-09-04 起与 permission 模块统一）</b>：
 * 取 {@code accountRole__c.accountRoleDes__c}（该账户实际分配），
 * 账户未配置该角色时回退 {@code generalRole.customItem2__c}（角色模板默认池）。
 * 旧版 RoleRequestDetailApi 仅用角色模板池，本接口已修正。</p>
 */
@RestApi(baseUrl = "/coordinator")
public class ApprovalDetailApi extends ApprovalBaseApi {

    /** 日志标识：本接口所有日志统一前缀，便于线上按接口检索 */
    private static final String TAG = "ApprovalDetail";

    @RestMapping(value = "/approval/detail", method = RequestMethod.POST)
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

        // ===== 1. 查 roleRequest 主记录 =====
        String sql = "SELECT id, applyUser__c, account__c, roleBefore__c, roleAfter__c, "
                + "status__c, mobile__c, createdAt "
                + "FROM roleRequest__c WHERE id = '" + escapeSql(id.trim()) + "'";
        QueryResult<JSONObject> rt = queryWithLog(TAG, "roleRequest__c", sql);
        if (rt == null || rt.getRecords() == null || rt.getRecords().isEmpty()) {
            return HttpUtil.fail("NOT_FOUND", "角色申请记录不存在").toJSONString();
        }
        JSONObject record = (JSONObject) rt.getRecords().get(0);

        // ===== 2. 基本信息 =====
        Set<String> userIds = new HashSet<>();
        Set<String> accountIds = new HashSet<>();
        collectId(userIds, record.get("applyUser__c"));
        collectId(accountIds, record.get("account__c"));

        String userId = str(record.get("applyUser__c"));
        String accountId = str(record.get("account__c"));

        Map<String, JSONObject> userMap = queryUsers(userIds);
        Map<String, String> accountNameMap = queryAccountNames(accountIds);

        JSONObject basicInfo = new JSONObject();
        JSONObject ui = userMap.get(userId);
        basicInfo.put("userName", ui != null ? str(ui.get("name")) : "");
        basicInfo.put("email", ui != null ? str(ui.get("personalEmail")) : "");
        String snapMobile = str(record.get("mobile__c"));
        basicInfo.put("phone", !snapMobile.isEmpty()
                ? snapMobile : (ui != null ? str(ui.get("phone")) : ""));
        basicInfo.put("accountName", accountNameMap.getOrDefault(accountId, accountId));
        basicInfo.put("applyDate", formatDate(record.get("createdAt")));
        String statusName = firstArrayItem(record.get("status__c"));
        basicInfo.put("status", statusName);
        // status__c=2(审批中，显示名"审批") 才可操作，供前端控制驳回/通过按钮显隐
        basicInfo.put("editable", "审批".equals(statusName));

        // ===== 3. 变更前/后角色（权限描述=账户实际分配口径）=====
        Set<String> beforeRoleIds = new LinkedHashSet<>();
        Set<String> afterRoleIds = new LinkedHashSet<>();
        parseMultiIds(beforeRoleIds, record.get("roleBefore__c"));
        parseMultiIds(afterRoleIds, record.get("roleAfter__c"));

        Set<String> allRoleIds = new HashSet<>();
        allRoleIds.addAll(beforeRoleIds);
        allRoleIds.addAll(afterRoleIds);

        Map<String, String> roleNameMap = queryRoleNames(allRoleIds);
        Map<String, String> desMap = queryAccountRoleDescriptions(accountId, allRoleIds);

        JSONObject result = new JSONObject();
        result.put("basicInfo", basicInfo);
        result.put("before", buildRoleArray(beforeRoleIds, roleNameMap, desMap));
        result.put("after", buildRoleArray(afterRoleIds, roleNameMap, desMap));
        return HttpUtil.success(result).toJSONString();
    }

    /**
     * 组装角色数组 [{roleId, roleName, description}]，保持申请单中的原始顺序。
     */
    private JSONArray buildRoleArray(Set<String> roleIds, Map<String, String> roleNameMap,
                                     Map<String, String> desMap) {
        JSONArray arr = new JSONArray();
        for (String rid : roleIds) {
            JSONObject item = new JSONObject();
            item.put("roleId", rid);
            item.put("roleName", roleNameMap.getOrDefault(rid, rid));
            item.put("description", desMap.getOrDefault(rid, ""));
            arr.add(item);
        }
        return arr;
    }

    // picklist 在 XOQL 下返回显示名数组（如 ["审批"]），取首个元素；已统一由基类 firstArrayItem(Object) 实现。
    // 码值对照：1待提交/2审批/3驳回/4同意。
}
