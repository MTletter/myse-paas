package other.myse.coordinator.permission;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.data.model.MessageCenter__c;
import com.rkhd.platform.sdk.exception.ApiEntityServiceException;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.model.XObject;
import com.rkhd.platform.sdk.service.XObjectService;
import other.myse.coordinator.utils.HttpUtil;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限管理 - 保存用户选择的角色（other.myse.coordinator.permission）
 *
 * <p>对应页面：权限变更弹窗 - 确定按钮。写回 userRole__c.role__c。</p>
 *
 * <p>校验规则：所选角色必须在该账户 accountRole__c 已配置的角色范围内（弹窗只展示已配置角色，
 * 此校验防止前端/调用方传入未配置角色）。</p>
 *
 * <p>保存成功后发送权限变更通知（参考 MYSE-15 状态变更通知）：
 * 变更前后的角色集合不一致时，写一条 messageCenter__c 记录给该用户
 * （messageType__c=5 权限变更，ownerId=被变更用户，account__c=所属账户，isRead__c=2 未读）。
 * 角色未变化则不发；通知失败只记日志，不影响保存结果。</p>
 *
 * <pre>
 * 入参:
 *   id       String   必填  userRole 记录 ID
 *   roleIds  String[] 必填  选中的基础角色ID列表（空数组=清空该用户所有角色）
 * </pre>
 */
@RestApi(baseUrl = "/coordinator")
public class PermissionSaveApi extends PermissionBaseApi {

    /** 日志标识：本接口所有日志统一前缀，便于线上按接口检索 */
    private static final String TAG = "PermissionSave";

    /** 消息类型：5=权限变更（messageCenter__c.messageType__c 选项值） */
    private static final int MESSAGE_TYPE_PERMISSION_CHANGE = 5;
    /** 是否已读：2=否（未读） */
    private static final int IS_READ_NO = 2;

    /**
     * messageCenter__c（消息中心）业务类型：优先读自定义配置 entityType.MessageCenter（与 MYSE-15 一致），
     * 读不到时回退沙盒值 4409649230792705（生产环境部署需核对）。
     */
    private static final Long MESSAGE_CENTER_ENTITY_TYPE = resolveMessageCenterEntityType(TAG);

    // 消息中心业务类型：统一由基类 resolveMessageCenterEntityType(TAG) 解析

    @RestMapping(value = "/permission/save", method = RequestMethod.POST)
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

    /** 业务逻辑（异常统一冒泡到 {@link #save(String)} 兜底，本方法不重复打异常日志） */
    private String doSave(String body) throws ApiEntityServiceException {
        if (body == null || body.isEmpty()) {
            return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
        }
        JSONObject req = JSON.parseObject(body);
        String id = req.getString("id");
        if (id == null || id.trim().isEmpty()) {
            return HttpUtil.fail("PARAM_ERROR", "id 不能为空").toJSONString();
        }
        JSONArray roleIdsArr = req.getJSONArray("roleIds");
        if (roleIdsArr == null) {
            return HttpUtil.fail("PARAM_ERROR", "roleIds 不能为空").toJSONString();
        }

        Long recordId;
        try {
            recordId = Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            return HttpUtil.fail("PARAM_ERROR", "id 格式不正确").toJSONString();
        }

        // ===== 0. 查 userRole 记录拿账户/用户/当前角色（变更前角色用于权限变更通知）=====
        String urSql = "SELECT id, user__c, purchaseId__c, role__c FROM userRole__c WHERE id = '"
                + escapeSql(id.trim()) + "'";
        QueryResult<JSONObject> urRt = queryWithLog(TAG, "userRole__c", urSql);
        if (urRt == null || urRt.getRecords() == null || urRt.getRecords().isEmpty()) {
            return HttpUtil.fail("NOT_FOUND", "用户权限记录不存在 id=" + id).toJSONString();
        }
        JSONObject ur = (JSONObject) urRt.getRecords().get(0);
        String accountId = str(ur.get("purchaseId__c"));
        String userId = str(ur.get("user__c"));
        Set<String> beforeRoleIds = new HashSet<>();
        parseMultiIds(beforeRoleIds, ur.get("role__c"));

        // ===== 选中角色去重 =====
        Set<String> selectedIds = new HashSet<>();
        for (int i = 0; i < roleIdsArr.size(); i++) {
            String rid = str(roleIdsArr.get(i)).trim();
            if (!rid.isEmpty()) {
                selectedIds.add(rid);
            }
        }

        if (!selectedIds.isEmpty()) {
            // ===== 校验1：必须在该账户已配置角色范围内 =====
            String arSql = "SELECT basicRole__c FROM accountRole__c WHERE purchaseId__c = '"
                    + escapeSql(accountId) + "' LIMIT 0, 200";
            QueryResult<JSONObject> arRt = queryWithLog(TAG, "accountRole__c(已配置角色)", arSql);
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
            // 注：原「普通角色与协调员角色不能同时选择」的校验已下线（业务放开），
            // 相关代码已删除；若需恢复，参考 git 历史。角色分组展示在 PermissionRoleOptionsApi。
        }

        // ===== 更新 userRole__c.role__c（多值关联字段传 JSONArray 对象）=====
        List<XObject> records = XObjectService.instance().getByIds("userRole__c", Arrays.asList(recordId));
        if (records == null || records.isEmpty()) {
            return HttpUtil.fail("NOT_FOUND", "用户权限记录不存在 id=" + id).toJSONString();
        }
        XObject obj = records.get(0);
        obj.setAttribute("role__c", roleIdsArr);
        OperateResult result = XObjectService.instance().update(obj);
        if (result == null || !Boolean.TRUE.equals(result.getSuccess())) {
            String err = result == null ? "null"
                    : (result.getErrorMessage() != null ? result.getErrorMessage() : "更新失败");
            logger.error(TAG + " 保存用户角色失败 id=" + id + " err=" + err);
            return HttpUtil.fail("UPDATE_FAIL", err).toJSONString();
        }
        logger.info(TAG + " 保存用户角色成功 id=" + id + " 角色数=" + selectedIds.size());

        // ===== 标记该用户权限已变化（租户级缓存令牌）=====
        // 必须在数据库更新成功之后打标记：否则改失败也会通知前端"权限变了"，
        // 而标记失败则该用户收不到变更通知（error 日志可见）。
        Long changedUserId = toLong(userId);
        if (changedUserId != null) {
            markPermissionChanged(TAG, changedUserId);
        } else {
            logger.error(TAG + " 权限已保存但无法解析 userId，未能打权限变更标记, userId=" + userId
                    + ", userRoleId=" + id);
        }

        // ===== 权限变更通知（参考 MYSE-15 状态变更通知：写 messageCenter__c）=====
        notifyPermissionChange(id, userId, accountId, beforeRoleIds, selectedIds);

        return HttpUtil.success(null).toJSONString();
    }

    /**
     * 权限变更通知：角色集合发生变化时，给被变更权限的用户写一条消息中心记录（messageCenter__c）。
     * <p>口径参考 MYSE-15 订单状态变更通知：只在「变更前/变更后」不一致时发送。</p>
     * <p>通知失败只记日志，不影响保存结果。</p>
     */
    private void notifyPermissionChange(String userRoleId, String userId, String accountId,
                                        Set<String> beforeRoleIds, Set<String> afterRoleIds) {
        try {
            if (userId.isEmpty() || accountId.isEmpty()) {
                logger.warn(TAG + " 跳过权限变更通知：user/account 为空 userRoleId=" + userRoleId);
                return;
            }
            if (beforeRoleIds.equals(afterRoleIds)) {
                logger.info(TAG + " 角色未变化，不发通知 userRoleId=" + userRoleId + " 角色数=" + afterRoleIds.size());
                return;
            }

            // 变更前后的角色名（一次批量查）
            Set<String> allRoleIds = new HashSet<>();
            allRoleIds.addAll(beforeRoleIds);
            allRoleIds.addAll(afterRoleIds);
            Map<String, String> roleNameMap = queryRoleNames(allRoleIds);

            String before = joinNames(beforeRoleIds, roleNameMap, "、");
            String after = joinNames(afterRoleIds, roleNameMap, "、");

            JSONObject data = new JSONObject();
            data.put("name", "【权限】账户权限变更通知");
            data.put("content__c", "【通知】您的账户权限已变更，角色从「" + before + "」变更为「" + after + "」");
            data.put("msgType", MESSAGE_TYPE_PERMISSION_CHANGE);
            data.put("account__c", Long.valueOf(accountId));
            data.put("ownerId", Long.valueOf(userId));
            // 与 MYSE-15 一致：dataId__c 存对象 apiKey，objectKey__c 存记录标识（供前端跳转）
            data.put("dataId__c", "");
            data.put("objectKey__c","myAccount");
            initMessageNotification(data);
        } catch (Exception e) {
            logger.error(TAG + " 权限变更通知发送失败 userRoleId=" + userRoleId, e);
        }
    }

    /** 写消息中心记录（messageCenter__c），写法与 MYSE-15 initMessageNotification 一致。 */
    private void initMessageNotification(JSONObject mainData) {
        MessageCenter__c messageCenter = new MessageCenter__c();
        messageCenter.setEntityType(MESSAGE_CENTER_ENTITY_TYPE);
        messageCenter.setName(mainData.getString("name"));
        messageCenter.setAccount__c(mainData.getLong("account__c"));
        messageCenter.setPublishTime__c(System.currentTimeMillis());
        messageCenter.setContent__c(mainData.getString("content__c"));
        messageCenter.setMessageType__c(mainData.getInteger("msgType"));
        messageCenter.setDataId__c(mainData.getString("dataId__c"));
        messageCenter.setObjectKey__c(mainData.getString("objectKey__c"));
        messageCenter.setOwnerId(mainData.getLong("ownerId"));
        messageCenter.setIsRead__c(IS_READ_NO); // 新通知默认未读
        try {
            OperateResult res = XObjectService.instance().insert(messageCenter, true);
            logger.info(TAG + " 权限变更通知写入结果: success=" + (res != null ? res.getSuccess() : "null")
                    + " ownerId=" + mainData.getLong("ownerId")
                    + (res == null ? "" : " err=" + res.getErrorMessage()));
        } catch (ApiEntityServiceException e) {
            logger.error(TAG + " 权限变更通知写入消息中心失败: " + e.getMessage(), e);
        }
    }
}
