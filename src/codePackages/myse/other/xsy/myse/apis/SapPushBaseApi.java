package other.xsy.myse.apis;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.data.model.MessageCenter__c;
import com.rkhd.platform.sdk.model.BatchOperateResult;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.XoqlService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SAP 单据推送接口<b>公共基类</b>。
 *
 * <p><b>为什么抽</b>（2026-10-22）：Myse14 / Myse15 各写了一份完全一样的
 * 类型转换、日期解析、批量查询工具方法，单是重复就占掉 370 多行。
 * 抽到这里后，两个子类直接继承，<b>调用点一行都不用改</b>。</p>
 *
 * <p><b>放这里而不是 utils 的原因</b>：子类调用的是<b>实例方法</b>（非 static），
 * 继承即可用；放进静态工具类则要改所有调用点加类名前缀，改动面大、风险高。</p>
 *
 * <p><b>日志前缀</b>：各子类有自己的 TAG，基类通过 {@link #tag()} 取，
 * 不在基类放 TAG 字段（否则子类同名字段会遮蔽父类，导致日志打出 null）。</p>
 */
public abstract class SapPushBaseApi {

    protected static final Logger logger = LoggerFactory.getLogger();

    /** IN 查询 / 批量写入分批大小 */
    protected static final int QUERY_BATCH_SIZE = 100;
    protected static final int WRITE_BATCH_SIZE = 200;

    /** 子类返回自己的日志 TAG（如 MYSE-14 / MYSE-15） */
    protected abstract String tag();

    // ==================== 取值 / 类型转换 ====================

    /** 取值并 trim；空白串和 "null" 视为 null */
    protected String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() || "null".equalsIgnoreCase(t) ? null : t;
    }

    /** JSONObject 取字段并转 String */
    protected String asString(JSONObject o, String key) {
        return o == null ? null : o.getString(key);
    }

    /** XOQL 响应里的 records（兼容 data.records 数组 / 对象、顶层 records 等多种形态） */
    protected JSONArray extractRecords(JSONObject resp) {
        if (resp == null) {
            return null;
        }
        Object data = resp.get("data");
        if (data instanceof JSONArray) {
            return (JSONArray) data;
        }
        if (data instanceof JSONObject) {
            Object rec = ((JSONObject) data).get("records");
            if (rec instanceof JSONArray) {
                return (JSONArray) rec;
            }
            if (rec instanceof JSONObject) {
                JSONArray one = new JSONArray();
                one.add(rec);
                return one;
            }
        }
        Object rec = resp.get("records");
        if (rec instanceof JSONArray) {
            return (JSONArray) rec;
        }
        if (rec instanceof JSONObject) {
            JSONArray one = new JSONArray();
            one.add(rec);
            return one;
        }
        return null;
    }

    /** 对象 / 单元素数组 → JSONArray */
    protected JSONArray normalizeToArray(Object obj) {
        JSONArray arr = new JSONArray();
        if (obj == null) {
            return arr;
        }
        if (obj instanceof JSONArray) {
            return (JSONArray) obj;
        }
        arr.add(obj);
        return arr;
    }

    /**
     * 取报文节点里的条目数组。
     * <p>兼容两种报文形态：单对象（{@code segment: {item: {...}}}）与数组（批量推送）。</p>
     */
    protected JSONArray getItems(JSONObject root, String segment) {
        if (root == null) {
            return null;
        }
        JSONObject wrapper = root.getJSONObject(segment);
        if (wrapper == null) {
            return null;
        }
        return normalizeToArray(wrapper.get("item"));
    }

    /**
     * 取明细行的送达方客户号，兼容两种字段名：
     * <ul>
     *   <li>{@code CUSTOMERID} —— 老报文格式</li>
     *   <li>{@code ITEMCUSTOMERID} —— 新批量格式（T_OUTPUT 包装）里明细用的是这个名字</li>
     * </ul>
     * 两者都取不到时返回 null（调用方再回退到头级 CUSTOMERID）。
     */
    protected String itemCustomerCode(JSONObject item) {
        String v = trimToNull(asString(item, "CUSTOMERID"));
        if (v == null) {
            v = trimToNull(asString(item, "ITEMCUSTOMERID"));
        }
        return v;
    }

    /**
     * SAP 数值 → Double。
     * <p>先过滤掉千分位逗号、空格等非数值字符，剩余部分无法解析时返回 null。</p>
     */
    protected Double parseDouble(Object v) {
        String s = trimToNull(v+"");
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '+' || c == '-' || c == '.' || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        String clean = sb.toString().trim();
        if (clean.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(clean);
        } catch (Exception e) {
            logger.warn(tag() + " 数值解析失败: " + s);
            return null;
        }
    }

    // ==================== 时间解析 ====================

    /** SAP 日期（yyyyMMdd / yyyy-MM-dd / yyyyMMddHHmmss 等）→ 毫秒时间戳 */
    protected Long parseDate(Object v) {
        String s = trimToNull(v+"");
        if (s == null) {
            return null;
        }
        String[] patterns = {"yyyyMMdd", "yyyy-MM-dd", "yyyyMMddHHmmss", "yyyy-MM-dd HH:mm:ss",
                "yyyy/MM/dd", "yyyyMMddHHmm", "yyyy-MM-dd'T'HH:mm:ss"};
        for (String p : patterns) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(p);
                sdf.setLenient(false);
                return sdf.parse(s).getTime();
            } catch (Exception ignore) {
                // 换下一个格式
            }
        }
        // 兜底：纯数字（已是毫秒/秒级时间戳）
        if (s.matches("\\d{10,13}")) {
            try {
                long n = Long.parseLong(s);
                return s.length() <= 10 ? n * 1000L : n;
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }

    /** XOQL 返回的日期字段 → 毫秒时间戳（兼容 {"time":...} / 字符串 / 数字） */
    protected Long extractTs(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        if (o instanceof JSONObject) {
            Object t = ((JSONObject) o).get("time");
            if (t == null) {
                t = ((JSONObject) o).get("value");
            }
            return t == null ? null : extractTs(t);
        }
        if (o instanceof JSONArray && !((JSONArray) o).isEmpty()) {
            return extractTs(((JSONArray) o).get(0));
        }
        return parseDate(o);
    }

    /** 时间戳 → yyyy-MM-dd */
    protected String formatDate(Long ts) {
        if (ts == null) {
            return null;
        }
        return new SimpleDateFormat("yyyy-MM-dd").format(new Date(ts));
    }

    // ==================== SQL 片段 ====================

    /** 单引号转义（防 SQL 注入） */
    protected String escape(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("'", "''");
    }

    /** 构造带引号的 IN 子句，如 ('a','b') */
    protected String buildInClause(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append("'").append(v == null ? "" : v.replace("'", "''")).append("'");
        }
        return sb.toString();
    }

    /** 批量预查询：字段值 → 记录 id（分批 IN，循环内零反查 SQL） */
    protected Map<String, Long> queryIdsByFieldIn(String entity, String field, Set<String> values) {
        Map<String, Long> map = new HashMap<String, Long>();
        if (values == null || values.isEmpty()) {
            return map;
        }
        List<String> list = new ArrayList<>(values);
        for (int from = 0; from < list.size(); from += QUERY_BATCH_SIZE) {
            List<String> part = list.subList(from, Math.min(from + QUERY_BATCH_SIZE, list.size()));
            String xoql = "SELECT id, " + field + " FROM " + entity + " WHERE " + field
                    + " IN (" + buildInClause(part) + ")";
            logger.info(tag() + " SQL: " + xoql);
            try {
                QueryResult<JSONObject> qr = XoqlService.instance().query(xoql);
                if (qr != null && qr.getRecords() != null) {
                    for (JSONObject rec : qr.getRecords()) {
                        if (rec == null) {
                            continue;
                        }
                        Object id = rec.get("id");
                        Object v = rec.get(field);
                        if (id != null && v != null) {
                            map.put(v.toString(), Long.parseLong(id.toString()));
                        }
                    }
                }
                logger.info(tag() + " 预查询 " + entity + "." + field + ": 入参=" + part.size()
                        + " 命中=" + map.size());
            } catch (Exception e) {
                logger.error(tag() + " 预查询失败 entity=" + entity + " " + field + ": " + e.getMessage(), e);
            }
        }
        return map;
    }

    /** 报文里有值则记一条 info 日志（查问题用，不打印每条全量报文） */
    protected void logIfPresent(JSONObject o, String sapField, String label) {
        String v = trimToNull(asString(o, sapField));
        if (v != null) {
            logger.info(tag() + " " + label + "=" + v);
        }
    }

    /** 收集去重集合的并集 */
    protected static void addAll(Set<String> target, Set<String> src) {
        if (target != null && src != null) {
            target.addAll(src);
        }
    }

    /** 有序去重集合 */
    protected static Set<String> newSet() {
        return new LinkedHashSet<String>();
    }

    // ==================== 地址拼装 ====================

    /**
     * 把报文里的地址分散字段拼成一行文本。
     * <p>取 SHIPTONAME / ADDRESSLINE1~5 / CITY / STATEPROVREGION / POSTALCODE / COUNTRYCODE，
     * 非空项用空格连接；全空返回 null（调用方据此不赋值）。</p>
     */
    protected String buildAddress(JSONObject o) {
        if (o == null) {
            return null;
        }
        String[] parts = {
                asString(o, "SHIPTONAME"), asString(o, "ADDRESSLINE1"), asString(o, "ADDRESSLINE2"),
                asString(o, "ADDRESSLINE3"), asString(o, "ADDRESSLINE4"), asString(o, "ADDRESSLINE5"),
                asString(o, "CITY"), asString(o, "STATEPROVREGION"), asString(o, "POSTALCODE"),
                asString(o, "COUNTRYCODE")
        };
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            p = trimToNull(p);
            if (p != null) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(p);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ==================== 源报价单预查询 ====================

    /**
     * 按报价单号批量预查 quote__c（分批 IN，循环内零反查 SQL）。
     *
     * @param quoteNumbers 报价单号集合
     * @return 报价单号 → 该报价单记录（含 id / ownerId）
     */
    protected Map<String, JSONObject> queryQuotesByNumbers(Set<String> quoteNumbers) {
        Map<String, JSONObject> map = new HashMap<String, JSONObject>();
        if (quoteNumbers == null || quoteNumbers.isEmpty()) {
            return map;
        }
        List<String> list = new ArrayList<>(quoteNumbers);
        for (int from = 0; from < list.size(); from += QUERY_BATCH_SIZE) {
            List<String> part = list.subList(from, Math.min(from + QUERY_BATCH_SIZE, list.size()));
            String xoql = "SELECT id, quoteNumberAutoGenerate__c, ownerId, verifier__c,quoteStatus__c,creatorOne__c,purchaserId__c FROM quote__c"
                    + " WHERE quoteNumberAutoGenerate__c IN (" + buildInClause(part) + ")";
            logger.info(tag() + " SQL: " + xoql);
            try {
                QueryResult<JSONObject> qr = XoqlService.instance().query(xoql);
                if (qr != null && qr.getRecords() != null) {
                    for (JSONObject rec : qr.getRecords()) {
                        if (rec == null) {
                            continue;
                        }
                        String num = rec.getString("quoteNumberAutoGenerate__c");
                        if (num != null) {
                            map.put(num, rec);
                        }
                    }
                }
                logger.info(tag() + " 源报价单预查询: 入参=" + part.size() + " 命中=" + map.size());
            } catch (Exception e) {
                logger.error(tag() + " 预查询源报价单失败: " + e.getMessage(), e);
            }
        }
        return map;
    }

    // ==================== 消息中心通知 ====================

    /** 消息已读标志：2=否（未读） */
    protected static final int MSG_UNREAD = 2;

    /**
     * 子类返回消息中心的业务类型 id（配置集 {@code entityType.MessageCenter}）。
     * <p>两个接口的 static 块各自读一次配置集，这里只把值交出来复用，
     * 免得基类再发一次请求。</p>
     */
    protected abstract Long messageCenterBusiType();

    /**
     * 构造一条消息中心记录（不写库）。
     *
     * @param name      标题，<b>必填</b>，不填会导致消息记录插入失败
     * @param msgType   消息类型：1 待办 / 2 订阅 / 3 转单 / 4 公告 / 5 权限变更
     */
    protected MessageCenter__c newNotification(String name, String content, Long accountId, Long ownerId,
                                               String dataId, String objectKey, Integer msgType) {
        MessageCenter__c mc = new MessageCenter__c();
        mc.setEntityType(messageCenterBusiType());
        mc.setName(name);
        mc.setAccount__c(accountId);
        mc.setPublishTime__c(new Date().getTime());
        mc.setContent__c(content);
        mc.setMessageType__c(msgType);
        mc.setDataId__c(dataId);
        mc.setObjectKey__c(objectKey);
        mc.setOwnerId(ownerId);
        mc.setIsRead__c(MSG_UNREAD);
        logger.info(tag() + " 消息中心通知: name=" + name + ", content=" + content
                + ", accountId=" + accountId + ", ownerId=" + ownerId);
        return mc;
    }

    /** 批量写入消息中心记录（分批，失败只记日志不影响主流程）。 */
    protected void batchInsertNotifications(List<MessageCenter__c> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (int from = 0; from < list.size(); from += WRITE_BATCH_SIZE) {
            List<MessageCenter__c> part = list.subList(from, Math.min(from + WRITE_BATCH_SIZE, list.size()));
            try {
                BatchOperateResult br = XObjectService.instance().insert(part, true);
                logger.info(tag() + " 消息中心通知写入: 条数=" + part.size() + " success=" + br.getSuccess()
                        + " err=" + br.getErrorMessage());
            } catch (Exception e) {
                logger.error(tag() + " 消息中心通知写入失败 条数=" + part.size() + " err=" + e.getMessage(), e);
            }
        }
    }
}
