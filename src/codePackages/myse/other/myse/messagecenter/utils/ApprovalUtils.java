package other.myse.messagecenter.utils;

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
 * 审批流相关代码
 */
public class ApprovalUtils {

    private static Logger logger = LoggerFactory.getLogger();

    /**
     * 提交审批流程
     *
     * @param
     */
    public static void submitApproval(Long dataId, String entityApi, Long ownerId) throws Exception {
        JSONObject body = getBodyData("submit", entityApi, ownerId, dataId, "", 0);
        request(body);
    }

    /**
     * @param requestType  审批操作类型，提交，审批通过
     * @param entityApiKey 实体
     * @param ownerId      所有人
     * @param dataId       数据
     * @param comment      备注
     * @return
     * @throws Exception
     */
    private static JSONObject getBodyData(String requestType,
                                          String entityApiKey,
                                          Long ownerId,
                                          Long dataId,
                                          String comment,
                                          long entityType) throws Exception {
        JSONObject params = new JSONObject();
        params.put("action", requestType);
        params.put("comment", comment);
        params.put("entityApiKey", entityApiKey);
        params.put("dataId", dataId);
        params.put("srcTaskDefKey", null);
        params.put("srcProcInstId", null);
        params.put("srcProcType", null);
        switch (requestType) {
            case "submit":
                submitParams(entityApiKey, params, dataId, ownerId);
                break;
        }
        JSONObject bodyData = new JSONObject();
        bodyData.put("data", params);
        return bodyData;
    }

    /**
     * submitParams 参数拼接
     *
     * @param entityApiKey
     * @param params
     * @throws Exception
     */
    private static void submitParams(String entityApiKey, JSONObject params, Long dataId, Long submitter) throws Exception {

        //流程预处理
        JSONObject definitionJson = preProcessor(entityApiKey, dataId);

        //procdefId 否 Long 流程定义ID submit  时必须
        params.put("procdefId", definitionJson.getLongValue("procdefId"));
        params.put("submitter", submitter);  //提交人，submit 必填
        params.put("nextTaskDefKey", definitionJson.getString("nextTaskDefKey"));
        if (definitionJson.getJSONArray("chooseApprover") != null) {
            JSONArray jsonArray = new JSONArray();
            JSONArray chooseApprover = definitionJson.getJSONArray("chooseApprover");
            JSONObject parse = (JSONObject) JSONObject.parse(chooseApprover.get(0).toString());
            jsonArray.add(parse.getLongValue("id"));
            params.put("nextAssignees", jsonArray);
        }
    }

    /**
     * 流程预处理
     *
     * @param entityApiKey
     * @return
     * @throws Exception
     */
    private static JSONObject preProcessor(String entityApiKey, Long dataId) throws Exception {


        String url = "/rest/data/v2.0/creekflow/task/actions/preProcessor";
        JSONObject data = new JSONObject();
        JSONObject jsonObject = new JSONObject();
        data.put("data", jsonObject);
        jsonObject.put("entityApiKey", entityApiKey);
        jsonObject.put("action", "submit");
        jsonObject.put("dataId", dataId);
        JSONObject ret = post(url, data.toString());
        logger.info("查询审批下一步结果：" + ret);
        if (ret.containsKey("code") && ret.getString("code").equals("200")) {
            return ret.getJSONObject("data");
        } else {
            throw new Exception(ret.toJSONString());
        }
    }

    /**
     * 审批操作
     *
     * @param body
     * @throws Exception Config.SER_API_URL +
     */
    private static void request(JSONObject body) throws Exception {
        JSONObject ret = post("/rest/data/v2.0/creekflow/task", body.toString());
        logger.info("审批自动提交结果：" + ret);
        if (ret.getLongValue("code") != 200) {
            logger.info("Fail --> 自动提交，操作失败");
        } else {
            logger.info("Success--> 自动提交，操作成功");
        }
    }

    /***
     * CommonData post
     * @param url
     * @param data
     * @return
     */
    public static JSONObject post(String url, String data) {
        try {
            logger.info("request body(post) :" + data);
            RkhdHttpData rkhdHttpData = RkhdHttpData.newBuilder().callString(url).callType("POST")
                    .header("Content-Type", "application/json")
                    .body(data).build();

            JSONObject jsonObject = RkhdHttpClient.instance().execute(rkhdHttpData,
                    ResponseBodyHandlers.ofJSON());
            logger.info("CommonData post response :" + jsonObject.toJSONString());

            if (jsonObject.containsKey("code") && jsonObject.getString("code").equals("1020008")) {
                //todo token 过期问题的处理
                //JSONObject js = postTryAgain(url, data);
                //return js;
            } else {
                return jsonObject;
            }

        } catch (Exception e) {
            logger.error("请求异常-->" + e.getMessage());
        }
        return null;
    }

    /**
     * 获取流程实例
     * @return
     */
    public static JSONObject getProcessFilter(RkhdHttpClient rkhdClient,String xObject) throws XsyHttpException {
        return getProcessFilter(rkhdClient,xObject,null);
    }
    public static JSONObject getProcessFilter(RkhdHttpClient rkhdClient,String xObject,Long bizTypeId) throws XsyHttpException {
        String url = "/rest/metadata/v2.0/creekflow/processDefinitions/filter?procType=2&status=1&entityApiKey="+xObject;
        if(bizTypeId!=null){
            url = "/rest/metadata/v2.0/creekflow/processDefinitions/filter?procType=2&status=1&entityApiKey="+xObject+"&bizTypeId="+bizTypeId;
        }
        RkhdHttpData rkhdDate = new RkhdHttpData();
        rkhdDate.setCall_type("GET");
        rkhdDate.setCallString(url);
        String resJson = rkhdClient.execute(rkhdDate, ResponseBodyHandlers.ofString());
        JSONObject rJson = JSONObject.parseObject(resJson);
        return rJson;
    }


    /**
     * preProcessor 获取流程下一节点信息
     *
     * @param rkhdClient
     * @param entityApiKey
     * @param action       提交 submit 	拒绝 reject 同意 agree
     * @param dataId
     * @return
     */
    public static JSONObject preProcessor(RkhdHttpClient rkhdClient, String entityApiKey, String action, Long dataId) {
        JSONObject result=new JSONObject();
        if(action.equals("withdraw")){
            action="reject";
        }
        Long usertaskLogId = null;
        if (!"submit".equals(action)) {
            JSONObject historyJson = queryApprovalHistory(entityApiKey, dataId, "false");
            JSONArray historyArray = historyJson.getJSONArray("data");
            for (Object o : historyArray) {
                JSONObject history = (JSONObject) o;
                if (history.getInteger("usertaskInstStatus") == 1 && history.getInteger("status") == 5) {
                    usertaskLogId = history.getLong("id");
                    break;
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
//        logger.info("获取下一节点信息入参：" + body.toString());
        try {
            String resJson = rkhdClient.execute(rkhdDate, ResponseBodyHandlers.ofString());
            JSONObject rJson = JSONObject.parseObject(resJson);
            if ("200".equals(rJson.getString("code"))) {
                JSONObject retJson = rJson.getJSONObject("data");
                retJson.put("usertaskLogId", usertaskLogId);
                return retJson;
            } else {
                logger.error("获取下一节点信息出错：" + rJson.getString("msg"));
            }
        } catch (XsyHttpException e) {
            logger.error(e.getMessage(), e);
        }
        return result;
    }

    /**
     * approvalOperate 审批操作
     *
     * @param rkhdClient
     * @param entityApiKey
     * @param action       提交submit 同意agree 拒绝reject 撤回withdraw
     * @param dataId
     * @param submitter
     * @param preProcessor
     */
    public static void approvalOperate(RkhdHttpClient rkhdClient, String entityApiKey, String action, long dataId,
                                       Long submitter, JSONObject preProcessor,int rejectStage,String option) {
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
        data.put("comment",option);
        if (action.equals("reject")) {
            //默认取第一个
            data.put("targetTaskDefKey", preProcessor.getJSONArray("returnableTaskDefKeyNamePairs").getJSONObject(rejectStage).getString("key"));
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
        logger.info("提交流程body===" + body.toString());
        try {
            String resJson = rkhdClient.execute(rkhdDate, ResponseBodyHandlers.ofString());
            JSONObject rJson = JSONObject.parseObject(resJson);
            logger.info("审批操作结果：" + rJson);
            if (!"200".equals(rJson.getString("code"))) {
                logger.error("审批操作出错：" + rJson.getString("msg"));
            }
        } catch (XsyHttpException e) {
            logger.error("审批操作出错：" + e.getMessage());
        }
    }

    /**
     * 查询审批历史
     */
    public static JSONObject queryApprovalHistory(String entityApiKey, Long dataId, String stageFlg) {
        RkhdHttpData rkhdHttpData = RkhdHttpData.newBuilder()
                .callString("/rest/data/v2.0/creekflow/history/filter?entityApiKey=" + entityApiKey + "&dataId=" + dataId + "&stageFlg=" + stageFlg)
                .callType("GET").build();
        JSONObject jsonObject = null;
        try {
            jsonObject = RkhdHttpClient.instance().execute(rkhdHttpData,
                    ResponseBodyHandlers.ofJSON());
        } catch (XsyHttpException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
//        logger.info("审批历史 :" + jsonObject.toJSONString());
        return jsonObject;
    }

    public static void approval(String entityApiKey, String action, Long dataId,String option) throws IOException {
        JSONObject processorJson = preProcessor(RkhdHttpClient.instance(), entityApiKey, action, dataId);
        approvalOperate(RkhdHttpClient.instance(), entityApiKey, action, dataId, null, processorJson,0,option);
    }

    // ===================== v1 审批 OpenAPI（对外集成接口族） =====================
    // 路径族：/data/v1/objects/approval/{submit|refuse|agree|cancel|approvals|approvalHistory}
    // 与 creekflow/task（v2 内部接口）区别：v1 直接以 approvalId 定位审批任务来执行动作，
    // 不需要 preProcessor 组装 procdefId / usertaskLogId / targetTaskDefKey，适合「代用户执行审批动作」。

    /**
     * v1 OpenAPI：查询某用户的审批任务列表（待办）。
     *
     * @param userId   处理人（审批人）用户 id
     * @param pageNo   页码，从 1 开始
     * @param pageSize 每页条数
     * @return 平台返回的原始 JSON（含 records 数组），失败返回 null
     */
    public static JSONObject queryApprovalTasks(Long userId, int pageNo, int pageSize) {
        String url = "/data/v1/objects/approval/approvals?pageNo=" + pageNo
                + "&pageSize=" + pageSize + "&userId=" + userId;
        RkhdHttpData data = RkhdHttpData.newBuilder().callString(url).callType("GET").build();
        try {
            String resJson = RkhdHttpClient.instance().execute(data, ResponseBodyHandlers.ofString());
            logger.info("v1 approvals 查询结果：" + resJson);
            return JSONObject.parseObject(resJson);
        } catch (Exception e) {
            logger.error("v1 approvals 查询异常 url=" + url + ": " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * v1 OpenAPI：执行「拒绝」审批动作（真正替审批人执行拒绝，不手工改业务状态字段）。
     *
     * @param approvalId 审批任务 id（v1 approvals 接口返回的那条记录 id）
     * @param comments   审批意见 / 拒绝原因
     * @return 平台返回的原始 JSON，失败返回 null
     */
    public static JSONObject refuseByOpenApi(Long approvalId, String comments) {
        JSONObject body = new JSONObject();
        body.put("approvalId", approvalId);
        body.put("comments", comments == null ? "" : comments);
        String url = "/data/v1/objects/approval/refuse";
        logger.info("v1 refuse 请求 body=" + body.toJSONString());
        try {
            RkhdHttpData data = RkhdHttpData.newBuilder().callString(url).callType("POST")
                    .header("Content-Type", "application/json")
                    .body(body.toJSONString()).build();
            String resJson = RkhdHttpClient.instance().execute(data, ResponseBodyHandlers.ofString());
            logger.info("v1 refuse 响应：" + resJson);
            return JSONObject.parseObject(resJson);
        } catch (Exception e) {
            logger.error("v1 refuse 调用异常 approvalId=" + approvalId + ": " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * v1 OpenAPI：执行「同意」审批动作（与 {@link #refuseByOpenApi} 对称）。
     *
     * @param approvalId     审批任务 id
     * @param approvalUserId 处理人（审批人）用户 id
     * @param comments       审批意见
     */
    public static JSONObject agreeByOpenApi(Long approvalId, Long approvalUserId, String comments) {
        JSONObject body = new JSONObject();
        body.put("approvalId", approvalId);
        if (approvalUserId != null) {
            body.put("approvalUserId", approvalUserId);
        }
        body.put("comments", comments == null ? "" : comments);
        String url = "/data/v1/objects/approval/agree";
        logger.info("v1 agree 请求 body=" + body.toJSONString());
        try {
            RkhdHttpData data = RkhdHttpData.newBuilder().callString(url).callType("POST")
                    .header("Content-Type", "application/json")
                    .body(body.toJSONString()).build();
            String resJson = RkhdHttpClient.instance().execute(data, ResponseBodyHandlers.ofString());
            logger.info("v1 agree 响应：" + resJson);
            return JSONObject.parseObject(resJson);
        } catch (Exception e) {
            logger.error("v1 agree 调用异常 approvalId=" + approvalId + ": " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * 审批拒绝
     * @param entityApiKey
     * @param dataId
     * @throws IOException
     */
    public static void reject(String entityApiKey, Long dataId, int rejectStage,String option) throws IOException {
        JSONObject processorJson = preProcessor(RkhdHttpClient.instance(), entityApiKey, "reject", dataId);
        approvalOperate(RkhdHttpClient.instance(), entityApiKey, "reject", dataId, null, processorJson,rejectStage,option);
    }

    public static void approvalWithDraw(String entityApiKey, String action, Long dataId,String option) throws IOException {
        JSONObject historyJson = queryApprovalHistory(entityApiKey,dataId,"false");
        JSONArray historyArray = historyJson.getJSONArray("data");
        Long usertaskLogId = null;
        Long procInstId = null;
        for (Object o : historyArray) {
            JSONObject history = (JSONObject) o;
            if (history.getInteger("usertaskInstStatus") == 1 && history.getInteger("status") == 5) {
                logger.info("history:"+history.toString());
                usertaskLogId = history.getLong("id");
                procInstId = history.getLong("procInstId");
                //break;
            }
        }
        RkhdHttpData rkhdDate = new RkhdHttpData();
        rkhdDate.setCall_type("POST");
        rkhdDate.setCallString("/rest/data/v2.0/creekflow/task");
        JSONObject data = new JSONObject();
        data.put("entityApiKey", entityApiKey);
        data.put("action", action);
        data.put("dataId", dataId);
        data.put("procInstId", procInstId);
        data.put("srcProcType",2);
//        data.put("submitter", 1806417665311704L);
        data.put("comment",option);
        JSONObject body = new JSONObject();
        body.put("data", data);
        rkhdDate.setBody(body.toString());
        logger.info("撤回流程body===" + body.toString());
        try {
            String resJson = RkhdHttpClient.instance().execute(rkhdDate, ResponseBodyHandlers.ofString());
            JSONObject rJson = JSONObject.parseObject(resJson);
            logger.info("审批操作结果：" + rJson);
            if (!"200".equals(rJson.getString("code"))) {
                logger.error("审批操作出错：" + rJson.getString("msg"));
            }
        } catch (XsyHttpException e) {
            logger.error("审批操作出错：" + e.getMessage());
        }
    }
}
