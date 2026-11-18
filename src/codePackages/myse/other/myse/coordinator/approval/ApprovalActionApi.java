package other.myse.coordinator.approval;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.data.model.RoleRequest__c;
import com.rkhd.platform.sdk.exception.ApiEntityServiceException;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.service.XObjectService;
import other.myse.coordinator.utils.ApprovalUtils;
import other.myse.coordinator.utils.HttpUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 待审批列表模块 - 用户权限审批操作（驳回/通过）（approval 子包）
 *
 * <p>对应页面：「用户权限审批」详情页底部两个按钮（驳回、通过）。
 * 详情数据走 {@code /coordinator/approval/detail}。</p>
 *
 * <pre>
 * 入参:
 *   id      String  必填  roleRequest__c 记录 ID
 *   action  String  必填  approve（通过） / reject（驳回）
 *   reason  String  可选  备注（仅日志，roleRequest__c 当前无 remark 字段不入库）
 *
 * 校验:
 *   当前 status__c 必须为 2 (审批中) 才允许审批；其他状态返回 STATE_ERROR（防重复审批）
 *
 * 状态码值（2026-09-01 老板拍板）：
 *   approve: status__c = 4 (同意)，approvalStatus = 3 (平台审批通过)
 *   reject : status__c = 3 (驳回)，approvalStatus = 2 (平台审批拒绝)
 *
 * 返回: { success: true, data: null }
 * </pre>
 *
 * <p><b>边界</b>：本接口仅更新 roleRequest__c 审批状态，不同步写入 userRole__c.role__c。
 * 若后续需"审批通过 = 自动同步用户角色"，再加扩展。</p>
 *
 * <p>迁移说明：自旧 {@code /coordinator/role-request/approval} 迁来
 * （api 包 RoleRequestApprovalApi），逻辑保持一致。旧接口暂留，模块全部切完后统一下线。</p>
 */
@RestApi(baseUrl = "/coordinator")
public class ApprovalActionApi extends ApprovalBaseApi {

    /** 业务状态码值 */
    private static final int STATUS_PENDING = 2;   // 审批中 / 待审批
    private static final int STATUS_REJECTED = 3;  // 驳回
    private static final int STATUS_APPROVED = 4;  // 同意

    /** 平台标准审批状态 */
    private static final int APPST_REJECTED = 2;
    private static final int APPST_APPROVED = 3;

    /** 日志标识：本接口所有日志统一前缀，便于线上按接口检索 */
    private static final String TAG = "ApprovalAction";

    @RestMapping(value = "/approval/action", method = RequestMethod.POST)
    public String action(@RestBeanParam(name = "json") String body) {
        String resp;
        try {
            logIn(TAG, body);
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = checkPermissionChanged(TAG);
            if (guard != null) {
                return guard;
            }
            resp = doAction(body);
        } catch (Exception e) {
            logger.error(TAG + " 处理异常: " + e.getMessage(), e);
            resp = HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
        logOut(TAG, resp);
        return resp;
    }

    /** 业务逻辑（异常统一冒泡到 {@link #action(String)} 兜底，本方法不重复打异常日志） */
    private String doAction(String body) throws ApiEntityServiceException {
        if (body == null || body.isEmpty()) {
            return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
        }
        JSONObject req = JSON.parseObject(body);
        String id = req.getString("id");
        String action = req.getString("action");
        String reason = req.getString("reason");
        JSONArray roleIdsArr = req.getJSONArray("roleIds");   // 变更后勾选角色（前端可改）

        // ===== 1. 必填校验 =====
        if (id == null || id.trim().isEmpty()) {
            return HttpUtil.fail("PARAM_ERROR", "id 不能为空").toJSONString();
        }
        if (action == null
                || (!"approve".equals(action.trim()) && !"reject".equals(action.trim()))) {
            return HttpUtil.fail("PARAM_ERROR", "action 必须为 approve 或 reject").toJSONString();
        }
        action = action.trim();
        long recordId;
        try {
            recordId = Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            return HttpUtil.fail("PARAM_ERROR", "id 格式不正确").toJSONString();
        }

        // ===== 2. 读主记录，校验当前状态 =====
        RoleRequest__c obj = new RoleRequest__c();
        obj.setId(recordId);
        obj = XObjectService.instance().get(obj, true);
        logger.info(TAG + " 读取 roleRequest id=" + recordId + " status=" + obj.getStatus__c());
        if (obj.getStatus__c() == null || obj.getStatus__c() != STATUS_PENDING) {
            logger.warn(TAG + " 审批被拒绝：当前状态非审批中 id=" + id + " status=" + obj.getStatus__c());
            return HttpUtil.fail("STATE_ERROR",
                    "当前状态非审批中（status__c=" + obj.getStatus__c() + "），不可审批").toJSONString();
        }

        // ===== 3. 更新 roleAfter__c（前端勾选的变更后角色）+ 审批状态，一次 update 落库 =====
        // 注意：roleAfter__c 是多值 lookup，必须用平台实体模型 setRoleAfter__c(Long[]) 写入
        //（对齐 RoleApplyApi 已验证的写法）；XObject.setAttribute 传 JSONArray 会导致平台内部 NPE。
        List<String> afterRoleIds = parseRoleIds(roleIdsArr);
        RoleRequest__c rr = new RoleRequest__c();
        rr.setId(recordId);
        if (!afterRoleIds.isEmpty()) {
            rr.setRoleAfter__c(toLongArray(afterRoleIds));
            logger.info(TAG + " 更新变更后角色 roleRequestId=" + id + " roleAfter__c=" + afterRoleIds);
        }
        int newStatus;
        int newApprovalStatus;
        if ("approve".equals(action)) {
            newStatus = STATUS_APPROVED;
            newApprovalStatus = APPST_APPROVED;
        } else {
            newStatus = STATUS_REJECTED;
            newApprovalStatus = APPST_REJECTED;
        }
        rr.setStatus__c(newStatus);
        rr.setApprovalStatus(newApprovalStatus);
        OperateResult result = XObjectService.instance().update(rr, true);
        if (result == null || !Boolean.TRUE.equals(result.getSuccess())) {
            String err = result == null ? "null"
                    : (result.getErrorMessage() != null ? result.getErrorMessage() : "更新失败");
            logger.error(TAG + " 审批角色申请失败 id=" + id + " action=" + action + " err=" + err);
            return HttpUtil.fail("UPDATE_FAIL", err).toJSONString();
        }
        logger.info(TAG + " 审批角色申请成功 id=" + id + " action=" + action
                + " status=" + newStatus + " approvalStatus=" + newApprovalStatus);

        // ===== 4. 额外调用平台审批流的「同意/拒绝」接口（参考 RoleApplyApi 的 submit 调用）=====
        boolean flowResult = false;
        try {
            ApprovalUtils.approval("roleRequest__c", "approve".equals(action) ? "agree" : "reject",
                    recordId, reason == null ? "" : reason);
            flowResult = true;
        } catch (Exception e) {
            // 平台审批流调用失败只记日志：自定义状态已落库，不回滚、不阻断返回结果
            logger.error(TAG + " 调用平台审批流失败 roleRequestId=" + id + " action=" + action, e);
        }

        JSONObject data = new JSONObject();
        data.put("flowResult", flowResult);
        return HttpUtil.success(data).toJSONString();
    }

    /**
     * 解析前端传来的「变更后勾选角色」数组 → 去重后的 id 列表（统一转字符串）。
     * 兼容元素为数字字符串 / 数字 / {"id":...} 对象三种形态。
     */
    private List<String> parseRoleIds(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null || arr.isEmpty()) {
            return out;
        }
        for (int i = 0; i < arr.size(); i++) {
            Object o = arr.get(i);
            if (o == null) {
                continue;
            }
            String v = (o instanceof JSONObject) ? ((JSONObject) o).getString("id") : o.toString();
            if (v != null) {
                v = v.trim();
                if (!v.isEmpty() && !out.contains(v)) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    /** 角色 id 列表 → Long[]（RoleRequest__c.setRoleAfter__c 多值关联字段用） */
    private Long[] toLongArray(List<String> ids) {
        Long[] out = new Long[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            try {
                out[i] = Long.valueOf(ids.get(i));
            } catch (NumberFormatException e) {
                out[i] = null;
            }
        }
        return out;
    }

}
