package other.myse.messagecenter.flows;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.creekflow.approvalevent.ApprovalEvent;
import com.rkhd.platform.sdk.creekflow.approvalevent.ApprovalEventRequest;
import com.rkhd.platform.sdk.creekflow.approvalevent.ApprovalEventResponse;
import com.rkhd.platform.sdk.data.model.MessageCenter__c;
import com.rkhd.platform.sdk.exception.ApiEntityServiceException;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.CustomConfigService;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色申请（roleRequest__c）审批流 - 审批通过后事件扩展（other.myse.messagecenter.flows）
 *
 * <p>挂载方式：平台审批流程的「审批通过后」扩展（ApprovalEvent），绑定对象 roleRequest__c。
 * 与 {@code RoleApplyApi}（提交申请）同属 messagecenter 模块，独立部署、不跨包依赖。</p>
 *
 * <p>行为：审批通过后给申请人（roleRequest__c.applyUser__c）写一条消息中心记录
 * （messageCenter__c，messageType__c=5 权限变更，isRead__c=2 未读），
 * 内容形如「【通知】您提交的角色权限申请已审批通过，角色从「业务员」变更为「协调员」」。</p>
 *
 * <p><b>容错：通知失败只记日志，不影响审批结果（始终返回 success=true）。</b>
 * 与老施耐德 other.schneider 的 ApprovalCompleted 不同——那边异常会 setSuccess(false)。</p>
 *
 * <p>注意：本类只发通知，不同步写 userRole__c.role__c（如需要"审批通过=自动同步角色"另行扩展）。</p>
 */
public class RoleRequestApprovalCompleted implements ApprovalEvent {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 本扩展只处理角色申请对象 */
    private static final String ENTITY_ROLE_REQUEST = "roleRequest__c";
    /** 消息类型：5=权限变更 */
    private static final int MESSAGE_TYPE_PERMISSION_CHANGE = 5;
    /** 是否已读：2=否（未读） */
    private static final int IS_READ_NO = 2;

    /**
     * messageCenter__c（消息中心）业务类型：优先读自定义配置 entityType.MessageCenter（与 MYSE-15 一致），
     */
    private static final Long MESSAGE_CENTER_ENTITY_TYPE = resolveMessageCenterEntityType();

    private static Long resolveMessageCenterEntityType() {
        try {
            Map<String, String> cfg = CustomConfigService.instance().getConfigSet("entityType");
            String v = (cfg == null) ? null : cfg.get("MessageCenter");
            if (v != null && !v.trim().isEmpty()) {
                return Long.valueOf(v.trim());
            }
        } catch (Exception e) {
            logger.warn("读取自定义配置 entityType.MessageCenter 失败，使用内置默认值", e);
        }
        return null;
    }

    @Override
    public ApprovalEventResponse execute(ApprovalEventRequest request) {
        ApprovalEventResponse response = new ApprovalEventResponse();
        Long dataId = request == null ? null : request.getDataId();
        try {
            notifyApplicant(dataId);
        } catch (Exception e) {
            // 通知属于旁路逻辑，异常不得阻断审批流程
            logger.error("RoleRequestApprovalCompleted 处理异常 dataId=" + dataId, e);
        }
        response.setSuccess(true);
        response.setMsg("success");
        return response;
    }

    /**
     * 审批通过后给申请人发消息中心通知。
     */
    private void notifyApplicant(Long roleRequestId) {
        // ===== 1. 查角色申请记录 =====
        String sql = "SELECT id, name, applyUser__c, account__c, status__c, roleBefore__c, roleAfter__c "
                + "FROM roleRequest__c WHERE id = " + roleRequestId;

        try {
            QueryResult<JSONObject> rt = XoqlService.instance().query(sql);
            JSONObject rr = (JSONObject) rt.getRecords().get(0);

            Long userId = toLong(rr.get("applyUser__c"));
            Long accountId = toLong(rr.get("account__c"));
            if (userId == null || accountId == null) {
                logger.warn("RoleRequestApprovalCompleted 申请人/账户为空，跳过通知 id=" + roleRequestId
                        + " applyUser=" + rr.get("applyUser__c") + " account=" + rr.get("account__c"));
                return;
            }

            // ===== 2. 变更前/变更后角色名 =====
            Set<String> beforeIds = parseIds(rr.get("roleBefore__c"));
            Set<String> afterIds = parseIds(rr.get("roleAfter__c"));
            Map<String, String> roleNameMap = queryRoleNames(beforeIds, afterIds);

            // ===== 3. 写消息中心 =====
            JSONObject data = new JSONObject();
            data.put("name", "【权限】角色申请审批结果");
            data.put("content__c", "【通知】您提交的角色权限申请已审批通过，角色从「"
                    + joinRoleNames(beforeIds, roleNameMap) + "」变更为「"
                    + joinRoleNames(afterIds, roleNameMap) + "」");
            data.put("msgType", MESSAGE_TYPE_PERMISSION_CHANGE);
            data.put("account__c", accountId);
            data.put("ownerId", userId);
            // 与 MYSE-15 一致：dataId__c 存对象 apiKey，objectKey__c 存记录标识（供前端跳转）
            data.put("dataId__c", "");
            data.put("objectKey__c", "myAccount");
            initMessageNotification(data);
            logger.info("RoleRequestApprovalCompleted 审批通过通知已发送 roleRequestId=" + roleRequestId
                    + " userId=" + userId + " accountId=" + accountId + " status=" + rr.get("status__c"));

            String msgSql="select id from messageCenter__c where isHandle__c=2 and todoType__c=3 and messageType__c=1 and dataId__c='"+rr.getString("name")+"'";
            logger.info("msgSql:"+msgSql);
            QueryResult<MessageCenter__c> res=XObjectService.instance().query(msgSql,true);
            logger.info("res:"+res.getRecords());
            if(!res.getRecords().isEmpty()){
                MessageCenter__c upMsg=res.getRecords().get(0);
                upMsg.setIsHandle__c(1);
                XObjectService.instance().update(upMsg,true);
            }
        } catch (ApiEntityServiceException e) {
            throw new RuntimeException(e);
        }



    }

    /** 批量查基础角色名称（变更前 + 变更后并集） */
    private Map<String, String> queryRoleNames(Set<String> beforeIds, Set<String> afterIds) {
        Set<String> all = new HashSet<>();
        all.addAll(beforeIds);
        all.addAll(afterIds);
        Map<String, String> map = new java.util.HashMap<>();
        if (all.isEmpty()) {
            return map;
        }
        try {
            String sql = "SELECT id, name FROM generalRole__c WHERE id IN (" + buildInClause(all) + ")";
            QueryResult<JSONObject> rt = XoqlService.instance().query(sql);
            if (rt != null && rt.getRecords() != null) {
                for (Object o : rt.getRecords()) {
                    JSONObject r = (JSONObject) o;
                    map.put(str(r.get("id")), str(r.get("name")));
                }
            }
        } catch (Exception e) {
            logger.warn("RoleRequestApprovalCompleted 批量查询角色名称失败", e);
        }
        return map;
    }

    /** 角色 id 集合 → "角色A、角色B"（查不到名字回显 id；空集合显示"无"） */
    private String joinRoleNames(Set<String> roleIds, Map<String, String> roleNameMap) {
        if (roleIds.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (String rid : roleIds) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            String name = roleNameMap.get(rid);
            sb.append((name == null || name.isEmpty()) ? rid : name);
        }
        return sb.toString();
    }

    /** 写消息中心记录（messageCenter__c），写法与 MYSE-15 initMessageNotification 一致 */
    private void initMessageNotification(JSONObject mainData) {
        MessageCenter__c messageCenter = new MessageCenter__c();
        messageCenter.setEntityType(MESSAGE_CENTER_ENTITY_TYPE);
        messageCenter.setName(mainData.getString("name")); // name 为必填字段
        messageCenter.setAccount__c(mainData.getLong("account__c"));
        messageCenter.setPublishTime__c(System.currentTimeMillis());
        messageCenter.setContent__c(mainData.getString("content__c"));
        messageCenter.setMessageType__c(mainData.getInteger("msgType"));
        messageCenter.setDataId__c(mainData.getString("dataId__c"));
        messageCenter.setObjectKey__c(mainData.getString("objectKey__c"));
        messageCenter.setOwnerId(mainData.getLong("ownerId"));
        messageCenter.setIsRead__c(IS_READ_NO); // 新通知默认未读
        logger.info("RoleRequestApprovalCompleted msgCenter:" + messageCenter);
        try {
            OperateResult res = XObjectService.instance().insert(messageCenter, true);
            logger.info("RoleRequestApprovalCompleted 通知写入结果: "
                    + (res != null ? String.valueOf(res.getSuccess()) + " err=" + res.getErrorMessage() : "null"));
        } catch (ApiEntityServiceException e) {
            logger.error("RoleRequestApprovalCompleted 通知写入消息中心失败: " + e.getMessage(), e);
        }
    }

    // ===================== 本包私有工具（不依赖 coordinator 基类） =====================

    /**
     * 解析多值关联字段的 id 集合（兼容 JSONArray / List / 逗号分隔字符串 / 单个数字）。
     */
    private Set<String> parseIds(Object raw) {
        Set<String> ids = new HashSet<>();
        if (raw == null) {
            return ids;
        }
        List<Object> items = new ArrayList<>();
        if (raw instanceof JSONArray) {
            items.addAll((JSONArray) raw);
        } else if (raw instanceof List) {
            items.addAll((List<?>) raw);
        } else {
            String s = str(raw);
            if (s.trim().startsWith("[")) {
                try {
                    JSONArray arr = JSON.parseArray(s);
                    if (arr != null) {
                        items.addAll(arr);
                    }
                } catch (Exception ignored) {
                    items.add(s);
                }
            } else {
                for (String p : s.split(",")) {
                    String t = p.trim();
                    if (!t.isEmpty()) {
                        items.add(t);
                    }
                }
            }
        }
        for (Object o : items) {
            if (o == null) {
                continue;
            }
            String v = str(o);
            if (o instanceof JSONObject) {
                v = str(((JSONObject) o).get("id"));
            }
            if (!v.isEmpty()) {
                ids.add(v);
            }
        }
        return ids;
    }

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

    private Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof JSONObject) {
            v = ((JSONObject) v).get("id");
        }
        if (v == null) {
            return null;
        }
        try {
            return Long.valueOf(str(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private String escapeSql(String s) {
        return s == null ? "" : s.replace("'", "''");
    }
}
