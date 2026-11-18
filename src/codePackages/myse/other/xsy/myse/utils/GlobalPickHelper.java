package other.xsy.myse.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.http.RkhdHttpClient;
import com.rkhd.platform.sdk.http.RkhdHttpData;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用选项集（global picks）读取与匹配。
 *
 * <p><b>用途</b>：SAP 单据同步时，把 SAP 传来的「创建人」系统标识（如 {@code NEW_MYSE_SYS}）
 * 转成业务可读的名称（如 {@code MYSE}）。</p>
 *
 * <p><b>接口</b>：{@code GET /rest/metadata/v2.0/settings/globalPicks/{选项集apiKey}}</p>
 * <pre>
 *   → { "code":"0", "data": { "records": { "apiKey":"cjr__c", "custom":true,
 *        "pickOption":[ { "optionApiKey":"CBFS_SYS", "optionLabel":"CBFS", "optionCode":1, ... }, ... ] } } }
 * </pre>
 *
 * <p><b>匹配口径</b>（老板 2026-10-22 确认）：<b>trim + 精确匹配</b>。
 * 匹配上 → 赋 optionLabel；<b>没匹配上 → 原样填 SAP 传的值</b>。</p>
 *
 * <p><b>为什么要 trim</b>：选项集里存在带尾随空格的 apiKey（实测 {@code "E_FSE_SYS "}），
 * 而 SAP 传的是 {@code E_FSE_SYS}，不 trim 会漏匹配。</p>
 *
 * <p><b>缓存</b>：选项集是低频变更的租户配置，静态缓存 + TTL（默认 30 分钟），
 * 避免每次单据同步都发一次 HTTP。</p>
 *
 * <p><b>容错</b>：接口调不通 / 解析失败 / 选项集不存在 → 返回空映射，
 * 调用方据此<b>原样填 SAP 的值</b>，绝不影响单据落库。</p>
 */
public class GlobalPickHelper {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 通用选项集接口路径前缀 */
    private static final String GLOBAL_PICK_PATH = "/rest/metadata/v2.0/settings/globalPicks/";

    /** 创建人通用选项集 apiKey */
    public static final String PICK_CREATOR = "cjr__c";

    /** 选项集缓存：选项集apiKey → (optionApiKey → optionLabel) */
    private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<String, Map<String, String>>();

    /** 选项集加载时间戳：选项集apiKey → 加载时刻（毫秒） */
    private static final Map<String, Long> LOADED_AT = new ConcurrentHashMap<String, Long>();

    /** 缓存有效期（30 分钟） */
    private static final long CACHE_TTL = 30L * 60L * 1000L;

    /** 防止并发重复拉取 */
    private static final Object LOCK = new Object();

    private GlobalPickHelper() {
    }

    /**
     * 用通用选项集把 SAP 传的值转成 label。
     *
     * @param pickApiKey 选项集 apiKey（如 {@link #PICK_CREATOR}）
     * @param rawValue   SAP 传的原值
     * @return 匹配上 → optionLabel；<b>未匹配 / 取不到选项集 → 原样返回 rawValue</b>；
     *         rawValue 为 null → 返回 null
     */
    public static String matchGlobalPick(String pickApiKey, String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String v = rawValue.trim();
        if (v.isEmpty()) {
            return rawValue;
        }
        Map<String, String> map = getPickMap(pickApiKey);
        if (map.isEmpty()) {
            // 选项集取不到（接口异常/不存在）→ 原样填，不影响单据落库
            logger.warn("通用选项集取不到，原样填值: pick=" + pickApiKey + ", value=" + rawValue);
            return rawValue;
        }
        String label = map.get(v);
        if (label == null || label.trim().isEmpty()) {
            logger.info("通用选项集未匹配到 apiKey，原样填值: pick=" + pickApiKey + ", value=" + rawValue);
            return rawValue;
        }
        logger.info("通用选项集匹配成功: pick=" + pickApiKey + ", apiKey=" + v + " → label=" + label);
        return label;
    }

    /**
     * 取通用选项集的 optionApiKey → optionLabel 映射（带缓存）。
     *
     * @param pickApiKey 选项集 apiKey
     * @return 映射；取不到时返回空 Map（不抛异常）
     */
    public static Map<String, String> getPickMap(String pickApiKey) {
        if (pickApiKey == null || pickApiKey.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        String key = pickApiKey.trim();
        Long loadedAt = LOADED_AT.get(key);
        if (loadedAt != null && (System.currentTimeMillis() - loadedAt) < CACHE_TTL) {
            Map<String, String> cached = CACHE.get(key);
            return cached == null ? Collections.<String, String>emptyMap() : cached;
        }
        synchronized (LOCK) {
            // 双检：可能已在等锁期间加载过
            loadedAt = LOADED_AT.get(key);
            if (loadedAt != null && (System.currentTimeMillis() - loadedAt) < CACHE_TTL) {
                Map<String, String> cached = CACHE.get(key);
                return cached == null ? Collections.<String, String>emptyMap() : cached;
            }
            Map<String, String> map = fetchPickMap(key);
            CACHE.put(key, map);
            LOADED_AT.put(key, System.currentTimeMillis());
            return map;
        }
    }

    /**
     * 调平台接口拉取选项集并解析。
     *
     * @return optionApiKey(trim) → optionLabel；失败返回空 Map
     */
    private static Map<String, String> fetchPickMap(String pickApiKey) {
        Map<String, String> map = new HashMap<String, String>();
        String respStr = null;
        try {
            RkhdHttpData httpData = RkhdHttpData.newBuilder()
                    .callString(GLOBAL_PICK_PATH + pickApiKey)
                    .callType("GET")
                    .build();
            respStr = RkhdHttpClient.instance().performRequest(httpData);
        } catch (Exception e) {
            logger.error("调用通用选项集接口失败: pick=" + pickApiKey + " err=" + e.getMessage(), e);
            return map;
        }
        if (respStr == null || respStr.trim().isEmpty()) {
            logger.warn("通用选项集接口返回空: pick=" + pickApiKey);
            return map;
        }
        try {
            JSONObject resp = JSON.parseObject(respStr);
            JSONObject records = extractRecordsObject(resp);
            if (records == null) {
                logger.warn("通用选项集响应里没有 records: pick=" + pickApiKey + ", resp=" + respStr);
                return map;
            }
            JSONArray pickOption = records.getJSONArray("pickOption");
            if (pickOption == null) {
                logger.warn("通用选项集响应里没有 pickOption: pick=" + pickApiKey);
                return map;
            }
            for (int i = 0; i < pickOption.size(); i++) {
                JSONObject o = pickOption.getJSONObject(i);
                if (o == null) {
                    continue;
                }
                String optionApiKey = o.getString("optionApiKey");
                String optionLabel = o.getString("optionLabel");
                if (optionApiKey == null || optionApiKey.trim().isEmpty()) {
                    continue;
                }
                if (optionLabel == null) {
                    optionLabel = "";
                }
                // key 统一 trim（选项集里存在带尾随空格的 apiKey，如 "E_FSE_SYS "）
                map.put(optionApiKey.trim(), optionLabel);
            }
            logger.info("通用选项集加载完成: pick=" + pickApiKey + ", 选项数=" + map.size());
        } catch (Exception e) {
            logger.error("解析通用选项集响应失败: pick=" + pickApiKey + ", resp=" + respStr, e);
            return map;
        }
        return map;
    }

    /**
     * 从响应里取出 records 对象。
     * <p>注意：本接口的 {@code data.records} 是<b>对象</b>（不是数组），
     * 因为一个选项集只有一条记录。</p>
     */
    private static JSONObject extractRecordsObject(JSONObject resp) {
        if (resp == null) {
            return null;
        }
        Object data = resp.get("data");
        if (data instanceof JSONObject) {
            JSONObject dj = (JSONObject) data;
            Object rec = dj.get("records");
            if (rec instanceof JSONObject) {
                return (JSONObject) rec;
            }
            // 兼容 records 是数组的情况（多选项集时）
            if (rec instanceof JSONArray && !((JSONArray) rec).isEmpty()) {
                return ((JSONArray) rec).getJSONObject(0);
            }
            return null;
        }
        if (data instanceof JSONArray && !((JSONArray) data).isEmpty()) {
            return ((JSONArray) data).getJSONObject(0);
        }
        // 兜底：直接在顶层找 records
        Object rec = resp.get("records");
        if (rec instanceof JSONObject) {
            return (JSONObject) rec;
        }
        if (rec instanceof JSONArray && !((JSONArray) rec).isEmpty()) {
            return ((JSONArray) rec).getJSONObject(0);
        }
        return null;
    }

    /** 手动清缓存（选项集在平台上改过后，下次调用立即生效）。 */
    public static void clearCache() {
        CACHE.clear();
        LOADED_AT.clear();
    }
}
