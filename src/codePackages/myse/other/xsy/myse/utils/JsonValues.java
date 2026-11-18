package other.xsy.myse.utils;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * SAP 报文取值的通用工具（Myse16 / Myse17 等 SAP 入站接口共用）。
 *
 * <p>把「取值 + 类型归一」的重复逻辑集中到一处，避免每个接口各写一份、行为却不一致。</p>
 *
 * <p><b>为什么必须归一</b>：SAP 报文里同一个含义的字段可能有多种形态
 * —— 数字（{@code 2019}）、带前后空格的字符串（{@code " 19"}）、对象（{@code {"id":1}}），
 * 直接强转会抛 NumberFormatException 或得到 null。</p>
 */
public final class JsonValues {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 兼容 Number/Boolean 等标量的字符串读取（SAP 常把数字字段写成未加引号的数字）。 */
    public static String asString(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String) {
            return (String) v;
        }
        return v.toString();
    }

    /** 读取对象的某个字段为字符串。 */
    public static String asString(JSONObject o, String key) {
        if (o == null) {
            return null;
        }
        return asString(o.get(key));
    }

    /**
     * 归一为 Long：Number 取 longValue；JSONObject 取 id；JSONArray 取首元素递归；
     * 字符串去空白后解析；其余/解析失败 → null。
     */
    public static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof JSONObject) {
            return asLong(((JSONObject) v).get("id"));
        }
        if (v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            return arr.isEmpty() ? null : asLong(arr.get(0));
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 归一为 Long（宽松版）：JSONObject 先取 id 再兜底 value；字符串把字面量 "null" 视为 null。
     * <p>用于 SAP 报文里值可能是 {id:..} / {value:..} 或 "null" 字符串的字段。</p>
     */
    public static Long asLongValue(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof JSONObject) {
            JSONObject j = (JSONObject) v;
            Object inner = j.get("id");
            if (inner == null) {
                inner = j.get("value");
            }
            return inner == null ? null : asLongValue(inner);
        }
        String s = v.toString().trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 去掉首尾空白；空串归一为 null。 */
    public static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    /** 同 {@link #trimToNull(String)}，兼容任意标量（Number/Boolean 等）。 */
    public static String trimToNull(Object v) {
        return trimToNull(asString(v));
    }

    /** null 安全取值：null → ""，其余 → toString()。 */
    public static String nullSafe(Object v) {
        return v == null ? "" : v.toString();
    }

    /** SAP 报文日期格式（yyyy-MM-dd），ThreadLocal 复用避免并发问题。 */
    private static final ThreadLocal<SimpleDateFormat> SAP_DATE_FMT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));

    /**
     * 时间戳 → SAP 报文日期（yyyy-MM-dd）。
     *
     * <p>SAP 回传的时间戳可能已是毫秒、也可能是秒，按位数判定后统一处理；null → ""。</p>
     */
    public static String formatDateSAP(Long ts) {
        if (ts == null) {
            return "";
        }
        long millis = ts > 1000000000000L ? ts : ts * 1000L;
        return SAP_DATE_FMT.get().format(new Date(millis));
    }

    /** SQL 字面量转义（单引号、反斜杠）。 */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    /**
     * 解析金额/数量字符串（如 " 1"、"1"、" +1"）：去掉空格/多余字符后转 Double。
     *
     * @return 解析失败返回 null（不抛异常，避免单条脏数据打断整批处理）
     */
    public static Double parseDouble(Object v) {
        String s = asString(v);
        s = trimToNull(s);
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
            logger.warn("数值解析失败: " + s);
            return null;
        }
    }

    /** 解析日期（yyyy-MM-dd 或 yyyyMMdd）→ 毫秒时间戳（日期字段用 Long 存）。 */
    public static Long parseDate(Object v) {
        String s = asString(v);
        s = trimToNull(s);
        if (s == null) {
            return null;
        }
        String fmt = s.contains("-") ? "yyyy-MM-dd" : "yyyyMMdd";
        try {
            SimpleDateFormat sdf = new SimpleDateFormat(fmt);
            sdf.setLenient(false);
            return sdf.parse(s).getTime();
        } catch (ParseException e) {
            return null;
        }
    }

    /** 解析日期时间（yyyy-MM-dd HH:mm:ss 或 yyyyMMdd HH:mm:ss）→ 毫秒时间戳。 */
    public static Long parseDateTime(String s) {
        s = trimToNull(s);
        if (s == null) {
            return null;
        }
        String fmt = s.contains("-") ? "yyyy-MM-dd HH:mm:ss" : "yyyyMMdd HH:mm:ss";
        try {
            SimpleDateFormat sdf = new SimpleDateFormat(fmt);
            sdf.setLenient(false);
            return sdf.parse(s).getTime();
        } catch (ParseException e) {
            return null;
        }
    }

    /** 读取记录里某个字段的 id（lookup 字段可能是 {id:..} 对象，也可能是标量）。 */
    public static Long readLookupId(JSONObject row, String field) {
        if (row == null) {
            return null;
        }
        Object v = row.get(field);
        if (v instanceof JSONObject) {
            v = ((JSONObject) v).get("id");
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v != null && !v.toString().trim().isEmpty()) {
            try {
                return Long.valueOf(v.toString().trim());
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }

    /** 取 T_XXX.item（单对象或数组统一处理，取第一个）。 */
    public static JSONObject getSingleObject(JSONObject root, String segment) {
        JSONObject wrapper = root == null ? null : root.getJSONObject(segment);
        if (wrapper == null) {
            return null;
        }
        Object item = wrapper.get("item");
        return item instanceof JSONObject ? (JSONObject) item : null;
    }

    /** 取 T_XXX.item 并归一化为数组（单对象包成 1 元素数组，数组原样，null→null）。 */
    public static JSONArray getItems(JSONObject root, String segment) {
        JSONObject wrapper = root == null ? null : root.getJSONObject(segment);
        if (wrapper == null) {
            return null;
        }
        return normalizeToArray(wrapper.get("item"));
    }

    /** 对象归一化为数组：JSONArray 原样；JSONObject 包成 1 元素数组；其余/空 → null。 */
    public static JSONArray normalizeToArray(Object obj) {
        if (obj instanceof JSONArray) {
            return (JSONArray) obj;
        }
        if (obj instanceof JSONObject) {
            JSONArray a = new JSONArray();
            a.add(obj);
            return a;
        }
        return null;
    }

    private JsonValues() {
    }
}
