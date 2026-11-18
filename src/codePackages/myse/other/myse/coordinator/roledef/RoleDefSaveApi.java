package other.myse.coordinator.roledef;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.data.model.AccountRole__c;
import com.rkhd.platform.sdk.data.model.MessageCenter__c;
import com.rkhd.platform.sdk.exception.ApiEntityServiceException;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.model.BatchOperateResult;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.model.OperateResult;
import other.myse.coordinator.api.UserPermissionBaseApi;
import other.myse.coordinator.utils.HttpUtil;

import java.util.*;

/**
 * 角色权限定义 - 角色功能权限编辑保存（other.myse.coordinator.roledef）
 *
 * <p>弹窗点「确定」，把勾选的功能权限点写回 accountRole__c.accountRoleDes__c。
 * 配套查询接口：`/coordinator/roledef/permissions`。</p>
 *
 * <pre>
 * 入参:
 *   accountRoleId  String   必填  accountRole__c 记录ID（弹窗查询返回的 accountRoleId）
 *   desIds         String[] 必填  选中的权限点ID列表（roleDes__c），传空数组=清空该角色全部权限
 *
 * <p>保存成功后，按「账户 + 角色」两个条件到 userRole__c 找相关用户，批量写 messageCenter__c
 * 发送权限变更通知（messageType__c=5 权限变更，ownerId=接收用户，account__c=所属账户，isRead__c=2 未读）。
 * 权限点集合未变化则不发；通知失败只记日志，不影响保存结果。</p>
 *
 * 返回: null
 * </pre>
 *
 * <p><b>日志规范（本类为样板，其余接口照此统一）：</b>
 * <ol>
 *   <li>入参一条：{@code RoleDefSave 入参: {...}}（打原始 body，不再打解析后的中间对象）；</li>
 *   <li>出参一条：{@code RoleDefSave 出参: {...}}（成功/失败都打，统一在出口处打印一次）；</li>
 *   <li>每次 XOQL：执行前打完整 SQL {@code RoleDefSave SQL: ...}，执行后打返回条数——
 *       统一走基类 {@code queryWithLog(TAG, desc, sql)}，异常在基类统一打 error；</li>
 *   <li>异常统一 {@code logger.error(msg, e)}，不打 printStackTrace（重复且容易丢上下文）；</li>
 *   <li>不打中间过程对象（entity.toString()）、不打全量记录，避免日志膨胀。</li>
 * </ol>
 * </p>
 */
@RestApi(baseUrl = "/coordinator")
public class RoleDefSaveApi extends UserPermissionBaseApi {

    /** 日志标识：本类所有日志统一前缀，便于线上按接口检索 */
    private static final String TAG = "RoleDefSave";

    /** 消息类型：5=权限变更（messageCenter__c.messageType__c 选项值） */
    private static final int MESSAGE_TYPE_PERMISSION_CHANGE = 5;
    /** 是否已读：2=否（未读） */
    private static final int IS_READ_NO = 2;
    /** 单批写入消息中心的最大条数（超过则分批） */
    private static final int MESSAGE_BATCH_SIZE = 200;

    /**
     * messageCenter__c（消息中心）业务类型：优先读自定义配置 entityType.MessageCenter（与 PermissionSave 一致），
     * 读不到时回退沙盒值 4409649230792705（生产环境部署需核对）。
     */
    private static final Long MESSAGE_CENTER_ENTITY_TYPE = resolveMessageCenterEntityType(TAG);

    // 消息中心业务类型：统一由基类 resolveMessageCenterEntityType(TAG) 解析

    /**
     * 接口入口：只负责 入参日志 / 异常兜底 / 出参日志，业务逻辑在 {@link #doSave(String)}。
     */
    @RestMapping(value = "/roledef/save", method = RequestMethod.POST)
    public String save(@RestBeanParam(name = "json") String body) {
        String resp;
        try {
            logIn(TAG, body);
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = checkPermissionChanged(TAG);
            if (guard != null) {
                return guard;
            }
            resp = doSave(body);
        } catch (Exception e) {
            logger.error(TAG + " 处理异常: " + e.getMessage(), e);
            resp = HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
        logOut(TAG, resp);
        return resp;
    }

    /**
     * 业务逻辑（异常统一冒泡到 {@link #save(String)} 兜底，本方法不重复打日志）。
     */
    private String doSave(String body) throws Exception {
        if (body == null || body.isEmpty()) {
            return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
        }
        JSONObject req = JSON.parseObject(body);
        String accountRoleId = req.getString("accountRoleId");
        if (accountRoleId == null || accountRoleId.trim().isEmpty()) {
            return HttpUtil.fail("PARAM_ERROR", "accountRoleId 不能为空").toJSONString();
        }

        JSONArray desIdsArr = req.getJSONArray("desIds");
        if (desIdsArr == null) {
            return HttpUtil.fail("PARAM_ERROR", "desIds 不能为空（清空权限请传空数组）").toJSONString();
        }

        // ===== 1. 校验 accountRole 记录存在 =====
        Long recordId;
        try {
            recordId = Long.parseLong(accountRoleId.trim());
        } catch (NumberFormatException e) {
            return HttpUtil.fail("PARAM_ERROR", "accountRoleId 格式不正确").toJSONString();
        }
        // 多查 purchaseId__c(账户) / basicRole__c(角色) / accountRoleDes__c(变更前权限点)：
        // 保存后要按 账户+角色 找 userRole__c 上的相关用户发通知，并用旧权限点做变更前后对比
        String chkSql = "SELECT id, purchaseId__c, basicRole__c, accountRoleDes__c FROM accountRole__c"
                + " WHERE id = " + recordId + " LIMIT 1";
        QueryResult<JSONObject> chkRt = queryWithLog(TAG, "accountRole__c", chkSql);
        if (chkRt == null || chkRt.getRecords() == null || chkRt.getRecords().isEmpty()) {
            return HttpUtil.fail("NOT_FOUND", "角色配置记录不存在 id=" + accountRoleId).toJSONString();
        }
        JSONObject accountRole = (JSONObject) chkRt.getRecords().get(0);
        String accountId = extractId(accountRole.get("purchaseId__c"));
        String roleId = extractId(accountRole.get("basicRole__c"));
        Set<String> beforeDesIds = new HashSet<>();
        parseMultiIds(beforeDesIds, accountRole.get("accountRoleDes__c"));

        // ===== 2. 清洗 desIds（去空、去重，跳过空元素避免数组里出现 null）=====
        List<Long> cleanList = new ArrayList<>();
        Set<String> afterDesIds = new LinkedHashSet<>();
        for (int i = 0; i < desIdsArr.size(); i++) {
            String did = str(desIdsArr.get(i)).trim();
            if (did.isEmpty()) {
                continue;
            }
            Long v = Long.valueOf(did);
            if (cleanList.contains(v)) {
                continue;
            }
            cleanList.add(v);
            afterDesIds.add(did);
        }
        Long[] cleanArr = cleanList.toArray(new Long[cleanList.size()]);

        // ===== 3. 写回 accountRole__c.accountRoleDes__c（多值关联字段传 Long[]）=====
        logger.info(TAG + " 更新 accountRole__c id=" + recordId + " 权限点数=" + cleanArr.length);
        AccountRole__c obj = new AccountRole__c();
        obj.setId(recordId);
        obj.setAccountRoleDes__c(cleanArr);
        OperateResult result = XObjectService.instance().update(obj, true);
        if (result == null || !Boolean.TRUE.equals(result.getSuccess())) {
            String err = result == null ? "null"
                    : (result.getErrorMessage() != null ? result.getErrorMessage() : "更新失败");
            logger.error(TAG + " 更新 accountRole 权限失败 id=" + accountRoleId + " err=" + err);
            return HttpUtil.fail("UPDATE_FAIL", err).toJSONString();
        }

        // ===== 4. 标记权限已变化（租户级缓存令牌）=====
        // 角色功能权限是【账户级】配置，改动会影响该账户下所有拥有该角色的用户，
        // 因此按账户批量打标记（userRole__c 里该账户的所有用户）。
        // 必须在数据库更新成功之后打标记。
        markPermissionChangedForAccount(TAG, accountId);

        // ===== 5. 功能权限变更通知：账户+角色 -> userRole__c 相关用户（批量写消息中心）=====
        notifyRolePermissionChange(accountRoleId, accountId, roleId, beforeDesIds, afterDesIds);

        return HttpUtil.success(null).toJSONString();
    }

    // ===================== 功能权限变更通知 =====================

    /**
     * 角色功能权限变更通知：
     * 以 accountRole__c 上的「账户 + 角色」为条件，到 userRole__c 找出持有该角色的相关用户，
     * 批量写 messageCenter__c（每条一个接收人）。
     * <p>与 PermissionSaveApi 同口径：权限点集合未变化则不发；通知失败只记日志，不影响保存结果。</p>
     */
    private void notifyRolePermissionChange(String accountRoleId, String accountId, String roleId,
                                            Set<String> beforeDesIds, Set<String> afterDesIds) {
        try {
            if (accountId.isEmpty() || roleId.isEmpty()) {
                logger.warn(TAG + " 跳过权限变更通知：账户/角色为空 accountRoleId=" + accountRoleId
                        + " accountId=" + accountId + " roleId=" + roleId);
                return;
            }
            if (beforeDesIds.equals(afterDesIds)) {
                logger.info(TAG + " 权限点未变化，不发通知 accountRoleId=" + accountRoleId
                        + " desIds数=" + afterDesIds.size());
                return;
            }

            // ===== 1. 账户 + 角色 -> 相关用户 =====
            List<Long> userIds = queryUserIdsByAccountAndRole(accountId, roleId);
            if (userIds.isEmpty()) {
                logger.info(TAG + " 未找到相关用户，不发通知 accountId=" + accountId + " roleId=" + roleId);
                return;
            }

            // ===== 2. 变更前后权限点名称（一次批量查）=====
            Set<String> allDesIds = new HashSet<>();
            allDesIds.addAll(beforeDesIds);
            allDesIds.addAll(afterDesIds);
            Map<String, String> desNameMap = queryRoleDesNames(allDesIds);
            Map<String, String> roleNameMap = queryRoleNames(Collections.singleton(roleId));
            String roleName = roleNameMap.get(roleId);
            if (roleName == null || roleName.isEmpty()) {
                roleName = roleId;
            }
            String before = joinNames(beforeDesIds, desNameMap, "，");
            String after = joinNames(afterDesIds, desNameMap, "，");
            String content = "【通知】您所属账户的「" + roleName + "」角色功能权限已变更，权限从「"
                    + before + "」变更为「" + after + "」";

            // ===== 3. 批量构造并批量写入消息中心 =====
            Long accountLong = Long.valueOf(accountId);
            long now = System.currentTimeMillis();
            List<MessageCenter__c> messages = new ArrayList<>();
            for (Long userId : userIds) {
                MessageCenter__c mc = new MessageCenter__c();
                mc.setEntityType(MESSAGE_CENTER_ENTITY_TYPE);
                mc.setName("【权限】角色权限变更通知");
                mc.setContent__c(content);
                mc.setMessageType__c(MESSAGE_TYPE_PERMISSION_CHANGE);
                mc.setAccount__c(accountLong);
                mc.setOwnerId(userId);
                mc.setPublishTime__c(now);
                mc.setIsRead__c(IS_READ_NO);   // 新通知默认未读
                // 与 PermissionSaveApi 一致：dataId__c 存对象 apiKey，objectKey__c 存前端跳转标识
                mc.setDataId__c("");
                mc.setObjectKey__c("myAccount");
                messages.add(mc);
            }
            batchInsertMessages(messages);

        } catch (Exception e) {
            logger.error(TAG + " 权限变更通知发送失败 accountRoleId=" + accountRoleId, e);
        }
    }

    /**
     * 账户 + 角色 -> userRole__c 上的用户 id 列表。
     * <p>XOQL 不支持多值关联字段直接 IN 过滤，故先按账户拉取 userRole__c，再本地按 role__c 是否包含该角色过滤。</p>
     */
    private List<Long> queryUserIdsByAccountAndRole(String accountId, String roleId) {
        List<Long> userIds = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        try {
            String sql = "SELECT id, user__c, role__c FROM userRole__c WHERE purchaseId__c = '"
                    + escapeSql(accountId) + "' LIMIT 0, 500";
            QueryResult<JSONObject> rt = queryWithLog(TAG, "userRole__c", sql);
            if (rt == null || rt.getRecords() == null) {
                return userIds;
            }
            for (Object o : rt.getRecords()) {
                JSONObject ur = (JSONObject) o;
                // 多值字段 role__c：包含该角色才算相关用户
                Set<String> roleIds = new HashSet<>();
                parseMultiIds(roleIds, ur.get("role__c"));
                if (!roleIds.contains(roleId)) {
                    continue;
                }
                String uid = extractId(ur.get("user__c"));
                if (uid.isEmpty()) {
                    continue;
                }
                Long u = Long.valueOf(uid);
                if (seen.add(u)) {
                    userIds.add(u);
                }
            }
        } catch (Exception e) {
            logger.error(TAG + " 解析相关用户失败 accountId=" + accountId + " roleId=" + roleId, e);
        }
        logger.info(TAG + " 账户=" + accountId + " 角色=" + roleId + " 命中相关用户 " + userIds.size() + " 人");
        return userIds;
    }

    /** 分批批量写入消息中心（失败只记日志，不抛出） */
    private void batchInsertMessages(List<MessageCenter__c> messages) {
        int total = messages.size();
        logger.info(TAG + " 批量写入消息中心 共 " + total + " 条");
        for (int from = 0; from < total; from += MESSAGE_BATCH_SIZE) {
            int to = Math.min(from + MESSAGE_BATCH_SIZE, total);
            List<MessageCenter__c> batch = messages.subList(from, to);
            try {
                BatchOperateResult res = XObjectService.instance().insert(batch, true, true);
                boolean ok = res != null && Boolean.TRUE.equals(res.getSuccess());
                logger.info(TAG + " 消息中心批量写入 " + from + "-" + to + " 结果=" + ok
                        + (res == null ? "" : " err=" + res.getErrorMessage()));
                if (!ok && res != null && res.getOperateResults() != null) {
                    for (OperateResult r : res.getOperateResults()) {
                        if (r != null && !Boolean.TRUE.equals(r.getSuccess())) {
                            logger.error(TAG + " 单条通知写入失败 err=" + r.getErrorMessage());
                        }
                    }
                }
            } catch (ApiEntityServiceException e) {
                logger.error(TAG + " 消息中心批量写入失败 " + from + "-" + to + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * 取关联/引用字段的 id：普通值直接转字符串；若 XOQL 返回 {"id":..} 对象则取 id。
     */
    private String extractId(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof JSONObject) {
            String id = ((JSONObject) value).getString("id");
            return id == null ? "" : id.trim();
        }
        String s = value.toString().trim();
        if (s.startsWith("{") && s.endsWith("}")) {
            try {
                JSONObject jo = JSON.parseObject(s);
                String id = jo.getString("id");
                return id == null ? "" : id.trim();
            } catch (Exception ignored) {
            }
        }
        return s;
    }
}
