package other.myse.coordinator.utils;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.exception.XsyHttpException;
import com.rkhd.platform.sdk.http.RkhdHttpClient;
import com.rkhd.platform.sdk.http.RkhdHttpData;
import com.rkhd.platform.sdk.http.handler.ResponseBodyHandlers;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 审批流相关工具（other.myse.coordinator.utils）
 *
 * <p>内联自 {@code other.myse.messagecenter.utils.ApprovalUtils}：coordinator 与 messagecenter
 * 是两个独立 codePackage，<b>跨包类不可见</b>，因此 coordinator 侧需要操作平台审批流时
 * 必须内联一份（只保留 approve/reject 场景需要的 agree/reject 能力）。</p>
 *
 * <p>两侧代码需保持同步；若 messagecenter 版本后续修改，这里要同步调整。</p>
 */
public class ApprovalUtils {

    /** 日志标识：本工具类所有日志统一前缀 */
    private static final String TAG = "ApprovalUtils";

    private static Logger logger = LoggerFactory.getLogger();

    /**
     * 审批操作入口：先取下一节点信息（preProcessor），再发起审批动作。
     *
     * @param entityApiKey 对象 apiKey，如 roleRequest__c
     * @param action       提交 submit / 同意 agree / 拒绝 reject / 撤回 withdraw
     * @param dataId       记录 id
     * @param option       审批意见（comment）
     */
    public static void approval(String entityApiKey, String action, Long dataId, String option) throws IOException {
        logger.info(TAG + " 审批操作开始 entityApiKey=" + entityApiKey + " action=" + action + " dataId=" + dataId);
        JSONObject processorJson = preProcessor(RkhdHttpClient.instance(), entityApiKey, action, dataId);
        approvalOperate(RkhdHttpClient.instance(), entityApiKey, action, dataId, null, processorJson, 0, option);
    }

    /**
     * 审批拒绝
     */
    public static void reject(String entityApiKey, Long dataId, int rejectStage, String option) throws IOException {
        JSONObject processorJson = preProcessor(RkhdHttpClient.instance(), entityApiKey, "reject", dataId);
        approvalOperate(RkhdHttpClient.instance(), entityApiKey, "reject", dataId, null, processorJson, rejectStage, option);
    }

    /**
     * preProcessor 获取流程下一节点信息
     */
    public static JSONObject preProcessor(RkhdHttpClient rkhdClient, String entityApiKey, String action, Long dataId) {
        JSONObject result = new JSONObject();
        if (action.equals("withdraw")) {
            action = "reject";
        }
        Long usertaskLogId = null;
        if (!"submit".equals(action)) {
            JSONObject historyJson = queryApprovalHistory(entityApiKey, dataId, "false");
            // 审批历史查询失败时返回 null，直接取值会 NPE，跳过 usertaskLogId 查找
            JSONArray historyArray = historyJson == null ? null : historyJson.getJSONArray("data");
            if (historyArray != null) {
                for (Object o : historyArray) {
                    JSONObject history = (JSONObject) o;
                    if (history.getInteger("usertaskInstStatus") == 1 && history.getInteger("status") == 5) {
                        usertaskLogId = history.getLong("id");
                        break;
                    }
                }
            }
        }

        RkhdHttpData rkhdDate = new RkhdHttpData();
        rkhdDate.setCall_type("POST");
        rkhdDate.setCallString("/rest/data/v2.0/creekflow/task/actions/preProcessor");
        JSONObject data = new JSONObject();
        data.put("entityApiKey", entityApiKey);
        data.put("action", action);
        data.put("dataId", dataId);
        if (usertaskLogId != null) {
            data.put("usertaskLogId", usertaskLogId);
        }
        JSONObject body = new JSONObject();
        body.put("data", data);
        rkhdDate.setBody(body.toString());
        try {
            String resJson = rkhdClient.execute(rkhdDate, ResponseBodyHandlers.ofString());
            JSONObject rJson = JSONObject.parseObject(resJson);
            if ("200".equals(rJson.getString("code"))) {
                JSONObject retJson = rJson.getJSONObject("data");
                retJson.put("usertaskLogId", usertaskLogId);
                logger.info(TAG + " 获取下一节点信息成功 entityApiKey=" + entityApiKey
                        + " action=" + action + " dataId=" + dataId);
                return retJson;
            } else {
                logger.error(TAG + " 获取下一节点信息出错 entityApiKey=" + entityApiKey + " action=" + action
                        + " dataId=" + dataId + " msg=" + rJson.getString("msg"));
            }
        } catch (XsyHttpException e) {
            logger.error(TAG + " 获取下一节点信息异常 entityApiKey=" + entityApiKey + " action=" + action
                    + " dataId=" + dataId + ": " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * approvalOperate 审批操作
     */
    public static void approvalOperate(RkhdHttpClient rkhdClient, String entityApiKey, String action, long dataId,
                                       Long submitter, JSONObject preProcessor, int rejectStage, String option) {
        RkhdHttpData rkhdDate = new RkhdHttpData();
        rkhdDate.setCall_type("POST");
        rkhdDate.setCallString("/rest/data/v2.0/creekflow/task");
        JSONObject data = new JSONObject();
        data.put("entityApiKey", entityApiKey);
        data.put("action", action);
        data.put("dataId", dataId);
        data.put("procdefId", preProcessor.getLong("procdefId"));
        data.put("usertaskLogId", preProcessor.getLong("usertaskLogId"));
        data.put("submitter", submitter);
        data.put("procInstId", preProcessor.getLong("procInstId"));
        data.put("nextTaskDefKey", preProcessor.getString("nextTaskDefKey"));
        data.put("comment", option);
        if (action.equals("reject")) {
            //默认取第一个
            data.put("targetTaskDefKey", preProcessor.getJSONArray("returnableTaskDefKeyNamePairs")
                    .getJSONObject(rejectStage).getString("key"));
        }
        if (preProcessor.containsKey("chooseApprover") && preProcessor.getJSONArray("chooseApprover") != null) {
            List<Long> chooseApprover = new ArrayList<>();
            for (Object obj : preProcessor.getJSONArray("chooseApprover")) {
                JSONObject approver = JSONObject.parseObject(obj.toString());
                chooseApprover.add(approver.getLong("id"));
            }
            data.put("nextAssignees", chooseApprover);
        }
        JSONObject body = new JSONObject();
        body.put("data", data);
        rkhdDate.setBody(body.toString());
        try {
            String resJson = rkhdClient.execute(rkhdDate, ResponseBodyHandlers.ofString());
            JSONObject rJson = JSONObject.parseObject(resJson);
            if ("200".equals(rJson.getString("code"))) {
                logger.info(TAG + " 审批操作成功 entityApiKey=" + entityApiKey + " action=" + action
                        + " dataId=" + dataId);
            } else {
                logger.error(TAG + " 审批操作出错 entityApiKey=" + entityApiKey + " action=" + action
                        + " dataId=" + dataId + " msg=" + rJson.getString("msg"));
            }
        } catch (XsyHttpException e) {
            logger.error(TAG + " 审批操作异常 entityApiKey=" + entityApiKey + " action=" + action
                    + " dataId=" + dataId + ": " + e.getMessage(), e);
        }
    }

    /**
     * 查询审批历史
     */
    public static JSONObject queryApprovalHistory(String entityApiKey, Long dataId, String stageFlg) {
        RkhdHttpData rkhdHttpData = RkhdHttpData.newBuilder()
                .callString("/rest/data/v2.0/creekflow/history/filter?entityApiKey=" + entityApiKey
                        + "&dataId=" + dataId + "&stageFlg=" + stageFlg)
                .callType("GET").build();
        JSONObject jsonObject = null;
        try {
            jsonObject = RkhdHttpClient.instance().execute(rkhdHttpData, ResponseBodyHandlers.ofJSON());
        } catch (XsyHttpException e) {
            logger.error(TAG + " 查询审批历史失败 entityApiKey=" + entityApiKey + " dataId=" + dataId, e);
        } catch (IOException e) {
            logger.error(TAG + " 查询审批历史失败 entityApiKey=" + entityApiKey + " dataId=" + dataId, e);
        }
        return jsonObject;
    }
}
