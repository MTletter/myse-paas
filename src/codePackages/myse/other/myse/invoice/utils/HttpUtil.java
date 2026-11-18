package other.myse.invoice.utils;

import com.alibaba.fastjson.JSONObject;

/**
 * HTTP 响应工具类（other.myse.invoice）
 */
public class HttpUtil {

    public static JSONObject success(Object data) {
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("data", data);
        return resp;
    }

    public static JSONObject fail(String code, String message) {
        JSONObject resp = new JSONObject();
        resp.put("success", false);
        resp.put("errorCode", code);
        resp.put("errorMessage", message);
        return resp;
    }
}
