package other.xsy.myse.apis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.http.RkhdHttpClient;
import com.rkhd.platform.sdk.http.RkhdHttpData;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;

import java.util.List;

import static other.xsy.myse.utils.JsonValues.formatDateSAP;
import static other.xsy.myse.utils.JsonValues.nullSafe;
import static other.xsy.myse.utils.StringUtil.padItemNumber;
import static other.xsy.myse.utils.StringUtil.padKunnr;

/**
 * 销售订单修改出站接口（MYSE-07 模拟校验 / MYSE-08 确认落库）公共基类。
 *
 * <p>SAP 修改报文构建（ZHEADER_IN + ZITEM_IN）、内部 XOQL 查询、明细摘要与日志工具
 * 原先两个类各写一份（逐行相同），集中到这里；子类只保留各自的入口流程、数据加载 SQL
 * 与差异逻辑（07 的草稿合并/账户校验 / 08 的按 id 选行）。</p>
 *
 * <p>日志前缀通过 {@link #tag()} 取子类自己的 TAG（MYSE-07 / MYSE-08）。</p>
 */
public abstract class OrderModifyApiBase {

    protected static final Logger logger = LoggerFactory.getLogger();

    /** 单条日志最大长度（SQL/报文/响应超长截断，避免刷屏） */
    protected static final int LOG_MAX_LEN = 1000;

    /** 行修改状态：U = 修改 */
    protected static final String MODIFICATION_STATUS_UPDATE = "U";

    /** 国家（order__c 无对应源字段，固定 CN） */
    protected static final String COUNTRY = "CN";

    /** 子类返回自己的日志 TAG（MYSE-07 / MYSE-08） */
    protected abstract String tag();

    // ===================== 封装 SAP 报文 =====================

    /**
     * 组装 SAP 订单修改报文：ZHEADER_IN（订单头）+ ZITEM_IN（订单行，只含前端指定的行）。
     * 07 模拟（Z_BAPI_ORDERMODIF_VALIDATION）与 08 确认（Z_BAPI_ORDERMODIF_RELEASE）结构一致。
     */
    protected JSONObject build(JSONObject order, List<JSONObject> details) {
        JSONObject root = new JSONObject();

        // ---------- ZHEADER_IN：订单头 ----------
        JSONObject header = new JSONObject();
        header.put("COUNTRY", COUNTRY);                                                   // 国家（固定 CN）
        header.put("ORDERNUMBER", nullSafe(order.getString("orderNumberAutoGenerate__c"))); // SAP 订单号（外部单号）
        header.put("REQUIREDDELIVERYDATE", formatDateSAP(toLong(order.get("requireDeliverydate__c")))); // 整单期望发货日期
        header.put("MODIFICATION", "");                                                    // 修改标识（无源，留空）
        root.put("ZHEADER_IN", header);

        // ---------- ZITEM_IN：订单行（只含前端指定的行）----------
        JSONArray items = new JSONArray();
        if (details != null) {
            for (JSONObject d : details) {
                JSONObject item = new JSONObject();
                item.put("ITEM_NUMBER", padItemNumber(nullSafe(d.get("posicion__c"))));               // 行号（补零到 6 位）
                item.put("MODIFICATION_STATUS", MODIFICATION_STATUS_UPDATE);                          // 行修改状态 = U
                item.put("REQUIREDDELIVERYDATE", formatDateSAP(toLong(d.get("requestedDeliveryDate__c")))); // 行期望发货日期
                item.put("CUSTOMERID", nullSafe(padKunnr(d.getString("kunnr"))));                              // 客户代码（发货地址代码）
                items.add(item);
            }
        }
        JSONObject zitem = new JSONObject();
        zitem.put("item", items);
        root.put("ZITEM_IN", zitem);

        return root;
    }

    // ===================== 数据加载（内部 XOQL，系统鉴权）=====================

    /** 内部 XOQL 查询（系统鉴权，绕过 API 上下文对象级权限隔离），返回 records 数组；日志含 SQL/行数/耗时。 */
    protected JSONArray queryInternal(String xoql) {
        long t0 = System.currentTimeMillis();
        try {
            RkhdHttpData httpData = RkhdHttpData.newBuilder()
                    .callString("/rest/data/v2.0/query/xoql")
                    .callType("POST")
                    .formData("xoql", xoql)
                    .build();
            String respStr = RkhdHttpClient.instance().performRequest(httpData);
            JSONArray recs = (respStr == null || respStr.trim().isEmpty())
                    ? null : extractRecords(JSON.parseObject(respStr));
            logger.info(tag() + " SQL: " + abbreviate(xoql)
                    + " → 返回=" + (recs == null ? 0 : recs.size()) + " 行, 耗时=" + (System.currentTimeMillis() - t0) + "ms");
            return recs;
        } catch (Exception e) {
            logger.warn(tag() + " 内部XOQL查询失败, 耗时=" + (System.currentTimeMillis() - t0) + "ms, sql=" + abbreviate(xoql), e);
            return null;
        }
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

    // ===================== 日志 / 工具方法 =====================

    /** 明细摘要：id/行号/期望发货日期/客户代码，便于核对下发内容。 */
    protected String describeDetails(List<JSONObject> details) {
        if (details == null || details.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (JSONObject d : details) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("id=").append(toLong(d.get("id")))
              .append(", 行号=").append(padItemNumber(nullSafe(d.get("posicion__c"))))
              .append(", 期望发货日期=").append(formatDateSAP(toLong(d.get("requestedDeliveryDate__c"))))
              .append(", 客户代码=").append(nullSafe(d.getString("kunnr")));
        }
        return abbreviate(sb.toString());
    }

    /** 日志截断：超过 LOG_MAX_LEN 截断并标注省略字数。 */
    protected static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        if (s.length() <= LOG_MAX_LEN) {
            return s;
        }
        return s.substring(0, LOG_MAX_LEN) + "...(共" + s.length() + "字符，已截断)";
    }

    protected static Long toLong(Object v) {
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
}