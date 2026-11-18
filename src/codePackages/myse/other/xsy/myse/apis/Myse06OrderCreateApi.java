package other.xsy.myse.apis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.data.model.Order__c;
import com.rkhd.platform.sdk.service.XObjectService;
import other.xsy.myse.utils.HttpUtil;
import other.xsy.myse.utils.PiConfig;

import java.util.*;

import static other.xsy.myse.utils.JsonValues.nullSafe;
import static other.xsy.myse.utils.StringUtil.padKunnr;

/**
 * MYSE-06 创建销售订单（mySE → PI → SAP 出站）
 * <p>
 * 调用 SAP BAPI：Z_BAPI_CREATE_SALESORDER_NEW
 * </p>
 *
 * <p><b>销售易 API 路径</b>：/myse/order/create</p>
 * <p><b>入参</b>：{"id": 订单(order__c)的 id}</p>
 * <p><b>逻辑</b>：加载 order__c 主记录 + orderDetail__c 明细，封装成 SAP 订单创建报文
 * （CAB_PEDIDO 头 + POS_PEDIDO 行 + ITEM_BOM_NO / ITEM_CHAR_VALUE / ITEM_INST_ID / ITEM_REF 配置块），
 * 出站调用 SAP PI，返回 SAP 响应。</p>
 *
 * <p><b>SAP 报文结构（与用户提供的结构一致）</b>：
 * <pre>
 * {
 *   "CAB_PEDIDO": { 头 35 字段 },
 *   "ITEM_BOM_NO":     { "item": [ {...} ] },   // BOM 配置行（order__c 无源 → 空数组）
 *   "ITEM_CHAR_VALUE": { "item": [ {...} ] },   // 特性值（order__c 无源 → 空数组）
 *   "ITEM_INST_ID":    { "item": [ {...} ] },   // 实例号（order__c 无源 → 空数组）
 *   "ITEM_REF":        { "item": [ {...} ] },   // 配置引用（无源 → 空数组）
 *   "POS_PEDIDO": { "item": [ {行 30 字段} ] }
 * }
 * </pre>
 * </p>
 *
 * <p>内部 XOQL / 客户代码与物料号反查 / 报文构建见 {@link OrderApiBase}（与 MYSE-05 共用）。
 * 创建报文 ORDERNUMBER 传订单号、行级 SHIPTONAME 传空串。</p>
 */
@RestApi(baseUrl = "/myse")
public class Myse06OrderCreateApi extends OrderApiBase {

    private static String PI_URL;
    private static String API_TOKEN;

    @Override
    protected String tag() {
        return "MYSE-06";
    }

    /** 延迟加载 PI 配置（不在 static 块里做 SDK 查询，避免类初始化阶段类加载失败）。 */
    private void ensureConfigLoaded() {
        if (PI_URL == null) {
            PiConfig.Entry c = PiConfig.get("MYSE-06");
            if (c != null) {
                PI_URL = c.getUrl();
                API_TOKEN = c.getToken();
            }
        }
    }

    @RestMapping(value = "/order/create", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        Long orderId = null;
        try {
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            JSONObject requestBody = JSON.parseObject(body);
            Object idObj = requestBody.get("id");
            if (idObj == null) {
                return HttpUtil.fail("PARAM_ERROR", "订单id(id)不能为空").toJSONString();
            }
            if (idObj instanceof Number) {
                orderId = ((Number) idObj).longValue();
            } else {
                try {
                    orderId = Long.parseLong(idObj.toString().trim());
                } catch (Exception e) {
                    return HttpUtil.fail("PARAM_ERROR", "订单id(id)必须为数字").toJSONString();
                }
            }
            logger.info("MYSE-06 创建销售订单 请求 orderId=" + orderId);
            ensureConfigLoaded();

            // 1) 加载订单主记录（内部 XOQL，绕过 API 上下文对象级权限隔离，按字段名读取避免缺 getter 编译问题）
            JSONObject order = loadOrder(orderId);

            // 2) 加载订单明细
            List<JSONObject> details = loadOrderDetails(orderId);
            logger.info("MYSE-06 明细数: " + (details == null ? 0 : details.size()));

            // 3) 反查 售达方/送达方客户代码(kunnr) 与 物料号(productCode__c)，kunnr 不足10位前补0（SAP CHAR10）
            String kunnr = padKunnr(queryKunnr(asLong(order, "purchaseId__c")));
            Map<Long, String> productCodeMap = queryProductCodes(details);

            // 4) 封装 SAP 报文
            JSONObject sapPayload = buildOrderPayload(order, details, kunnr, productCodeMap,
                    nullSafe(order.getString("orderNumberAutoGenerate__c")), true);
            logger.info("MYSE-06 SAP报文: " + sapPayload.toJSONString());

            // 5) 出站调用 SAP PI
            JSONObject piResponse = HttpUtil.post(PI_URL, sapPayload, API_TOKEN);

            // 7) SAP 业务校验失败（RETURNCODE=E）
            if (piResponse != null && piResponse.containsKey("RESP_PEDIDO")
                    && "E".equals(piResponse.getJSONObject("RESP_PEDIDO").getString("RETURNCODE"))) {
                String errMsg = piResponse.getJSONObject("RESP_PEDIDO").getString("RETURNMESSAGE");
                logger.error("MYSE-06 SAP 返回业务失败: " + piResponse.toJSONString());
                updateSyncStatus(orderId, 3, errMsg);
                return piResponse.toString();
            }

            logger.info("MYSE-06 创建销售订单成功（已提交 SAP）");
            String number=piResponse.getJSONObject("RESP_PEDIDO").getString("VBELN");
            updateSyncStatus(orderId, 2,number );
            return piResponse.toString();

        } catch (Exception e) {
            logger.error("MYSE-06 执行异常", e);
            updateSyncStatus(orderId, 3, e.getMessage());
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    // ===================== 数据加载（内部 XOQL，系统鉴权）=====================

    private JSONObject loadOrder(Long orderId) {
        JSONArray recs = queryInternal(
                "SELECT id, orderNumber__c, e_Docnumber__c, e_Bukrs__c, purchaseId__c, "
                        + "purchaseOrder__c, userName__c, requireDeliverydate__c, quotation__c, "
                        + "shipToAddress__c, totalPrice__c,orderNumberAutoGenerate__c,shipToCode__c.accountName shipToName,shipToCode__c.kunnr__c shipToCode " +
                        " FROM order__c WHERE id = " + orderId + " LIMIT 1");
        if (recs != null && !recs.isEmpty()) {
            return recs.getJSONObject(0);
        }
        return null;
    }

    private List<JSONObject> loadOrderDetails(Long orderId) {
        JSONArray recs = queryInternal(
                "SELECT id, posicion__c, catelogNumber__c, quantity__c, requestedDeliveryDate__c, "
                        +                 "unitnetprice__c, extendedProfilePrice__c, discountPercentage__c, sales_Unit__c, "
                        + "shipToAddress__c, selectionId__c, lineItemStatus__c, customerId__c.kunnr__c kunnr "
                        + " FROM orderDetail__c WHERE order__c = " + orderId);
        if (recs == null || recs.isEmpty()) {
            return Collections.emptyList();
        }
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < recs.size(); i++) {
            list.add(recs.getJSONObject(i));
        }
        return list;
    }

    /**
     * 回写订单同步状态：syncStatus__c（2=成功，3=失败）、syncDescription__c（接口返回信息/异常信息）。
     * 内部 try-catch，回写失败不影响主流程返回。
     */
    private void updateSyncStatus(Long oid, Integer status, String description) {
        if (oid == null) return;
        try {
            Order__c o = new Order__c();
            o.setId(oid);
            o.setSyncStatus__c(status);
            o.setSyncDescription__c(description == null ? "" : description);
            if(status==2){
                o.setOrderStatus__c(1);
            }
            if(status==3){
                o.setOrderStatus__c(17);
            }
            XObjectService.instance().update(o,true);
            logger.info("MYSE-06 同步状态已更新 orderId=" + oid + " status=" + status);
        } catch (Exception e) {
            logger.error("MYSE-06 更新同步状态失败 orderId=" + oid, e);
        }
    }
}