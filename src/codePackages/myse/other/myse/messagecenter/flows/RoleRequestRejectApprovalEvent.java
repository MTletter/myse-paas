package other.myse.messagecenter.flows;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.creekflow.approvalevent.ApprovalEvent;
import com.rkhd.platform.sdk.creekflow.approvalevent.ApprovalEventRequest;
import com.rkhd.platform.sdk.creekflow.approvalevent.ApprovalEventResponse;
import com.rkhd.platform.sdk.creekflow.approvalevent.TaskInfo;
import com.rkhd.platform.sdk.data.model.RoleRequest__c;
import com.rkhd.platform.sdk.exception.ScriptBusinessException;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.service.XObjectService;
import other.myse.messagecenter.utils.ApprovalUtils;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 角色申请（roleRequest__c）审批 —— <b>执行拒绝动作</b>的审批流触发事件（ApprovalEvent）
 *
 * <p><b>挂载方式</b>：审批流 roleRequest__c 的「拒绝」动作扩展，scriptTrigger.xml 注册为：</p>
 * <pre>
 * &lt;approvalevent&gt;
 *     &lt;object&gt;roleRequest__c&lt;/object&gt;
 *     &lt;operate&gt;reject&lt;/operate&gt;      &lt;!-- 执行「拒绝」动作时触发（return=驳回/退回，是另一个动作）--&gt;
 *     &lt;position&gt;after&lt;/position&gt;     &lt;!-- 拒绝动作执行后 --&gt;
 *     &lt;class&gt;other.myse.messagecenter.flows.RoleRequestRejectApprovalEvent&lt;/class&gt;
 * &lt;/approvalevent&gt;
 * </pre>
 *
 * <p><b>用途</b>：与「自动提交申请」（RoleApplyApi 新建后自动
 * {@code ApprovalUtils.approval("roleRequest__c", "submit", id, "")}）对称，在审批流的拒绝时点
 * <b>真正替审批人执行拒绝</b> —— 走审批 OpenAPI，而不是手工 update 审批状态字段。</p>
 *
 * <p><b>上下文</b>（ApprovalEventRequest 提供，比规则事件丰富）：</p>
 * <ul>
 *   <li>{@code entityApiKey} / {@code dataId} —— 对象与记录；</li>
 *   <li>{@code usertaskLogId} —— 当前审批任务 id → <b>直接定位待处理任务</b>；</li>
 *   <li>{@code instanceId} / {@code processApiKey} —— 流程实例 / 流程定义；</li>
 *   <li>{@code taskInfo} —— 节点名称、<b>触发的按钮</b>（拒绝按钮）、前置节点等。</li>
 * </ul>
 *
 * <p><b>执行链路</b>：</p>
 * <ol>
 *   <li>读 roleRequest__c；</li>
 *   <li><b>状态闸门</b>：只有 status__c = 2（审批中）才处理，1 待提交 / 3 驳回 / 4 同意直接跳过；</li>
 *   <li><b>防重入</b>：同一记录 5 分钟内只处理一次（拒绝动作会让平台再次更新记录，
 *       不加窗口会自我递归）；</li>
 *   <li><b>流程闸门</b>：查 v2 审批历史，必须存在「当前待处理」节点（status = 5 且 usertaskInstStatus = 1）；</li>
 *   <li>用 v1 审批 OpenAPI 查审批任务拿到 {@code approvalId}，
 *       调 <b>{@code POST /data/v1/objects/approval/refuse}</b> 执行拒绝（body：approvalId + comments）；</li>
 *   <li>兜底：v1 OpenAPI 不可用时，退回 v2 内部审批动作接口
 *       {@code ApprovalUtils.approval("roleRequest__c", "reject", dataId, reason)}。</li>
 * </ol>
 *
 * <p><b>不手工改审批状态字段</b>（status__c / approvalStatus）—— 审批流状态由平台推进。</p>
 *
 * <p><b>异常策略</b>：事件是旁路逻辑，异常一律记日志并如实返回失败，<b>不阻断审批流</b>。</p>
 *
 * <p><b>通知分工</b>：本类<b>不</b>发消息中心通知 —— 通知申请人与关闭待办由
 * {@code RoleRequestApprovalRefuse}（ApprovalEvent，operate=return）负责，避免重复发消息。</p>
 *
 * <p>销售易 API 路径：非 HTTP 接口，由平台审批流触发。</p>
 */
public class RoleRequestRejectApprovalEvent implements ApprovalEvent {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 日志标识 */
    private static final String TAG = "RoleRequestRejectApprovalEvent";

    /** 本扩展只处理角色申请对象 */
    private static final String ENTITY_ROLE_REQUEST = "roleRequest__c";

    /** status__c：2 = 审批中（唯一允许执行拒绝的状态） */
    private static final int STATUS_APPROVING = 2;

    /** 审批历史：status = 5 表示当前待处理节点 */
    private static final int HISTORY_STATUS_PENDING = 5;

    /** 审批历史：usertaskInstStatus = 1 表示任务仍活跃（未处理） */
    private static final int HISTORY_TASK_ACTIVE = 1;

    /** 拒绝原因（写入审批意见 comments） */
    private static final String REJECT_REASON = "系统规则自动拒绝";

    /**
     * 防重入窗口：记录 id → 处理时间戳。
     * <p>拒绝动作会让平台再次更新记录，再次触发本事件；没有这个窗口会自我递归。</p>
     */
    private static final Map<Long, Long> HANDLED = new ConcurrentHashMap<>();

    /** 防重入窗口长度（5 分钟） */
    private static final long DEDUP_WINDOW_MS = 5 * 60 * 1000L;

    @Override
    public ApprovalEventResponse execute(ApprovalEventRequest request) throws ScriptBusinessException {
        ApprovalEventResponse response = new ApprovalEventResponse();
        response.setSuccess(false);
        response.setMsg("未执行");

        Long dataId = request == null ? null : request.getDataId();
        String entityApiKey = request == null ? null : request.getEntityApiKey();
        try {
            // ===== 0. 入参校验 =====
            if (dataId == null || dataId <= 0) {
                response.setSuccess(false);
                response.setMsg("dataId 为空，无法执行拒绝");
                logger.error(TAG + " dataId 为空，跳过");
                return response;
            }
            if (entityApiKey != null && !entityApiKey.isEmpty()
                    && !ENTITY_ROLE_REQUEST.equals(entityApiKey)) {
                response.setSuccess(false);
                response.setMsg("非角色申请对象，跳过: " + entityApiKey);
                logger.warn(TAG + " 非角色申请对象，跳过 entityApiKey=" + entityApiKey);
                return response;
            }

            // ===== 1. 上下文（节点 / 按钮 / 当前审批任务）=====
            Long usertaskLogId = request.getUsertaskLogId();
            TaskInfo taskInfo = request.getTaskInfo();
            logger.info(TAG + " 上下文 entityApiKey=" + entityApiKey + " dataId=" + dataId
                    + " usertaskLogId=" + usertaskLogId
                    + " instanceId=" + request.getInstanceId()
                    + " processApiKey=" + request.getProcessApiKey()
                    + " node=" + (taskInfo == null ? null : taskInfo.getNodeName())
                    + " nodeApiKey=" + (taskInfo == null ? null : taskInfo.getNodeApiKey())
                    + " button=" + (taskInfo == null ? null : taskInfo.getButtonName())
                    + " buttonApiKey=" + (taskInfo == null ? null : taskInfo.getButtonApiKey()));

            // ===== 2. 读角色申请记录 =====
            RoleRequest__c query = new RoleRequest__c();
            query.setId(dataId);
            RoleRequest__c rr = XObjectService.instance().get(query, true);
            if (rr == null) {
                response.setSuccess(false);
                response.setMsg("角色申请记录不存在: " + dataId);
                logger.error(TAG + " 角色申请记录不存在 id=" + dataId);
                return response;
            }

            // ===== 3. 状态闸门：只有「审批中」才执行拒绝 =====
            Integer status = rr.getStatus__c();
            if (status == null || status != STATUS_APPROVING) {
                response.setSuccess(true);
                response.setMsg("当前状态非审批中(status__c=" + status + ")，已跳过");
                logger.warn(TAG + " 当前状态非审批中，跳过拒绝 id=" + dataId
                        + " name=" + rr.getName() + " status=" + status);
                return response;
            }

            // ===== 4. 防重入 =====
            if (isHandledRecently(dataId)) {
                response.setSuccess(true);
                response.setMsg("近期已处理过，跳过");
                logger.info(TAG + " 近期已处理过，跳过 id=" + dataId + " name=" + rr.getName());
                return response;
            }

            // ===== 5. 流程闸门：定位当前待处理的审批任务与审批人 =====
            JSONObject history = ApprovalUtils.queryApprovalHistory(ENTITY_ROLE_REQUEST, dataId, "false");
            JSONObject task = findPendingTask(history, usertaskLogId);
            if (task == null) {
                response.setSuccess(true);
                response.setMsg("无当前待处理的审批任务，已跳过");
                logger.info(TAG + " 无待处理审批任务，跳过 id=" + dataId + " name=" + rr.getName());
                return response;
            }
            Long assigneeId = task.getLong("assigneeId");
            Long taskLogId = task.getLong("id");
            logger.info(TAG + " 定位到审批任务 id=" + dataId + " name=" + rr.getName()
                    + " assigneeId=" + assigneeId + " usertaskLogId=" + taskLogId
                    + " taskName=" + task.getString("usertaskInstName"));

            // ===== 6. 用 v1 审批 OpenAPI 执行拒绝 =====
            boolean done = false;
            String msg = "";


            // ===== 7. 兜底：退回 v2 内部审批动作接口执行拒绝 =====

            try {
                ApprovalUtils.approval(ENTITY_ROLE_REQUEST, "reject", dataId, REJECT_REASON);
                done = true;
                msg = msg + "；已回退 v2 审批动作接口执行拒绝";
                logger.warn(TAG + " 回退 v2 审批动作接口执行拒绝 id=" + dataId + " " + msg);
            } catch (Exception e) {
                logger.error(TAG + " 回退 v2 审批动作接口失败 id=" + dataId, e);
                msg = msg + "；回退 v2 接口也失败: " + e.getMessage();
            }


            if (done) {
                markHandled(dataId);
            }
            response.setSuccess(done);
            response.setMsg(msg);
            return response;

        } catch (Exception e) {
            // 事件异常不得影响审批流本身，记 error 后如实返回失败
            logger.error(TAG + " 执行拒绝异常 dataId=" + dataId, e);
            response.setSuccess(false);
            response.setMsg("执行拒绝异常: " + e.getMessage());
            return response;
        }
    }

    // ===================== 内部方法 =====================

    /**
     * 从 v2 审批历史中定位「当前待处理」的任务节点。
     *
     * <p>判据：status = 5（待处理）且 usertaskInstStatus = 1（任务活跃）；
     * 若上下文已给 usertaskLogId，优先取同一条记录。</p>
     *
     * @param history       v2 审批历史返回
     * @param usertaskLogId 上下文里的当前审批任务 id（可为 null）
     */
    private JSONObject findPendingTask(JSONObject history, Long usertaskLogId) {
        if (history == null) {
            return null;
        }
        JSONArray data = history.getJSONArray("data");
        if (data == null) {
            return null;
        }
        JSONObject fallback = null;
        for (int i = 0; i < data.size(); i++) {
            Object o = data.get(i);
            if (!(o instanceof JSONObject)) {
                continue;
            }
            JSONObject row = (JSONObject) o;
            Integer status = row.getInteger("status");
            Integer taskStatus = row.getInteger("usertaskInstStatus");
            boolean pending = status != null && status == HISTORY_STATUS_PENDING
                    && taskStatus != null && taskStatus == HISTORY_TASK_ACTIVE;
            if (!pending) {
                continue;
            }
            if (usertaskLogId != null && usertaskLogId.equals(row.getLong("id"))) {
                return row;
            }
            if (fallback == null) {
                fallback = row;
            }
        }
        return fallback;
    }

    /**
     * 定位审批 OpenAPI 需要的 approvalId。
     *
     * <p>优先 v1 OpenAPI {@code /data/v1/objects/approval/approvals} 按审批人查任务并按 dataId 匹配；
     * 查不到时退回审批历史里的 usertaskLogId（两者同源，大概率一致）。</p>
     */
    private Long findApprovalIdByOpenApi(Long assigneeId, Long dataId, Long usertaskLogId) {
        if (assigneeId != null) {
            JSONObject ret = ApprovalUtils.queryApprovalTasks(assigneeId, 1, 100);
            if (ret != null) {
                JSONArray records = ret.getJSONArray("records");
                if (records == null) {
                    records = ret.getJSONArray("record");
                }
                if (records != null) {
                    for (int i = 0; i < records.size(); i++) {
                        Object o = records.get(i);
                        if (!(o instanceof JSONObject)) {
                            continue;
                        }
                        JSONObject row = (JSONObject) o;
                        Long rowDataId = firstLong(row, "dataId", "dataID", "recordId", "objId");
                        Long approvalId = firstLong(row, "approvalId", "id", "taskId", "usertaskLogId");
                        if (approvalId == null) {
                            continue;
                        }
                        if (rowDataId != null && rowDataId.equals(dataId)) {
                            return approvalId;
                        }
                        if (rowDataId == null && usertaskLogId != null && usertaskLogId.equals(approvalId)) {
                            return approvalId;
                        }
                    }
                }
            }
        }
        if (usertaskLogId != null) {
            logger.warn(TAG + " 审批 OpenAPI 未匹配到 approvalId，退回使用 usertaskLogId=" + usertaskLogId);
            return usertaskLogId;
        }
        return null;
    }

    /** 标记已处理（进入防重入窗口） */
    private void markHandled(Long dataId) {
        HANDLED.put(dataId, System.currentTimeMillis());
        cleanUp();
    }

    /** 是否在防重入窗口内 */
    private boolean isHandledRecently(Long dataId) {
        Long ts = HANDLED.get(dataId);
        if (ts == null) {
            return false;
        }
        return System.currentTimeMillis() - ts < DEDUP_WINDOW_MS;
    }

    /** 清理过期的防重入记录，避免内存无限增长 */
    private void cleanUp() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Long, Long>> it = HANDLED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> e = it.next();
            if (now - e.getValue() > DEDUP_WINDOW_MS) {
                it.remove();
            }
        }
    }

    /** 取多个候选字段中第一个非空的 Long 值 */
    private Long firstLong(JSONObject row, String... keys) {
        for (String k : keys) {
            Object v = row.get(k);
            if (v == null) {
                continue;
            }
            try {
                if (v instanceof Number) {
                    return ((Number) v).longValue();
                }
                String s = v.toString().trim();
                if (!s.isEmpty()) {
                    return Long.parseLong(s);
                }
            } catch (Exception ignore) {
                // 非数字，跳过
            }
        }
        return null;
    }

    /** 判断 v1 OpenAPI 响应是否成功（code = 200，或无 code 但有返回体） */
    private boolean isOpenApiSuccess(JSONObject ret) {
        if (ret == null) {
            return false;
        }
        Object code = ret.get("code");
        if (code == null) {
            // 没有 code 字段：只要不是空对象就认为调用通了
            return !ret.isEmpty();
        }
        try {
            return Long.parseLong(code.toString().trim()) == 200L;
        } catch (Exception e) {
            return "200".equals(code.toString().trim());
        }
    }

    public static void main(String[] args) throws ScriptBusinessException {
        ApprovalEventRequest app=new  ApprovalEventRequest();
        app.setDataId(4562967192224747L);
        new RoleRequestRejectApprovalEvent().execute(app);
    }
}
