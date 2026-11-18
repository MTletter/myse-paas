package other.myse.general.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.http.RkhdHttpClient;
import com.rkhd.platform.sdk.http.RkhdHttpData;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 平台内部 XOQL 查询工具（general 包内共享）。
 *
 * <p><b>用途</b>：部分自定义对象（productOrg__c / productPrice__c / saleorg__c /
 * accountSalesOrg__c / dictDetail__c 等）在 API 上下文中无法被 XoqlService 读取
 * （对象级权限隔离），必须走 RkhdHttpClient 内部路由（系统鉴权）执行 XOQL。
 * 此前 ProductSearchApi / ProductVerifyApi 各自内联了一份几乎相同的实现，
 * 现统一收敛到本类，通道选择的知识只保留这一处。</p>
 *
 * <p><b>容错约定</b>：查询异常时返回空集合、打 error 日志，不阻断调用方主流程
 * （与既有各 API 的行为一致）。</p>
 */
public final class XoqlHelper {

    private static final Logger logger = LoggerFactory.getLogger();

    private XoqlHelper() {
    }

    /**
     * 执行平台内部 XOQL（RkhdHttpClient，系统鉴权），返回完整记录行。
     * 用于 API 上下文只读受限的对象。查询异常时返回空集合（不阻断主流程）。
     */
    public static List<JSONObject> rows(String xoql) {
        List<JSONObject> rows = new ArrayList<JSONObject>();
        try {
            RkhdHttpData httpData = RkhdHttpData.newBuilder()
                    .callString("/rest/data/v2.0/query/xoql")
                    .callType("POST")
                    .formData("xoql", xoql)
                    .build();
            String respStr = RkhdHttpClient.instance().performRequest(httpData);
            if (respStr == null || respStr.trim().isEmpty()) {
                return rows;
            }
            JSONArray records = extractRecords(JSON.parseObject(respStr));
            if (records == null) {
                logger.warn("XoqlHelper 响应缺少 records, xoql=" + xoql + " resp=" + respStr);
                return rows;
            }
            for (int i = 0; i < records.size(); i++) {
                rows.add(records.getJSONObject(i));
            }
        } catch (Exception e) {
            logger.error("XoqlHelper 内部XOQL查询失败 xoql=" + xoql, e);
        }
        return rows;
    }

    /**
     * 执行平台内部 XOQL，取指定字段的 Long 值列表（保持返回顺序，跳过非法值）。
     */
    public static List<Long> ids(String xoql, String field) {
        List<Long> ids = new ArrayList<Long>();
        for (JSONObject r : rows(xoql)) {
            Object v = r.get(field);
            if (v != null) {
                try {
                    ids.add(Long.parseLong(v.toString()));
                } catch (Exception e) {
                    logger.warn("XoqlHelper 跳过非法 id: " + v);
                }
            }
        }
        return ids;
    }

    /**
     * 从内部 XOQL 响应兼容抽取 records（兼容 records / data.records / data 为数组三种包装）。
     */
    public static JSONArray extractRecords(JSONObject resp) {
        if (resp == null) {
            return null;
        }
        Object records = resp.get("records");
        if (records instanceof JSONArray) {
            return (JSONArray) records;
        }
        Object data = resp.get("data");
        if (data instanceof JSONObject) {
            Object r = ((JSONObject) data).get("records");
            if (r instanceof JSONArray) {
                return (JSONArray) r;
            }
        } else if (data instanceof JSONArray) {
            return (JSONArray) data;
        }
        return null;
    }

    /**
     * Long 集合拼接为 IN 子句内容："1,2,3"。
     */
    public static String joinLongs(Collection<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    /**
     * Integer 集合拼接为 IN 子句内容："1,2,3"。
     */
    public static String joinInts(Collection<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (Integer id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }
}
