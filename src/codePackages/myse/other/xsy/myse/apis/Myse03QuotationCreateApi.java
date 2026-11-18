package other.xsy.myse.apis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.data.model.MessageCenter__c;
import com.rkhd.platform.sdk.data.model.Quote__c;
import com.rkhd.platform.sdk.exception.CustomConfigException;
import com.rkhd.platform.sdk.http.CommonData;
import com.rkhd.platform.sdk.http.CommonHttpClient;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.CustomConfigService;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;
import org.apache.commons.lang.StringUtils;
import other.xsy.myse.utils.HttpUtil;
import other.xsy.myse.utils.PiConfig;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static other.xsy.myse.utils.JsonValues.formatDateSAP;
import static other.xsy.myse.utils.StringUtil.padKunnr;

/**
 * MYSE-03 报价单创建
 * <p>
 * 调用 SAP BAPI：Z_BAPI_QUOTATION_ENTRY
 * 方向：mySE → PI → SAP
 * </p>
 *
 * <p><b>销售易 API 路径</b>：/rest/data/v2.0/scripts/api/myse/quotation/create</p>
 *
 * <p><b>业务说明</b>：正式创建报价单并落库 SAP。</p>
 *
 * <p><b>入参</b>：{"id": 报价单id}</p>
 *
 * <p><b>选型配置 XML 来源优先级（2026-10-17，与 MYSE-02 保持一致）</b>：
 * <ol>
 *   <li>{@code selectionXml__c} 有值 → <b>直接用前端传的 XML，不调 hive 接口</b>；</li>
 *   <li>否则 {@code selectionId__c} 有值 → 按该配置ID 调 hive 接口取 XML（同一请求内按 id 缓存）；</li>
 *   <li>两者都无 → 该行不产生配置数据。</li>
 * </ol>
 * </p>
 *
 * <p>辅助查询 / VKORG 解析 / 选型配置块 / 报文构建见 {@link QuotationApiBase}（与 MYSE-02 共用）。</p>
 */
@RestApi(baseUrl = "/myse")
public class Myse03QuotationCreateApi extends QuotationApiBase {

    private static String PI_URL;

    private static String API_TOKEN;

    /** Schneider 选型配置器（hive）地址前缀：domain + /sc/v2.5/configurations/persisted/ ，后接 {configId}/hive */
    private static String SCHNEIDER_URL_PREFIX;

    /** Schneider 选型配置器 Bearer Token（不硬编码，取自 SystemConfig MYSE-SCHNEIDER） */
    private static String SCHNEIDER_TOKEN;

    /** 消息中心业务类型（entityType），用于写期望发货日期变更通知。 */
    private static Long MESSAGE_CENTER_BUSI_TYPE;

    @Override
    protected String tag() {
        return "MYSE-03";
    }

    /**
     * 延迟加载配置：PI（MYSE-03）+ Schneider 选型配置器（MYSE-SCHNEIDER）。
     * 不在 static 块里做 SDK 查询，避免类初始化阶段 SDK 上下文未就绪导致类加载失败。
     * 任何异常均记录日志而不抛出，保证入口方法始终可用。
     */
    private void ensureConfigLoaded() {
        if (PI_URL == null) {
            PiConfig.Entry c = PiConfig.get("MYSE-03");
            if (c != null) {
                PI_URL = c.getUrl();
                API_TOKEN = c.getToken();
            }
        }
        if (SCHNEIDER_URL_PREFIX == null) {
            PiConfig.Entry c = PiConfig.get("MYSE-SCHNEIDER");
            if (c != null) {
                SCHNEIDER_URL_PREFIX = c.getUrl();
                SCHNEIDER_TOKEN = c.getToken();
            }
        }
        try {
            // 获取对象实例
            CustomConfigService instance = CustomConfigService.instance();
            // 获取配置集,返回此配置集中的所有配置项
            Map<String, String> configSet = instance.getConfigSet("entityType");
            MESSAGE_CENTER_BUSI_TYPE=Long.parseLong(configSet.get("MessageCenter"));
        } catch (CustomConfigException e) {
            throw new RuntimeException(e);
        }
    }

    @RestMapping(value = "/quotation/create", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        Long quoteId = null;
        try {
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            JSONObject requestBody = JSON.parseObject(body);
            logger.info("MYSE-03 请求: " + requestBody.toJSONString());

            ensureConfigLoaded();

            quoteId = requestBody.getLong("id");
            if (quoteId == null) {
                return HttpUtil.fail("PARAM_ERROR", "id 不能为空").toJSONString();
            }

            // 1) 查报价单主记录
            Quote__c quote = new Quote__c();
            quote.setId(quoteId);
            quote = XObjectService.instance().get(quote,true);

            logger.info("quote:"+quote);

            // 2) 查明细（用 XOQL 取 JSONObject，确保拿到 model.jar 可能缺少 getter 的字段如 configurationID__c / shipToAddress__c）
            String detailSql = "SELECT id, posicion__c, catalogNumber__c, quantity__c, requestedDeliveryDate__c, "
                    + "shipToAddress__c, configurationID__c, selectionId__c, selectionXml__c, shipTo__c.kunnr__c kunnr "
                    + "FROM quoteDetail__c WHERE quoteId__c=" + quoteId;
            logger.info("MYSE-03 明细SQL: " + detailSql);
            QueryResult<JSONObject> detailRes = XoqlService.instance().query(detailSql);
            List<JSONObject> details = detailRes.getRecords() != null ? detailRes.getRecords() : Collections.emptyList();
            logger.info("MYSE-03 明细数: " + details.size());

            // 3) 批量反查产品名称（catalogNumber__c → product.productName）
            Map<Long, String> productNameMap = queryProductNames(details);

            // 4) 查产品销售组织 → 销售组织编码，命中 WG01/IB01 才回传 VKORG（其余留空）
            String vkorg = resolveVkorg(details);

            // 5) 查客户代码（purchaserId__c → account.kunnr__c），不足10位前补0（SAP CHAR10）
            String kunnr = padKunnr(queryKunnr(quote.getPurchaserId__c()));

            // 6.1) 送达方客户代码（表头 CUSTOMERID）：quote__c.shipTo__c → shipToParty__c.kunnr__c
            String shipToKunnr = queryShipToKunnr(quote.getShipTo__c());

            // 7) 构建 SAP 报文并调用 PI
            JSONObject sapPayload = buildPayload(quote.getQuoteNumberAutoGenerate__c(),
                    formatDateSAP(quote.getCreatedAt()), quote.getPurchaseOrder__c(),
                    details, productNameMap, "CN", kunnr, shipToKunnr, vkorg);
            logger.info("MYSE-03 SAP报文: " + sapPayload.toJSONString());

            JSONObject piResponse = HttpUtil.post(PI_URL, sapPayload, API_TOKEN);

            if (piResponse != null && piResponse.containsKey("RESP_QUOTATION")
                    && Objects.equals(piResponse.getJSONObject("RESP_QUOTATION").getString("RETURNCODE"), "E")) {
                String errMsg = piResponse.getJSONObject("RESP_QUOTATION").getString("RETURNMESSAGE");
                logger.error("MYSE-03 调用失败: " + piResponse.toJSONString());
                updateSyncStatus(quoteId, 3, errMsg);
                return HttpUtil.fail("PI_ERROR", errMsg).toJSONString();
            }

            if (piResponse == null) {
                logger.error("MYSE-03 SAP 返回为空(出站异常)");
                updateSyncStatus(quoteId, 3, "SAP 返回为空(出站异常)");
                return HttpUtil.fail("OUTBOUND_ERROR", "SAP 返回为空(出站异常)").toJSONString();
            }

            String VBELN=piResponse.getJSONObject("RESP_QUOTATION").getString("VBELN");
            logger.info("MYSE-03 成功");
            updateSyncStatus(quoteId, 2, VBELN);
            return piResponse.toJSONString();

        } catch (Exception e) {
            logger.error("MYSE-03 执行异常", e);
            updateSyncStatus(quoteId, 3, e.getMessage());
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    /**
     * 调用 Schneider 选型配置器 hive 接口，返回 XML 字符串。失败时返回 null。
     * URL/Token 取自 SystemConfig MYSE-SCHNEIDER；配置缺失则跳过配置块。
     */
    @Override
    protected String fetchSchneiderXml(String configId) {
        if (StringUtils.isBlank(SCHNEIDER_URL_PREFIX)) {
            logger.warn("MYSE-03 Schneider 配置缺失(SystemConfig MYSE-SCHNEIDER)，跳过配置块");
            return null;
        }
        String url = SCHNEIDER_URL_PREFIX + configId + "/hive";
        try {
            CommonData cd = new CommonData(url, "GET", "");
            cd.putHeader("Authorization", "Bearer " + (SCHNEIDER_TOKEN == null ? "" : SCHNEIDER_TOKEN));
            cd.putHeader("Accept", "application/xml");
            CommonHttpClient client = new CommonHttpClient();
            String xml = client.performRequest(cd);
            if (StringUtils.isBlank(xml)) {
                logger.warn("MYSE-03 Schneider 返回空, configId=" + configId);
                return null;
            }
            return xml;
        } catch (Exception e) {
            logger.error("MYSE-03 Schneider 调用异常, configId=" + configId, e);
            return null;
        }
    }

    /**
     * 回写报价单同步状态：syncStatus__c（2=成功，3=失败）、syncDescription__c（接口返回信息/异常信息）。
     * 内部 try-catch，回写失败不影响主流程返回。
     */
    private void updateSyncStatus(Long qid, Integer status, String description) {
        if (qid == null) return;
        try {
            Quote__c queryQuote = new Quote__c();
            queryQuote.setId(qid);
            queryQuote=XObjectService.instance().get(queryQuote, true);

            Quote__c q = new Quote__c();
            q.setId(qid);
            q.setSyncStatus__c(status);
            q.setSyncDescription__c(description == null ? "" : description);

            if(status==2){
                q.setQuoteNumberAutoGenerate__c(description);
                q.setQuoteStatus__c(2);
                MessageCenter__c msg=newNotification("【待确认】报价单:" + queryQuote.getQuoteNumberAutoGenerate__c() + " 待确认",
                        "【待确认】报价单:" + queryQuote.getQuoteNumberAutoGenerate__c() + " 待确认,请尽快处理。",
                        q.getPurchaserId__c(), q.getOwnerId(), queryQuote.getQuoteNumberAutoGenerate__c(), "quote__c");
                logger.info("msg:"+msg);
                OperateResult msgRes=XObjectService.instance().insert(msg,true);
                logger.info("msgRes:"+msgRes);
            }else{
                q.setQuoteStatus__c(7);
            }
            q.setStatusDate__c(new Date().getTime());
            OperateResult res=XObjectService.instance().update(q);
            logger.info("MYSE-03 同步状态已更新 quote=" + q + " status=" + res.getSuccess());
        } catch (Exception e) {
            logger.error("MYSE-03 更新同步状态失败 quoteId=" + qid, e);
        }
    }

    /** 构造一条消息中心记录。 */
    private MessageCenter__c newNotification(String name, String content, Long accountId, Long ownerId,
                                             String dataId, String objectKey) {
        MessageCenter__c mc = new MessageCenter__c();
        mc.setEntityType(MESSAGE_CENTER_BUSI_TYPE);
        // name（标题）为必填字段，不填会导致消息记录插入失败
        mc.setName(name);
        mc.setAccount__c(accountId);
        mc.setPublishTime__c(new Date().getTime());
        mc.setContent__c(content);
        mc.setMessageType__c(1);
        mc.setDataId__c(dataId);
        mc.setObjectKey__c(objectKey);
        mc.setOwnerId(ownerId);
        mc.setTodoType__c(2);
        logger.info(" 消息中心通知: name=" + name + ", content=" + content
                + ", accountId=" + accountId + ", ownerId=" + ownerId);
        return mc;
    }
}