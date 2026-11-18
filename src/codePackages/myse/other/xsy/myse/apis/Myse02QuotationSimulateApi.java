package other.xsy.myse.apis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.data.model.Account;
import com.rkhd.platform.sdk.data.model.Quote__c;
import com.rkhd.platform.sdk.http.CommonData;
import com.rkhd.platform.sdk.http.CommonHttpClient;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;
import org.apache.commons.lang.StringUtils;
import other.xsy.myse.utils.HttpUtil;
import other.xsy.myse.utils.PiConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static other.xsy.myse.utils.JsonValues.trimToNull;
import static other.xsy.myse.utils.StringUtil.padKunnr;

/**
 * MYSE-02 报价单模拟（dry-run）
 * <p>
 * 调用 SAP BAPI：Z_BAPI_SIMULATE_QUOTATION
 * 方向：mySE → PI → SAP
 * </p>
 *
 * <p><b>销售易 API 路径</b>：/rest/data/v2.0/scripts/api/myse/quotation/simulate</p>
 *
 * <p><b>业务说明</b>：报价单模拟创建，仅校验不落库。报文结构与 MYSE-03 一致。</p>
 *
 * <p><b>入参</b>：{"id": 报价单id}，或草稿模式 {"main": {...}, "lines": [...]}</p>
 *
 * <p><b>选型配置 XML 来源优先级（2026-10-17）</b>（作用于 lines / quoteDetail__c 明细行）：
 * <ol>
 *   <li>{@code selectionXml__c} 有值 → <b>直接用前端传的 XML，不调 hive 接口</b>；</li>
 *   <li>否则 {@code selectionId__c} 有值 → 按该配置ID 调 hive 接口取 XML（同一请求内按 id 缓存）；</li>
 *   <li>两者都无 → 该行不产生配置数据。</li>
 * </ol>
 * </p>
 *
 * <p>辅助查询 / VKORG 解析 / 选型配置块 / 报文构建见 {@link QuotationApiBase}（与 MYSE-03 共用）。</p>
 */
@RestApi(baseUrl = "/myse")
public class Myse02QuotationSimulateApi extends QuotationApiBase {

    private static String PI_URL;

    private static String API_TOKEN;

    @Override
    protected String tag() {
        return "MYSE-02";
    }

    /**
     * 延迟加载配置：不在 static 块里做 SDK 查询，避免类初始化阶段 SDK 上下文未就绪导致类加载失败。
     * 任何异常均记录日志而不抛出，保证入口方法始终可用。
     */
    private void ensureConfigLoaded() {
        if (PI_URL == null) {
            PiConfig.Entry c = PiConfig.get("MYSE-02");
            if (c != null) {
                PI_URL = c.getUrl();
                API_TOKEN = c.getToken();
            }
        }
    }

    @RestMapping(value = "/quotation/simulate", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        try {
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            JSONObject requestBody = JSON.parseObject(body);
            logger.info("MYSE-02 请求: " + requestBody.toJSONString());
            ensureConfigLoaded();

            Long quoteId = requestBody.getLong("id");
            // 兼容旧调用：仍传报价单 id 时按原逻辑查库取主记录 + 明细
            if (quoteId != null) {
                Quote__c quote = new Quote__c();
                quote.setId(quoteId);
                quote = XObjectService.instance().get(quote, true);
                String oldDetailSql = "SELECT id, posicion__c, catalogNumber__c, quantity__c, requestedDeliveryDate__c, "
                        + "shipToAddress__c, configurationID__c, selectionId__c, shipTo__c.kunnr__c kunnr "
                        + "FROM quoteDetail__c WHERE quoteId__c=" + quoteId;
                QueryResult<JSONObject> oldRes = XoqlService.instance().query(oldDetailSql);
                List<JSONObject> oldDetails = oldRes.getRecords() != null ? oldRes.getRecords() : Collections.emptyList();
                String oldKunnr = padKunnr(queryKunnr(quote.getPurchaserId__c()));
                // 送达方客户代码：quote__c.shipTo__c(发货地址) → shipToParty__c.kunnr__c
                String oldShipToKunnr = queryShipToKunnr(quote.getShipTo__c());
                JSONObject oldPayload = buildPayload("", "", "", oldDetails, queryProductNames(oldDetails), "CN",
                        oldKunnr, oldShipToKunnr, resolveVkorg(oldDetails));
                logger.info("MYSE-02 SAP报文(旧id模式): " + oldPayload.toJSONString());
                return callPi(oldPayload);
            }

            // ====== 新模式：前端未保存的草稿数据（main + lines）直接构建报文 ======
            String operateType = trimToNull(requestBody.getString("operateType"));
            logger.info("MYSE-02 operateType=" + operateType);
            JSONObject main = requestBody.getJSONObject("main");
            JSONArray linesArr = requestBody.getJSONArray("lines");
            if (main == null) {
                return HttpUtil.fail("PARAM_ERROR", "main 不能为空").toJSONString();
            }
            if (linesArr == null || linesArr.isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "lines 不能为空").toJSONString();
            }
            List<JSONObject> lines = new ArrayList<>();
            for (int i = 0; i < linesArr.size(); i++) {
                JSONObject line = linesArr.getJSONObject(i);
                if (line != null) {
                    lines.add(line);
                }
            }
            logger.info("MYSE-02 明细数: " + lines.size());

            // 1) 批量反查产品名称（catalogNumber__c(product id) → product.productName）
            Map<Long, String> productNameMap = queryProductNames(lines);

            // 2) 查产品销售组织 → 销售组织编码，命中 WG01/IB01 才回传 VKORG（其余留空）
            String vkorg = resolveVkorg(lines);

            // 3) 查客户代码：main 里带 purchaserId__c（账户id，可能是 {id:..} 对象或标量）时才反查
            String kunnr = "";
            Long purchaserId = readId(main.get("purchaserId__c"));
            if (purchaserId != null) {
                Account account=new Account();
                account.setId(purchaserId);
                account=XObjectService.instance().get(account, true);
                if(!account.getIsEnable__c()){
                    logger.info("账户未启用，不允许下单");
                    JSONObject RESP_QUOTATION=new JSONObject();
                    RESP_QUOTATION.put("RETURNCODE","401");
                    RESP_QUOTATION.put("RETURNMESSAGE","您的账户权限已被停用");
                    return RESP_QUOTATION.toString();
                }
                kunnr = padKunnr(queryKunnr(purchaserId));
            } else {
                logger.warn("MYSE-02 main 未提供 purchaserId__c，PURCHASERID/CUSTOMERID 留空");
            }

            // 3.1) 送达方客户代码（CAB 头 CUSTOMERID）：
            //      main.shipTo__c(发货地址 id) → shipToParty__c.kunnr__c；
            //      前端草稿若直接给了 shipTo__c.kunnr__c / kunnr 则优先取前端值
            String shipToKunnr = resolveMainShipToKunnr(main);

            // 4) 构建 SAP 报文并调用 PI（模拟仅校验不落库；单号/创建日期/采购单号留空）
            JSONObject sapPayload = buildPayload("", "", "", lines, productNameMap, "CN", kunnr, shipToKunnr, vkorg);
            logger.info("MYSE-02 SAP报文: " + sapPayload.toJSONString());
            return callPi(sapPayload);

        } catch (Exception e) {
            logger.error("MYSE-02 执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    /** 调用 PI 并统一处理返回。 */
    private String callPi(JSONObject sapPayload) {
        JSONObject piResponse = HttpUtil.post(PI_URL, sapPayload, API_TOKEN);
        logger.info("piResponse:" + piResponse);
        if (piResponse != null && piResponse.containsKey("RESULT")
                && Objects.equals(piResponse.getJSONObject("RESULT").getString("RETURNCODE"), "E")) {
            logger.error("MYSE-02 调用失败: " + piResponse.toJSONString());
            return HttpUtil.fail("PI_ERROR", piResponse.getJSONObject("RESULT").getString("RETURNMESSAGE")).toJSONString();
        }
        logger.info("MYSE-02 成功");
        return piResponse == null ? "" : piResponse.toJSONString();
    }

    /**
     * 从草稿 main 里取送达方客户代码（表头 CUSTOMERID）。
     * <p>取值优先级：</p>
     * <ol>
     *   <li>{@code main.kunnr} 或 {@code main.shipTo__c.kunnr__c} —— 前端已解析好的客户代码；</li>
     *   <li>{@code main.shipTo__c} 作为 id → 反查 shipToParty__c.kunnr__c。</li>
     * </ol>
     */
    private String resolveMainShipToKunnr(JSONObject main) {
        if (main == null) {
            return "";
        }
        String direct = trimToNull(main.getString("kunnr"));
        if (direct == null) {
            Object shipToObj = main.get("shipTo__c");
            if (shipToObj instanceof JSONObject) {
                direct = trimToNull(((JSONObject) shipToObj).getString("kunnr__c"));
            }
        }
        if (direct != null) {
            return padKunnr(direct);
        }
        return queryShipToKunnr(readId(main.get("shipTo__c")));
    }

    /**
     * 调用 Schneider 选型配置器 hive 接口，返回 XML 字符串。失败时返回 null。
     * <p>注意：本接口（模拟）沿用硬编码地址/token，与 MYSE-03 走 SystemConfig 的方式不同，保持原行为。</p>
     */
    @Override
    protected String fetchSchneiderXml(String configId) {
        String url = "https://gw-api.schneider-electric.cn/sc/v2.5/configurations/persisted/" + configId + "/hive";
        try {
            CommonData cd = new CommonData("GET",null , url);
            cd.putHeader("Authorization", "Bearer 1HHlLSuMq7S_kUE3db4lejHMjT6hr4p0");
            cd.putHeader("Accept", "application/xml");
            CommonHttpClient client = new CommonHttpClient();
            String xml = client.performRequest(cd);
            if (StringUtils.isBlank(xml)) {
                logger.warn("MYSE-02 Schneider 返回空, configId=" + configId);
                return null;
            }
            return xml;
        } catch (Exception e) {
            logger.error("MYSE-02 Schneider 调用异常, configId=" + configId, e);
            return null;
        }
    }
}