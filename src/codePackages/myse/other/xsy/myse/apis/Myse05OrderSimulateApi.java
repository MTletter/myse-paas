package other.xsy.myse.apis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.data.model.Account;
import com.rkhd.platform.sdk.service.XObjectService;
import other.xsy.myse.utils.HttpUtil;
import other.xsy.myse.utils.PiConfig;

import java.util.*;

import static other.xsy.myse.utils.JsonValues.trimToNull;
import static other.xsy.myse.utils.StringUtil.padKunnr;

/**
 * MYSE-05 销售订单模拟（Order Simulate / Dry-Run）
 * <p>
 * 前端传入 order__c 的 id，本接口加载订单主记录 + 明细，封装为 SAP BAPI
 * （Z_BAPI_SIMULATE_SALESORDER_NEW）报文，调用 SAP PI 后返回价格、折扣、交期等
 * 模拟结果。不落库。
 * </p>
 *
 * <p><b>API 路径</b>：POST /rest/data/v2.0/scripts/api/myse/order/simulate</p>
 * <p><b>Content-Type</b>：application/json</p>
 *
 * <h3>前端请求格式（两种）</h3>
 * <pre>
 * 1) 已保存订单：{ "id": 1234567890123456 }                       // order__c 订单 id
 * 2) 未保存草稿：{ "operateType": "insert", "main": {...}, "lines": [...] }
 *    main  = order__c 主记录字段（purchaseId__c / purchaseOrder__c / e_Bukrs__c /
 *            userName__c / shipToAddress__c / orderNumber__c ...）
 *    lines = orderDetail__c 明细字段（posicion__c / catelogNumber__c / quantity__c /
 *            requestedDeliveryDate__c / shipToAddress__c ...）
 * </pre>
 *
 * <h3>后端处理逻辑</h3>
 * <ol>
 *   <li>按 id 加载 order__c 主记录（内部 XOQL，绕过 API 上下文对象级权限隔离），或直接用前端传的 main/lines；</li>
 *   <li>加载 orderDetail__c 明细（order__c = id）；</li>
 *   <li>反查售达方/送达方客户代码（account.kunnr__c，经 purchaseId__c）；</li>
 *   <li>反查行项目物料号（product.productCode__c，经 catelogNumber__c）；</li>
 *   <li>按下表封装 SAP 报文（CAB_PEDIDO 订单头 + POS_PEDIDO 明细 + 配置块留空）；</li>
 *   <li>调用 SAP PI（Z_BAPI_SIMULATE_SALESORDER_NEW），解析返回价格/折扣/交期。</li>
 * </ol>
 *
 * <p>内部 XOQL / 客户代码与物料号反查 / 报文构建见 {@link OrderApiBase}（与 MYSE-06 共用）。
 * 模拟报文 ORDERNUMBER 传空串、行级不下发 SHIPTONAME。</p>
 */
@RestApi(baseUrl = "/myse")
public class Myse05OrderSimulateApi extends OrderApiBase {

    private static String PI_URL;
    private static String API_TOKEN;

    @Override
    protected String tag() {
        return "MYSE-05";
    }

    /** 延迟加载 PI 配置（不在 static 块里做 SDK 查询，避免类初始化阶段类加载失败）。 */
    private void ensureConfigLoaded() {
        if (PI_URL == null) {
            PiConfig.Entry c = PiConfig.get("MYSE-05");
            if (c != null) {
                PI_URL = c.getUrl();
                API_TOKEN = c.getToken();
            }
        }
    }

    @RestMapping(value = "/order/simulate", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        try {
            // ---- 1. 参数校验 ----
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            JSONObject requestBody = JSON.parseObject(body);
            logger.info("MYSE-05 请求: " + requestBody.toJSONString());
            ensureConfigLoaded();

            Object idObj = requestBody.get("id");
            // ---- 兼容旧调用：仍传订单 id 时按原逻辑查库取主记录 + 明细 ----
            if (idObj != null) {
                Long oldOrderId = parseId(idObj);
                if (oldOrderId == null) {
                    return HttpUtil.fail("PARAM_ERROR", "订单id(id)必须为数字").toJSONString();
                }
                logger.info("MYSE-05 订单模拟 请求 orderId=" + oldOrderId);
                JSONObject oldOrder = loadOrder(oldOrderId);
                List<JSONObject> oldDetails = loadOrderDetails(oldOrderId);
                String oldKunnr = padKunnr(queryKunnr(asLong(oldOrder, "purchaseId__c")));
                JSONObject oldSap = buildOrderPayload(oldOrder, oldDetails, oldKunnr, queryProductCodes(oldDetails), "", false);
                logger.info("MYSE-05 SAP报文(旧id模式): " + oldSap.toJSONString());
                return callPi(oldSap);
            }

            // ---- 新模式：前端未保存草稿数据（main + lines）直接构建报文 ----
            String operateType = trimToNull(requestBody.getString("operateType"));
            logger.info("MYSE-05 operateType=" + operateType);
            JSONObject main = requestBody.getJSONObject("main");
            JSONArray linesArr = requestBody.getJSONArray("lines");
            if (main == null) {
                return HttpUtil.fail("PARAM_ERROR", "main 不能为空").toJSONString();
            }
            if (linesArr == null || linesArr.isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "lines 不能为空").toJSONString();
            }
            List<JSONObject> details = new ArrayList<>();
            for (int i = 0; i < linesArr.size(); i++) {
                JSONObject line = linesArr.getJSONObject(i);
                if (line != null) {
                    details.add(line);
                }
            }
            logger.info("MYSE-05 明细数: " + details.size());

            // 售达方/送达方客户代码（main.purchaseId__c → account.kunnr__c）
            String kunnr = "";
            Long purchaseId = readId(main.get("purchaseId__c"));
            if (purchaseId != null) {
                kunnr = padKunnr(queryKunnr(purchaseId));
                Account account=new Account();
                account.setId(purchaseId);
                account=XObjectService.instance().get(account, true);
                if(!account.getIsEnable__c()){
                    logger.info("账户未启用，不允许操作");
                    JSONObject RESP_QUOTATION=new JSONObject();
                    RESP_QUOTATION.put("RETURNCODE","401");
                    RESP_QUOTATION.put("RETURNMESSAGE","您的账户权限已被停用");
                    return RESP_QUOTATION.toString();
                }
            } else {
                logger.warn("MYSE-05 main 未提供 purchaseId__c，PURCHASERID/CUSTOMERID 留空");
            }

            // 物料号（catelogNumber__c(product id) → product.productCode__c）
            Map<Long, String> productCodeMap = queryProductCodes(details);

            JSONObject sapRequest = buildOrderPayload(main, details, kunnr, productCodeMap, "", false);
            logger.info("MYSE-05 SAP报文: " + sapRequest.toJSONString());
            return callPi(sapRequest);

        } catch (Exception e) {
            logger.error("MYSE-05 执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getClass().getName() + ": " + e.getMessage()).toJSONString();
        }
    }

    /** 调用 SAP PI 并返回原始响应。 */
    private String callPi(JSONObject sapRequest) {
        JSONObject piResponse = HttpUtil.post(PI_URL, sapRequest, API_TOKEN);
        if (piResponse == null) {
            logger.error("MYSE-05 SAP 返回为空(出站异常)");
            return HttpUtil.fail("OUTBOUND_ERROR", "SAP 返回为空(出站异常)").toJSONString();
        }
        logger.info("MYSE-05 PI响应: " + piResponse.toJSONString());
        logger.info("MYSE-05 成功");
        return piResponse.toJSONString();
    }

    private Long parseId(Object idObj) {
        if (idObj instanceof Number) {
            return ((Number) idObj).longValue();
        }
        if (idObj == null) {
            return null;
        }
        try {
            return Long.parseLong(idObj.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取字段 id：lookup 字段可能是 JSONObject{id} 或标量。 */
    private Long readId(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof JSONObject) {
            v = ((JSONObject) v).get("id");
        }
        return parseId(v);
    }

    // ===================== 数据加载（内部 XOQL，系统鉴权）=====================

    private JSONObject loadOrder(Long orderId) {
        JSONArray recs = queryInternal(
                "SELECT id, orderNumberAutoGenerate__c, e_Bukrs__c, purchaseId__c, "
                        + "purchaseOrder__c, userName__c, shipToAddress__c,shipToCode__c.accountName shipToName,shipToCode__c.kunnr__c shipToCode "
                        + "FROM order__c WHERE id = " + orderId + " LIMIT 1");
        if (recs != null && !recs.isEmpty()) {
            return recs.getJSONObject(0);
        }
        return null;
    }

    private List<JSONObject> loadOrderDetails(Long orderId) {
        JSONArray recs = queryInternal(
                "SELECT id, posicion__c, catelogNumber__c, quantity__c, requestedDeliveryDate__c, "
                        + "shipToAddress__c, customerId__c.kunnr__c kunnr FROM orderDetail__c WHERE order__c = " + orderId);
        if (recs == null || recs.isEmpty()) {
            return Collections.emptyList();
        }
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < recs.size(); i++) {
            list.add(recs.getJSONObject(i));
        }
        return list;
    }
}