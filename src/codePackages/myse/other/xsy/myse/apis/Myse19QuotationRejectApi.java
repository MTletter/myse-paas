package other.xsy.myse.apis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.data.model.MessageCenter__c;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import other.xsy.myse.utils.HttpUtil;
import com.rkhd.platform.sdk.data.model.Quote__c;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.XObjectService;
import other.xsy.myse.utils.PiConfig;
import other.xsy.myse.utils.QueryUtil;

import java.util.Date;
import java.util.Map;

/**
 * MYSE-19 报价单拒单
 * <p>
 * mySE 通知 SAP ERP 拒绝指定报价单。
 * 方向：mySE → PI → SAP
 * </p>
 *
 * <p><b>销售易 API 路径</b>：/rest/data/v2.0/scripts/api/myse/quotation/reject</p>
 *
 * <p><b>业务说明</b>：前端传报价单对象 id + 拒绝原因，后端按 id 查 Quote__c，
 * 取 quoteNumberAutoGenerate__c 填入 SAP 入参 IV_VBELN，
 * 拒绝原因 refusalReason__c（选项序号）经选项集反查 <b>apiKey（Y1~Y4）</b>填入 IV_ABGRU，
 * 同步成功后回写报价单状态 quoteStatus__c = 5（拒单）。</p>
 */
@RestApi(baseUrl = "/myse")
public class Myse19QuotationRejectApi {

    private static final Logger logger = LoggerFactory.getLogger();

    private static  String PI_URL ;

    private static  String API_TOKEN;

    /** 延迟加载 PI 配置（不在 static 块里做 SDK 查询，避免类初始化阶段类加载失败）。 */
    private static void ensureConfigLoaded() {
        if (PI_URL == null) {
            PiConfig.Entry c = PiConfig.get("MYSE-19");
            if (c != null) {
                PI_URL = c.getUrl();
                API_TOKEN = c.getToken();
            }
        }
    }

    @RestMapping(value = "/quotation/reject", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name="json") String body) {
        try {
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            JSONObject requestBody = JSON.parseObject(body);

            // ===== 1) 前端仅传报价单对象 id =====
            Long quoteId = requestBody.getLong("id");
            Integer refusalReason__c=requestBody.getInteger("refusalReason__c");
            if (quoteId == null) {
                return HttpUtil.fail("PARAM_ERROR", "报价单id(id)不能为空").toJSONString();
            }
            logger.info("MYSE-19 报价单拒单请求 quoteId=" + quoteId);
            ensureConfigLoaded();

            // ===== 2) 按 id 查报价单，取 SAP 单号 quoteNumberAutoGenerate__c =====
            Quote__c quote = new Quote__c();
            quote.setId(quoteId);
            quote = XObjectService.instance().get(quote, true);
            String vbeln = quote == null ? null : quote.getQuoteNumberAutoGenerate__c();
            if (vbeln == null || vbeln.trim().isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "报价单未生成编号 quoteNumberAutoGenerate__c，无法拒单").toJSONString();
            }

            // ===== 3) 组装 SAP 入参（IV_ABGRU 传拒绝原因选项的 apiKey） =====
            String abgru = resolveRefusalApiKey(refusalReason__c);
            JSONObject sapPayload = new JSONObject();
            sapPayload.put("IV_ABGRU", abgru);
            sapPayload.put("IV_POSNR", "");
            sapPayload.put("IV_VBELN", vbeln);
            JSONObject ret = new JSONObject();
            ret.put("item", new JSONObject());
            sapPayload.put("RETURN", ret);
            logger.info("MYSE-19 SAP报文: " + sapPayload.toJSONString());

            // ===== 4) 出站调用 SAP PI =====
            JSONObject piResponse = HttpUtil.post(PI_URL, sapPayload, API_TOKEN);
            logger.info("MYSE-19 piResponse: " + piResponse.toJSONString());

            // ===== 5) 调用失败（含网关/出站异常）不更新状态 =====
            if (piResponse == null || piResponse.containsKey("error")) {
                String errMsg = piResponse == null ? "SAP PI 返回为空" : piResponse.getString("error");
                logger.error("MYSE-19 SAP PI 调用失败: " + errMsg);
                return HttpUtil.fail("OUTBOUND_ERROR", "SAP PI 调用失败: " + errMsg+","+piResponse.getJSONObject("RETURN").getJSONObject("item").getString("MSG")).toJSONString();
            }

            // ===== 5.5) SAP 业务失败（RETURNCODE=E）不更新状态 =====
            String returnCode = piResponse.getString("RETURNCODE");
            if ("E".equalsIgnoreCase(returnCode)) {
                String errMsg = piResponse.getString("RETURNMESSAGE");
                logger.error("MYSE-19 SAP返回失败: " + piResponse.toJSONString());
                return HttpUtil.fail("PI_ERROR",
                        (errMsg == null || errMsg.isEmpty()) ? "SAP 拒单失败" : errMsg).toJSONString();
            }

            // ===== 6) 同步成功，回写报价单状态 quoteStatus__c = 5 =====
            updateQuoteStatus(quoteId, 5,refusalReason__c);

            String sql="select id from messageCenter__c where isHandle__c=2 and todoType__c=2 and messageType__c=1 and dataId__c='"+quote.getQuoteNumberAutoGenerate__c()+"'";
            logger.info("sql:"+sql);
            QueryResult<MessageCenter__c> res=XObjectService.instance().query(sql,true);
            logger.info("res:"+res.getRecords());
            if(!res.getRecords().isEmpty()){
                MessageCenter__c upMsg=res.getRecords().get(0);
                upMsg.setIsHandle__c(1);
                XObjectService.instance().update(upMsg,true);
            }

            return HttpUtil.success(piResponse).toJSONString();

        } catch (Exception e) {
            logger.error("MYSE-19 报价单拒单异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    /**
     * 解析拒绝原因选项的 <b>apiKey</b>，作为 SAP 入参 IV_ABGRU 下发。
     *
     * <p>quote__c.refusalReason__c 是 picklist，值为选项序号（1~4），SAP 侧要的是业务编码
     * （apiKey，Y1~Y4），所以不能直接传序号：</p>
     * <pre>
     * value=1「需求变动」→ apiKey=Y1
     * value=2「重复下单」→ apiKey=Y2
     * value=3「物料/数量/折扣错误」→ apiKey=Y3
     * value=4「施耐德原因」→ apiKey=Y4
     * </pre>
     *
     * @param reason 前端传的 refusalReason__c（选项序号）
     * @return 对应 apiKey；未命中返回空串（由 SAP 侧校验拦截）
     */
    private String resolveRefusalApiKey(Integer reason) {
        if (reason == null) {
            logger.warn("MYSE-19 拒绝原因 refusalReason__c 为空，IV_ABGRU 下发空串");
            return "";
        }
        try {
            Map<String, Map<String, Integer>> picklistMap =
                    QueryUtil.getPicklistDescriptionApiKeyToValue("quote__c");
            Map<String, Integer> opts = picklistMap == null ? null : picklistMap.get("refusalReason__c");
            if (opts == null || opts.isEmpty()) {
                logger.error("MYSE-19 未取到 quote__c.refusalReason__c 选项集，IV_ABGRU 下发空串");
                return "";
            }
            for (Map.Entry<String, Integer> e : opts.entrySet()) {
                if (reason.equals(e.getValue())) {
                    logger.info("MYSE-19 拒绝原因映射: value=" + reason + " → IV_ABGRU=" + e.getKey());
                    return e.getKey();
                }
            }
            // 选项集里没有该值（数据脏或选项调整过）：原样下发序号值，不静默吞掉
            logger.warn("MYSE-19 拒绝原因 value=" + reason
                    + " 不在 quote__c.refusalReason__c 选项集内，IV_ABGRU 原样下发该值。可选 apiKey="
                    + opts.keySet());
            return String.valueOf(reason);
        } catch (Exception e) {
            logger.error("MYSE-19 解析拒绝原因 apiKey 失败 value=" + reason, e);
            return "";
        }
    }

    /**
     * 回写报价单状态（失败仅记日志，不影响主流程返回）。
     */
    private void updateQuoteStatus(Long quoteId, Integer status,Integer refusalReason__c) {
        if (quoteId == null) {
            return;
        }
        try {
            Quote__c q = new Quote__c();
            q.setId(quoteId);
            q.setQuoteStatus__c(status);
            q.setStatusDate__c(new Date().getTime());
            q.setRefusalReason__c(refusalReason__c);
            XObjectService.instance().update(q);
            logger.info("MYSE-19 报价单状态已回写 quoteId=" + quoteId + " quoteStatus__c=" + status);
        } catch (Exception e) {
            logger.error("MYSE-19 回写报价单状态失败 quoteId=" + quoteId + " status=" + status, e);
        }
    }
}
