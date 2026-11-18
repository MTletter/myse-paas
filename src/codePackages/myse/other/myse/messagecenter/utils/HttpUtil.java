package other.myse.messagecenter.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.http.CommonData;
import com.rkhd.platform.sdk.http.CommonHttpClient;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;

/**
 * 消息中心包 HTTP / 响应工具类
 */
public class HttpUtil {

    private static final Logger logger = LoggerFactory.getLogger();

    /**
     * 发送 POST 请求（预留，当前接口以 XOQL 查询为主，不需要外呼）
     */
    public static JSONObject post(String url, Object requestBody) {
        try {
            String bodyJson = JSON.toJSONString(requestBody);
            CommonData commonData = new CommonData(url, "POST", bodyJson);
            commonData.putHeader("Content-Type", "application/json");
            CommonHttpClient httpClient = new CommonHttpClient();
            String responseStr = httpClient.performRequest(commonData);
            if (responseStr == null || responseStr.isEmpty()) {
                JSONObject err = new JSONObject();
                err.put("error", "empty_response");
                return err;
            }
            return JSON.parseObject(responseStr);
        } catch (Exception e) {
            logger.error("HTTP 请求异常, url=" + url, e);
            JSONObject err = new JSONObject();
            err.put("error", "request_exception");
            err.put("message", e.getMessage());
            return err;
        }
    }

    /**
     * 构造统一的成功响应
     */
    public static JSONObject success(Object data) {
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("data", data);
        return resp;
    }

    /**
     * 构造统一的失败响应
     */
    public static JSONObject fail(String code, String message) {
        JSONObject resp = new JSONObject();
        resp.put("success", false);
        resp.put("errorCode", code);
        resp.put("errorMessage", message);
        return resp;
    }
}
