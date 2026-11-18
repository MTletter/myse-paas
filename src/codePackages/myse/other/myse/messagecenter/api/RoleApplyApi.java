package other.myse.messagecenter.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.data.model.RoleRequest__c;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;
import other.myse.messagecenter.utils.ApprovalUtils;
import other.myse.messagecenter.utils.HttpUtil;
import other.myse.messagecenter.utils.PermissionChangeGuard;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 我的账号 - 角色申请/创建（other.myse.messagecenter.api）
 *
 * <p>对应页面：我的账号 - 权限信息 - 角色申请/变更弹窗 - 「确定」按钮。提交后进入审批流。</p>
 *
 * <p>本接口原属 coordinator 包，按模块归属调整到 messagecenter（与 MyAccountApi 同一模块、
 * 同一 codePackage，独立部署，不跨包依赖 coordinator 的基类方法）。</p>
 *
 * <p>注意：弹窗可选角色数据由 {@code /coordinator/permission/role-options} 提供
 * （按账户查 accountRole__c 提供的可选角色 + 查 userRole__c 判定已勾选）。</p>
 *
 * <p>校验规则（与 PermissionSaveApi 对齐，弹窗确定 + 直接 API 调用都防呆）：
 * <ol>
 *   <li>roleIds 至少 1 个（申请必带变更内容）</li>
 *   <li>所选角色必须在该账户 accountRole__c 已配置范围内</li>
 *   <li>普通角色与协调员角色不能同时选择</li>
 * </ol>
 * </p>
 *
 * <p>提交后：
 * <ul>
 *   <li>写 roleRequest__c：userRole__c / applyUser__c / account__c / roleBefore__c / roleAfter__c / status__c=2（审批中） / name</li>
 *   <li>不直接改 userRole__c.role__c（改的动作由审批通过后触发）</li>
 * </ul>
 * </p>
 *
 * <pre>
 * 入参:
 *   userRoleId  String   必填  userRole 记录 ID（弹窗源行 id）
 *   roleIds     String[] 必填  选中的基础角色 ID 列表（至少 1 个）
 *
 * 返回: {
 *   id,        // roleRequest__c 新记录 id
 *   name,      // 申请单名称（回显）
 *   status     // "审批"
 * }
 * </pre>
 */
@RestApi(baseUrl = "/messagecenter")
public class RoleApplyApi {

    private static final Logger logger = LoggerFactory.getLogger();

    /**
     * roleRequest__c 对象的业务类型 ID。
     * ⚠️ 沙盒值 4422737787093974（与 roleRequest 已有记录一致），生产环境可能不同，
     *    部署时如报错「必填字段校验未通过：entityType」需核对。
     */
    private static final long ROLE_REQUEST_ENTITY_TYPE = 4422737787093974L;

    @RestMapping(value = "/role-apply/create", method = RequestMethod.POST)
    public String create(@RestBeanParam(name = "json") String body) {
        try {
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = PermissionChangeGuard.checkPermissionChanged("RoleApplyApi");
            if (guard != null) {
                return guard;
            }
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }
            JSONObject req = JSON.parseObject(body);
            String userRoleIdStr = req.getString("userRoleId");
            if (userRoleIdStr == null || userRoleIdStr.trim().isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "userRoleId 不能为空").toJSONString();
            }
            JSONArray roleIdsArr = req.getJSONArray("roleIds");
            if (roleIdsArr == null) {
                return HttpUtil.fail("PARAM_ERROR", "roleIds 不能为空").toJSONString();
            }

            Long userRoleId;
            try {
                userRoleId = Long.parseLong(userRoleIdStr.trim());
            } catch (NumberFormatException e) {
                return HttpUtil.fail("PARAM_ERROR", "userRoleId 格式不正确").toJSONString();
            }

            // ===== 选中角色去重 + 至少 1 个 =====
            Set<String> selectedIds = new HashSet<>();
            for (int i = 0; i < roleIdsArr.size(); i++) {
                String rid = str(roleIdsArr.get(i)).trim();
                if (!rid.isEmpty()) {
                    selectedIds.add(rid);
                }
            }
            if (selectedIds.isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "roleIds 至少 1 个").toJSONString();
            }

            // ===== 1. 查 userRole 主记录（拿 user/account/dimDepart/当前 role）=====
            String urSql = "SELECT id, user__c, purchaseId__c, dimDepart, role__c FROM userRole__c WHERE id = '"
                    + escapeSql(userRoleIdStr.trim()) + "'";
            QueryResult<JSONObject> urRt = XoqlService.instance().query(urSql);
            if (urRt == null || urRt.getRecords() == null || urRt.getRecords().isEmpty()) {
                return HttpUtil.fail("NOT_FOUND", "用户权限记录不存在 id=" + userRoleIdStr).toJSONString();
            }
            JSONObject ur = (JSONObject) urRt.getRecords().get(0);
            String userId = str(ur.get("user__c"));
            String accountId = str(ur.get("purchaseId__c"));
            String dimDepart = str(ur.get("dimDepart"));
            if (userId.isEmpty() || accountId.isEmpty()) {
                return HttpUtil.fail("DATA_ERROR", "用户权限记录异常 user/account 为空").toJSONString();
            }

            // ===== 2. 校验1：所选角色须在该账户已配置范围 =====
            String arSql = "SELECT basicRole__c FROM accountRole__c WHERE purchaseId__c = '"
                    + escapeSql(accountId) + "' LIMIT 0, 200";
            QueryResult<JSONObject> arRt = XoqlService.instance().query(arSql);
            Set<String> configuredIds = new HashSet<>();
            if (arRt != null && arRt.getRecords() != null) {
                for (Object o : arRt.getRecords()) {
                    collectId(configuredIds, ((JSONObject) o).get("basicRole__c"));
                }
            }
            for (String rid : selectedIds) {
                if (!configuredIds.contains(rid)) {
                    return HttpUtil.fail("VALIDATION_ERROR",
                            "所选角色包含该账户未配置的角色 roleId=" + rid).toJSONString();
                }
            }

            // ===== 3. 校验2：普通与协调员互斥 =====
            String typeSql = "SELECT id, roleType__c FROM generalRole__c WHERE id IN ("
                    + buildInClause(selectedIds) + ")";
            QueryResult<JSONObject> typeRt = XoqlService.instance().query(typeSql);
            boolean hasRegular = false;
            boolean hasCoordinator = false;
            if (typeRt != null && typeRt.getRecords() != null) {
                for (Object o : typeRt.getRecords()) {
                    String rt = str(((JSONObject) o).get("roleType__c"));
                    if (rt.contains("协调员") || "coordinator".equalsIgnoreCase(rt)) {
                        hasCoordinator = true;
                    } else {
                        hasRegular = true;
                    }
                }
            }
            if (hasRegular && hasCoordinator) {
                return HttpUtil.fail("VALIDATION_ERROR", "普通角色与协调员角色不能同时选择").toJSONString();
            }

            // ===== 4. 拼 name：{userName}-{accountName}-权限申请-yyyyMMddHHmmss =====
            String userName = queryUserName(userId);
            String accountName = queryAccountName(accountId);
            String ts = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
            String name = userName + "-" + accountName + "-权限申请-" + ts;
            if (name.startsWith("-") || name.length() < 5) {
                // 兜底：万一名字全空
                name = "权限申请-" + ts;
            }

            // ===== 5. roleBefore / roleAfter =====
            JSONArray roleBefore = new JSONArray();
            Object rawBefore = ur.get("role__c");
            if (rawBefore != null) {
                if (rawBefore instanceof JSONArray) {
                    roleBefore = (JSONArray) rawBefore;
                } else if (rawBefore instanceof List) {
                    JSONArray tmp = new JSONArray();
                    tmp.addAll((List<?>) rawBefore);
                    roleBefore = tmp;
                } else {
                    String s = str(rawBefore);
                    if (!s.isEmpty()) {
                        for (String p : s.split(",")) {
                            String t = p.trim();
                            if (!t.isEmpty()) {
                                roleBefore.add(t);
                            }
                        }
                    }
                }
            }

            JSONArray roleAfter = new JSONArray();
            for (String rid : selectedIds) {
                roleAfter.add(rid);
            }

            // ===== 6. 写 roleRequest__c =====
            RoleRequest__c obj = new RoleRequest__c();
            obj.setEntityType(ROLE_REQUEST_ENTITY_TYPE);
            obj.setName(name);
            obj.setUserRole__c(userRoleId);
            obj.setApplyUser__c(Long.parseLong(userId));
            obj.setAccount__c(Long.parseLong(accountId));
            obj.setRoleBefore__c(toLongArray(roleBefore));
            obj.setRoleAfter__c(toLongArray(roleAfter));
            obj.setStatus__c(2); // 审批中
            if (!dimDepart.isEmpty()) {
                try {
                    obj.setDimDepart(Long.parseLong(dimDepart));
                } catch (NumberFormatException ignore) {
                    // 兜底：dimDepart 非数字时跳过
                }
            }

            OperateResult result = XObjectService.instance().insert(obj);
            if (result == null || !Boolean.TRUE.equals(result.getSuccess())) {
                String err = result == null ? "null"
                        : (result.getErrorMessage() != null ? result.getErrorMessage() : "创建失败");
                logger.error("创建角色申请失败 userRoleId=" + userRoleIdStr + " err=" + err);
                return HttpUtil.fail("CREATE_FAILED", err).toJSONString();
            }
            Long newId = result.getDataId();

            //自动提交
            ApprovalUtils.approval("roleRequest__c",  "submit", newId, "");


            // ===== 7. 返回 =====
            JSONObject data = new JSONObject();
            data.put("id", newId);
            data.put("name", name);
            data.put("status", "审批");
            logger.info("RoleApply 成功 userRoleId=" + userRoleIdStr + " userId=" + userId
                    + " accountId=" + accountId + " roleAfter=" + selectedIds + " newId=" + newId);
            return HttpUtil.success(data).toJSONString();

        } catch (Exception e) {
            logger.error("RoleApply 异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    /** 取单条用户名（User.name） */
    private String queryUserName(String userId) {
        try {
            QueryResult<JSONObject> rt = XoqlService.instance().query(
                    "SELECT id, name FROM user WHERE id = '" + escapeSql(userId) + "' LIMIT 0, 1");
            if (rt != null && rt.getRecords() != null && !rt.getRecords().isEmpty()) {
                return str(((JSONObject) rt.getRecords().get(0)).get("name"));
            }
        } catch (Exception e) {
            logger.warn("queryUserName 失败 userId=" + userId);
        }
        return "";
    }

    /** 取单条账户名（account.accountName） */
    private String queryAccountName(String accountId) {
        try {
            QueryResult<JSONObject> rt = XoqlService.instance().query(
                    "SELECT id, accountName FROM account WHERE id = '" + escapeSql(accountId) + "' LIMIT 0, 1");
            if (rt != null && rt.getRecords() != null && !rt.getRecords().isEmpty()) {
                return str(((JSONObject) rt.getRecords().get(0)).get("accountName"));
            }
        } catch (Exception e) {
            logger.warn("queryAccountName 失败 accountId=" + accountId);
        }
        return "";
    }

    /** JSONArray 元素都是字符串形式 → 转 Long[]（roleRequest 多值关联字段用） */
    private Long[] toLongArray(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return new Long[0];
        }
        Long[] out = new Long[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            try {
                out[i] = Long.parseLong(str(arr.get(i)).trim());
            } catch (NumberFormatException ignore) {
                out[i] = null;
            }
        }
        return out;
    }

    // ===================== 辅助方法（本包私有，不依赖 coordinator 基类） =====================

    private String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private String escapeSql(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    /** 收集单值 id 到集合 */
    private void collectId(Set<String> target, Object value) {
        if (value != null) {
            String s = value.toString().trim();
            if (!s.isEmpty()) {
                target.add(s);
            }
        }
    }

    /** 构建 IN ('a','b') 子句 */
    private String buildInClause(Set<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append("'").append(escapeSql(id)).append("'");
        }
        return sb.toString();
    }

}
