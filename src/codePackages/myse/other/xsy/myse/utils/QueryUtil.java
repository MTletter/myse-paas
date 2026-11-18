package other.xsy.myse.utils;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.exception.ScriptBusinessException;
import com.rkhd.platform.sdk.http.RkhdHttpClient;
import com.rkhd.platform.sdk.http.RkhdHttpData;
import com.rkhd.platform.sdk.http.handler.ResponseBodyHandlers;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 单选项（picklist / multipicklist）描述查询。
 *
 * <p>只保留实际在用的两个方法：平台 v2.0 描述接口
 * {@code GET /rest/data/v2.0/xobjects/{apiKey}/description} 的两种映射方向。
 * 2026-11-16 精简前还有 20 多个从老项目带过来的查询方法，全部无调用，已删除
 * （需要时可从 {@code backups/myse_src_20261116_092737} 取回）。</p>
 */
public class QueryUtil {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 拉取对象描述里的字段 items（selectitem / checkitem）→ {字段 apiKey: {选项 apiKey: value}} */
    public static Map<String, Map<String, Integer>> getPicklistDescriptionApiKeyToValue(String apiKey) throws ScriptBusinessException {
        try {
            JSONArray fields = queryFields(apiKey);
            Map<String, Map<String, Integer>> map = new HashMap<>();
            for (Object obj : fields) {
                JSONObject field = (JSONObject) obj;
                String type = field.getString("type");
                if ("picklist".equals(type)) {
                    Map<String, Integer> keyMap = new HashMap<>();
                    for (Object it : field.getJSONArray("selectitem")) {
                        JSONObject option = (JSONObject) it;
                        keyMap.put(option.getString("apiKey"), option.getInteger("value"));
                    }
                    map.put(field.getString("apiKey"), keyMap);
                } else if ("multipicklist".equals(type)) {
                    Map<String, Integer> keyMap = new HashMap<>();
                    for (Object it : field.getJSONArray("checkitem")) {
                        JSONObject option = (JSONObject) it;
                        keyMap.put(option.getString("apiKey"), option.getInteger("value"));
                    }
                    map.put(field.getString("apiKey"), keyMap);
                }
            }
            return map;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new ScriptBusinessException(e);
        }
    }

    /** 拉取对象描述里的字段 items → {字段 apiKey: {选项 value: label}} */
    public static Map<String, Map<Integer, String>> getPicklistDescriptionValueToLabel(String apiKey) throws ScriptBusinessException {
        try {
            JSONArray fields = queryFields(apiKey);
            Map<String, Map<Integer, String>> map = new HashMap<>();
            for (Object obj : fields) {
                JSONObject field = (JSONObject) obj;
                String type = field.getString("type");
                if ("picklist".equals(type)) {
                    Map<Integer, String> keyMap = new HashMap<>();
                    for (Object it : field.getJSONArray("selectitem")) {
                        JSONObject option = (JSONObject) it;
                        keyMap.put(option.getInteger("value"), option.getString("label"));
                    }
                    map.put(field.getString("apiKey"), keyMap);
                } else if ("multipicklist".equals(type)) {
                    Map<Integer, String> keyMap = new HashMap<>();
                    for (Object it : field.getJSONArray("checkitem")) {
                        JSONObject option = (JSONObject) it;
                        keyMap.put(option.getInteger("value"), option.getString("label"));
                    }
                    map.put(field.getString("apiKey"), keyMap);
                }
            }
            return map;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new ScriptBusinessException(e);
        }
    }

    /** 调用描述接口，取 data.fields；非 200 抛业务异常。 */
    private static JSONArray queryFields(String apiKey) throws Exception {
        RkhdHttpData data = RkhdHttpData.newBuilder()
                .callType("GET")
                .callString("/rest/data/v2.0/xobjects/" + apiKey + "/description")
                .build();
        JSONObject result = RkhdHttpClient.instance().execute(data, ResponseBodyHandlers.ofJSON());
        if (result.getInteger("code") == null || 200 != result.getInteger("code")) {
            throw new ScriptBusinessException(result.getString("msg"));
        }
        return result.getJSONObject("data").getJSONArray("fields");
    }

    private QueryUtil() {
    }
}
