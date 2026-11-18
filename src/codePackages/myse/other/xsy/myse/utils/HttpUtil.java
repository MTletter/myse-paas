package other.xsy.myse.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.http.CommonHttpClient;
import com.rkhd.platform.sdk.http.CommonData;
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

    /** 连接超时（毫秒） */
    private static final int CONNECT_TIMEOUT_MS = 300000;
    /** 读取超时（毫秒） */
    private static final int READ_TIMEOUT_MS = 300000;
    /** 最大尝试次数：1 次正常请求 + 1 次重试 */
    private static final int MAX_ATTEMPTS = 2;
    /** 重试间隔（毫秒） */
    private static final long RETRY_INTERVAL_MS = 1000L;

    /**
     * 发送 POST 请求到 SAP PI（带超时重试：共尝试 2 次）
     *
     * @param piUrl       SAP PI 接口完整 URL
     * @param requestBody 请求体对象（将被序列化为 JSON）
     * @param token       Authorization 头（可空）
     * @return SAP PI 返回的 JSON 响应体；若请求失败则返回包含 error 字段的 JSONObject
     */
    public static JSONObject post(String piUrl, Object requestBody,String token) {
        String bodyJson = null;
        try {
            bodyJson = JSON.toJSONString(requestBody);
        } catch (Exception e) {
            logger.error("SAP PI 请求体序列化失败, url=" + piUrl, e);
            JSONObject err = new JSONObject();
            err.put("error", "serialize_exception");
            err.put("message", e.getMessage());
            return err;
        }
        return postWithRetry(piUrl, bodyJson, true, token);
    }

    /**
     * 超时重试外壳：仅在判定为<b>超时</b>时才重试 1 次，其他错误（HTTP 报错、空响应、JSON 解析失败）直接返回不重试。
     * 注意：POST 非幂等，重试可能导致 SAP 侧重复单据，故这里只对"明确超时"（请求大概率未被业务处理）重试。
     */
    private static JSONObject postWithRetry(String piUrl, String bodyJson, boolean tokenMode, String token) {
        JSONObject lastResult = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Attempt a = tokenMode ? execToken(piUrl, bodyJson, token) : execPlain(piUrl, bodyJson);
            lastResult = a.result;

            boolean failed = a.error != null
                    || a.result == null
                    || a.result.containsKey("error");
            if (!failed) {
                return a.result;
            }
            if (!isTimeout(a.error)) {
                return a.result;
            }
            if (attempt >= MAX_ATTEMPTS) {
                break;
            }
            logger.warn("SAP PI 请求超时，准备重试: url=" + piUrl + ", attempt=" + attempt + "/" + MAX_ATTEMPTS
                    + ", error=" + (a.error == null ? "empty/timeout" : String.valueOf(a.error)));
            sleepQuietly(RETRY_INTERVAL_MS);
        }
        logger.error("SAP PI 请求超时且重试 " + (MAX_ATTEMPTS - 1) + " 次后仍失败: url=" + piUrl);
        return lastResult;
    }

    /** 单次请求：带 token + 自定义超时参数的模式（原 post(url, body, token) 的实现）。 */
    private static Attempt execToken(String piUrl, String bodyJson, String token) {
        Throwable error = null;
        JSONObject result = null;
        try {
            CommonData commonData = new CommonData("POST", bodyJson, piUrl);
            commonData.putHeader("Content-Type", "application/json");
            // 仅在 token 非空时附加 Authorization（空 header 会让部分网关按地址解析失败）
            if (token != null && !token.trim().isEmpty()) {
                commonData.putHeader("Authorization", token);
            }
            CommonHttpClient httpClient = CommonHttpClient.instance(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
            String responseStr = httpClient.performRequest(commonData);
            logger.info("responseStr:" + responseStr);
            result = parse(piUrl, responseStr);
        } catch (Exception e) {
            error = e;
            logger.error("SAP PI 请求异常, url=" + piUrl, e);
            result = errorJson("request_exception", e.getMessage(), piUrl);
        }
        return new Attempt(result, error);
    }

    /** 单次请求：无 token 模式（原 post(url, body) 的实现）。 */
    private static Attempt execPlain(String piUrl, String bodyJson) {
        Throwable error = null;
        JSONObject result = null;
        try {
            CommonData commonData = new CommonData("POST", bodyJson, piUrl);
            commonData.putHeader("Content-Type", "application/json");
            commonData.putHeader("Accept", "application/json");
            CommonHttpClient httpClient = new CommonHttpClient();
            String responseStr = httpClient.performRequest(commonData);
            result = parse(piUrl, responseStr);
        } catch (Exception e) {
            error = e;
            logger.error("SAP PI 请求异常, url=" + piUrl, e);
            result = errorJson("request_exception", e.getMessage(), piUrl);
        }
        return new Attempt(result, error);
    }

    private static JSONObject parse(String piUrl, String responseStr) {
        if (responseStr == null || responseStr.isEmpty()) {
            logger.error("SAP PI 返回空响应, url=" + piUrl);
            return errorJson("empty_response", null, piUrl);
        }
        return JSON.parseObject(responseStr);
    }

    private static JSONObject errorJson(String error, String message, String piUrl) {
        JSONObject err = new JSONObject();
        err.put("error", error);
        err.put("url", piUrl);
        if (message != null) {
            err.put("message", message);
        }
        return err;
    }

    /**
     * 判定异常是否为超时：沿 cause 链找 SocketTimeoutException / ConnectTimeoutException /
     * InterruptedIOException / InterruptedException，或消息含 timed out / timeout（大小写不敏感）。
     */
    private static boolean isTimeout(Throwable e) {
        Throwable t = e;
        for (int depth = 0; depth < 10 && t != null; depth++) {
            String cls = t.getClass().getName().toLowerCase();
            if (cls.contains("timeoutexception")
                    || cls.contains("timedoutexception")
                    || cls.contains("interruptedioexception")
                    || cls.contains("interruptedexception")) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("timed out") || lower.contains("timeout") || lower.contains("read timed")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** 单次请求结果：result = 返回的 JSONObject（可能含 error），error = 抛出的原始异常（用于超时判定）。 */
    private static class Attempt {
        private final JSONObject result;
        private final Throwable error;

        private Attempt(JSONObject result, Throwable error) {
            this.result = result;
            this.error = error;
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
