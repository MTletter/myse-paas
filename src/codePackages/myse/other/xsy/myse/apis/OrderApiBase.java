package other.xsy.myse.apis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.http.RkhdHttpClient;
import com.rkhd.platform.sdk.http.RkhdHttpData;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import other.xsy.myse.utils.StringUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static other.xsy.myse.utils.JsonValues.formatDateSAP;
import static other.xsy.myse.utils.JsonValues.nullSafe;
import static other.xsy.myse.utils.StringUtil.padKunnr;

/**
 * 销售订单出站接口（MYSE-05 模拟 / MYSE-06 创建）公共基类。
 *
 * <p>内部 XOQL 查询、客户代码/物料号反查、SAP 订单报文构建原先两个类各写一份（逐行相同），
 * 集中到这里；子类只保留各自的入口流程、数据加载 SQL 与差异逻辑
 * （05 的草稿模式 / 06 的同步状态回写）。</p>
 *
 * <p><b>报文差异由参数表达</b>：模拟时 ORDERNUMBER 传空串、行级不下发 SHIPTONAME；
 * 创建时 ORDERNUMBER 传订单号、行级 SHIPTONAME 传空串（见 {@link #buildOrderPayload}）。</p>
 *
 * <p>日志前缀通过 {@link #tag()} 取子类自己的 TAG（MYSE-05 / MYSE-06）。</p>
 */
public abstract class OrderApiBase {

    protected static final Logger logger = LoggerFactory.getLogger();

    /** 子类返回自己的日志 TAG（MYSE-05 / MYSE-06） */
    protected abstract String tag();

    // ===================== SAP 报文构建 =====================

    /**
     * 将 order__c 主记录 + 明细封装为 SAP 订单报文
     * （05 模拟 Z_BAPI_SIMULATE_SALESORDER_NEW 与 06 创建 Z_BAPI_CREATE_SALESORDER_NEW 结构一致）。
     * 无源字段（CARRIER/ROUTING/VTWEG/SPART 及地址2~5）传空串；
     * 行级 CUSTOMERID 取明细发货地址信息(customerId__c).kunnr__c 并补零到 10 位（口径与 MYSE-08 一致）。
     *
     * @param order                 订单主记录（JSONObject，按字段名读取）
     * @param details               明细列表
     * @param kunnr                 售达方客户代码（已补零）→ PURCHASERID
     * @param productCodeMap        产品 id → productCode__c 映射
     * @param orderNumber           头 ORDERNUMBER：模拟传 ""，创建传订单号
     * @param includeItemShipToName 行级是否下发 SHIPTONAME（空串）：05 不下发，06 下发
     */
    protected JSONObject buildOrderPayload(JSONObject order, List<JSONObject> details,
                                           String kunnr, Map<Long, String> productCodeMap,
                                           String orderNumber, boolean includeItemShipToName) {
        JSONObject root = new JSONObject();

        // ----- CAB_PEDIDO（订单头）-----
        JSONObject cab = new JSONObject();
        cab.put("ORDERNUMBER", orderNumber);
        cab.put("PURCHASERID", nullSafe(kunnr));
        cab.put("PURCHASEORDER", nullSafe(order.getString("purchaseOrder__c")));
        cab.put("CARRIER", "");
        cab.put("ROUTING", "");
        cab.put("CUSTOMERID", nullSafe(padKunnr(order.getString("kunnr"))));
        cab.put("COMPANYCODE", nullSafe(order.getString("e_Bukrs__c")));
        cab.put("CSCACCOUNTRESPONSIBILITY", "");
        cab.put("SALESTERRITORY", "");
        cab.put("COUNTRYSPECIFICDATA", "");
        cab.put("USERNAME", nullSafe(order.getString("userName__c")));
        cab.put("ORDERCREATEDATE", "");
        cab.put("REQUIREDDELIVERYDATE", "");
        cab.put("PROMOTIONID", "");
        // 发货地址：order__c 仅存拼接串 shipToAddress__c，按每行 35 字符顺延填入 ADDRESSLINE1~5
        String shipAddr = nullSafe(order.getString("shipToAddress__c"));
        cab.put("SHIPTONAME", "");
        String[] cabAddrLines = StringUtil.splitAddressLines(shipAddr);
        cab.put("ADDRESSLINE1", cabAddrLines[0]);
        cab.put("ADDRESSLINE2", cabAddrLines[1]);
        cab.put("ADDRESSLINE3", cabAddrLines[2]);
        cab.put("ADDRESSLINE4", cabAddrLines[3]);
        cab.put("ADDRESSLINE5", cabAddrLines[4]);
        cab.put("CITY", "");
        cab.put("STATEPROVREGION", "");
        cab.put("POSTALCODE", "");
        cab.put("COUNTRYCODE", "");
        cab.put("PHONENUMBER", "");
        cab.put("FAXNUMBER", "");
        cab.put("ORDERMARKINGS", "");
        cab.put("WAIVESHIPPINGCHARGEINDICATOR", "");
        cab.put("DROPSHIPINDICATOR", "");
        cab.put("EMERGENCYINDICATOR", "");
        cab.put("COMPLETESHIPINDICATOR", "");
        cab.put("LANGUAGECODE", "");
        cab.put("EMAILADDRESS", "");
        cab.put("VTWEG", "");
        cab.put("SPART", "");
        cab.put("USERCOUNTRYCODE", "");
        cab.put("YYPILCO", "");
        cab.put("YYSTAGE", "");
        cab.put("CONTRACT_NUMBER", "");
        root.put("CAB_PEDIDO", cab);

        // ----- 配置块（order__c 无 BOM/特性数据，空数组）-----
        root.put("ITEM_BOM_NO", emptyItemArray());
        root.put("ITEM_CHAR_VALUE", emptyItemArray());
        root.put("ITEM_INST_ID", emptyItemArray());
        root.put("ITEM_REF", emptyItemArray());

        // ----- POS_PEDIDO（明细数组）-----
        JSONArray posArr = new JSONArray();
        for (JSONObject d : details) {
            JSONObject item = new JSONObject();
            item.put("LINENUMBER", nullSafe(d.getString("posicion__c")));
            item.put("QUANTITY", nullSafe(asLong(d, "quantity__c")));
            Long productId = asLong(d, "catelogNumber__c");
            String matnr = productCodeMap.getOrDefault(productId, "");
            item.put("CATALOGNUMBER", nullSafe(matnr));
            item.put("DONOTSHIPBEFOREDATE", formatDateSAP(asLong(d, "requestedDeliveryDate__c")));
            item.put("LINEMARKINGS", "");
            // 行级发货地址：按每行 35 字符顺延填入 ADDRESSLINE1~5
            String dShip = nullSafe(d.getString("shipToAddress__c"));
            if (includeItemShipToName) {
                item.put("SHIPTONAME", "");
            }
            String[] dAddrLines = StringUtil.splitAddressLines(dShip);
            item.put("ADDRESSLINE1", dAddrLines[0]);
            item.put("ADDRESSLINE2", dAddrLines[1]);
            item.put("ADDRESSLINE3", dAddrLines[2]);
            item.put("ADDRESSLINE4", dAddrLines[3]);
            item.put("ADDRESSLINE5", dAddrLines[4]);
            item.put("CITY", "");
            item.put("STATEPROVREGION", "");
            item.put("POSTALCODE", "");
            item.put("COUNTRYCODE", "");
            item.put("PHONENUMBER", "");
            item.put("FAXNUMBER", "");
            item.put("ROUTING", "");
            item.put("CARRIER", "");
            item.put("DROPSHIPINDICATOR", "");
            item.put("EMERGENCYINDICATOR", "");
            item.put("COMPLETESHIPINDICATOR", "");
            item.put("WAIVESHIPPINGCHARGEINDICATOR", "");
            item.put("YYCRITICAL", "");
            item.put("DLV_GROUP", "");
            item.put("ZTESTREPORT", "");
            item.put("MASTER_PACT_CONFIG_ID", "");
            // 行级送达方客户代码：明细发货地址信息(customerId__c).kunnr__c，补零到 10 位（SAP CHAR10）
            item.put("CUSTOMERID", nullSafe(padKunnr(d.getString("kunnr"))));
            item.put("CONTRACT_NUMBER", "");
            posArr.add(item);
        }
        JSONObject posPedido = new JSONObject();
        posPedido.put("item", posArr);
        root.put("POS_PEDIDO", posPedido);

        return root;
    }

    // ===================== 数据加载（内部 XOQL，系统鉴权）=====================

    /** 反查客户代码：account.id → kunnr__c */
    protected String queryKunnr(Long accountId) {
        if (accountId == null) {
            return "";
        }
        JSONArray recs = queryInternal("SELECT id, kunnr__c FROM account WHERE id = " + accountId + " LIMIT 1");
        if (recs != null && !recs.isEmpty()) {
            return nullSafe(recs.getJSONObject(0).getString("kunnr__c"));
        }
        logger.warn(tag() + " 未找到客户 account id=" + accountId + " 的 kunnr__c");
        return "";
    }

    /** 批量反查物料号：catelogNumber__c(product id) → product.productCode__c */
    protected Map<Long, String> queryProductCodes(List<JSONObject> details) {
        Map<Long, String> map = new HashMap<>();
        Set<Long> productIds = new HashSet<>();
        for (JSONObject d : details) {
            Long pid = asLong(d, "catelogNumber__c");
            if (pid != null) {
                productIds.add(pid);
            }
        }
        if (productIds.isEmpty()) {
            return map;
        }
        StringBuilder inSb = new StringBuilder();
        for (Long pid : productIds) {
            if (inSb.length() > 0) {
                inSb.append(",");
            }
            inSb.append(pid);
        }
        JSONArray recs = queryInternal(
                "SELECT id, productCode__c FROM product WHERE id IN (" + inSb.toString() + ")");
        if (recs != null) {
            for (int i = 0; i < recs.size(); i++) {
                JSONObject p = recs.getJSONObject(i);
                map.put(p.getLong("id"), nullSafe(p.getString("productCode__c")));
            }
        }
        return map;
    }

    /** 内部 XOQL 查询（系统鉴权，绕过 API 上下文对象级权限隔离），返回 records 数组。 */
    protected JSONArray queryInternal(String xoql) {
        try {
            RkhdHttpData httpData = RkhdHttpData.newBuilder()
                    .callString("/rest/data/v2.0/query/xoql")
                    .callType("POST")
                    .formData("xoql", xoql)
                    .build();
            String respStr = RkhdHttpClient.instance().performRequest(httpData);
            if (respStr != null && !respStr.trim().isEmpty()) {
                return extractRecords(JSON.parseObject(respStr));
            }
        } catch (Exception e) {
            logger.warn(tag() + " 内部XOQL查询失败: " + xoql, e);
        }
        return null;
    }

    /** 从内部 XOQL 响应中提取 records 数组（兼容 records 与 data.records 两种结构）。 */
    protected static JSONArray extractRecords(JSONObject resp) {
        if (resp == null) {
            return null;
        }
        if (resp.containsKey("records")) {
            Object r = resp.get("records");
            if (r instanceof JSONArray) {
                return (JSONArray) r;
            }
        }
        if (resp.containsKey("data") && resp.get("data") instanceof JSONObject) {
            Object r = resp.getJSONObject("data").get("records");
            if (r instanceof JSONArray) {
                return (JSONArray) r;
            }
        }
        return null;
    }

    // ===================== 工具方法 =====================

    protected static Long asLong(JSONObject o, String key) {
        if (o == null) {
            return null;
        }
        Object v = o.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    protected static JSONObject emptyItemArray() {
        JSONObject o = new JSONObject();
        o.put("item", new JSONArray());
        return o;
    }
}