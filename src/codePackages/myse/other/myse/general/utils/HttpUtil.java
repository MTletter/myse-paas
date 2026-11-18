package other.myse.general.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.http.CommonData;
import com.rkhd.platform.sdk.http.CommonHttpClient;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;

/**
 * SAP PI HTTP 工具类
 * <p>
 * 封装 mySE 向 SAP PI 发送 REST 请求的通用逻辑。
 * 所有接口均采用 POST + application/json 的方式调用 SAP PI 适配器。
 * </p>
 */
public class HttpUtil {

    private static final Logger logger = LoggerFactory.getLogger();

    /**
     * 发送 POST 请求到 SAP PI
     *
     * @param piUrl       SAP PI 接口完整 URL
     * @param requestBody 请求体对象（将被序列化为 JSON）
     * @return SAP PI 返回的 JSON 响应体；若请求失败则返回包含 error 字段的 JSONObject
     */
    public static JSONObject post(String piUrl, Object requestBody,String token) {
        try {
            String bodyJson = JSON.toJSONString(requestBody);

            CommonData commonData = new CommonData("POST", bodyJson,piUrl);
            commonData.putHeader("Content-Type", "application/json");
            // 仅在 token 非空时附加 Authorization（空 header 会让部分网关按地址解析失败）
            if (token != null && !token.trim().isEmpty()) {
                commonData.putHeader("Authorization", token);
            }
            CommonHttpClient httpClient = new CommonHttpClient();
            String responseStr = httpClient.performRequest(commonData);
            logger.info("responseStr:"+responseStr);
            if (responseStr == null || responseStr.isEmpty()) {
                logger.error("SAP PI 返回空响应, url=" + piUrl);
                JSONObject err = new JSONObject();
                err.put("error", "empty_response");
                err.put("url", piUrl);
                return err;
            }

            return JSON.parseObject(responseStr);

        } catch (Exception e) {
            logger.error("SAP PI 请求异常, url=" + piUrl, e);
            JSONObject err = new JSONObject();
            err.put("error", "request_exception");
            err.put("message", e.getMessage());
            return err;
        }
    }

    /**
     * 发送 POST 请求到 SAP PI
     *
     * @param piUrl       SAP PI 接口完整 URL
     * @param requestBody 请求体对象（将被序列化为 JSON）
     * @return SAP PI 返回的 JSON 响应体；若请求失败则返回包含 error 字段的 JSONObject
     */
    public static JSONObject post(String piUrl, Object requestBody) {
        try {
            String bodyJson = JSON.toJSONString(requestBody);

            CommonData commonData = new CommonData( "POST", bodyJson,piUrl);
            commonData.putHeader("Content-Type", "application/json");
            commonData.putHeader("Accept", "application/json");
            // TODO: 如 SAP PI 需要 Basic Auth，在此添加 Authorization header
            CommonHttpClient httpClient = new CommonHttpClient();
            String responseStr = httpClient.performRequest(commonData);

            if (responseStr == null || responseStr.isEmpty()) {
                logger.error("SAP PI 返回空响应, url=" + piUrl);
                JSONObject err = new JSONObject();
                err.put("error", "empty_response");
                err.put("url", piUrl);
                return err;
            }

            return JSON.parseObject(responseStr);

        } catch (Exception e) {
            logger.error("SAP PI 请求异常, url=" + piUrl, e);
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
