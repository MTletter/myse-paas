package other.myse.general.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.http.CommonData;
import com.rkhd.platform.sdk.http.CommonHttpClient;
import com.rkhd.platform.sdk.http.RkhdBlob;
import com.rkhd.platform.sdk.http.RkhdHttpClient;
import com.rkhd.platform.sdk.http.RkhdHttpData;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import other.myse.general.utils.HttpUtil;
import other.myse.general.utils.PermissionChangeGuard;

import java.util.Locale;

/**
 * 数据批量导出接口
 * <p><b>销售易 API 路径</b>：POST /rest/data/v2.0/scripts/api/general/export/data</p>
 *
 * <p><b>业务说明</b>：封装平台 OpenAPI「数据批量导出」
 * （POST /rest/data/v2.0/bulk/export/exportData），前端直接调用本接口即可，
 * 不需要自己拼平台导出报文、也不需要自己处理下载地址。</p>
 *
 * <p><b>处理逻辑</b>：</p>
 * <ol>
 *   <li>请求体原样透传给平台导出接口（queryParams / entityApiKey / itemIds /
 *       encoding / fileType 等，字段含义与平台文档一致）；</li>
 *   <li>平台返回中带下载地址（{@code fileName + presignedUrl / fileUrl}）时，
 *       服务端把导出文件下载下来，<b>以文件流直接返回前端</b>（浏览器即可保存/下载）；</li>
 *   <li>平台返回中只有任务标识（{@code jobId / logId}）时，说明数据量大走了异步导出，
 *       此时<b>原样把 jobId / logId 返回前端</b>，由前端提示用户等待导出完成通知。</li>
 * </ol>
 *
 */
@RestApi(baseUrl = "/general")
public class DataExportApi {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 平台 OpenAPI：数据批量导出 */
    private static final String EXPORT_PATH = "/rest/data/v2.0/bulk/export/exportData";

    /** 下载地址判定用 */
    private static final String HTTP_PREFIX = "http://";
    private static final String HTTPS_PREFIX = "https://";

    /** 日志里截断长 URL，避免刷屏 */
    private static final int LOG_URL_MAX = 160;

    @RestMapping(value = "/export/data", method = RequestMethod.POST)
    public Object execute(@RestBeanParam(name = "json") String body) {
        long startAt = System.currentTimeMillis();
        try {
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = PermissionChangeGuard.checkPermissionChanged("DataExportApi");
            if (guard != null) {
                return guard;
            }
            // ===== 1) 入参校验（业务字段只校验必填项，其余原样透传） =====
            if (body == null || body.trim().isEmpty()) {
                return json(HttpUtil.fail("PARAM_EMPTY", "请求体不能为空"));
            }
            JSONObject req;
            try {
                req = JSON.parseObject(body);
            } catch (Exception e) {
                return json(HttpUtil.fail("PARAM_ERROR", "请求体不是合法的 JSON"));
            }
            if (req == null) {
                return json(HttpUtil.fail("PARAM_ERROR", "请求体不能为空"));
            }
            String entityApiKey = trim(req.getString("entityApiKey"));
            if (entityApiKey.isEmpty()) {
                return json(HttpUtil.fail("PARAM_ERROR", "entityApiKey（实体 API Key）不能为空"));
            }
            JSONObject queryParams = req.getJSONObject("queryParams");
            String dataView = (queryParams == null) ? "" : trim(queryParams.getString("dataView"));
            logger.info("DataExport 导出请求 entityApiKey=" + entityApiKey
                    + ", dataView=" + dataView
                    + ", fileType=" + trim(req.getString("fileType"))
                    + ", encoding=" + trim(req.getString("encoding")));

            // ===== 2) 调用平台批量导出接口，请求体原样透传 =====
            String respText = callExport(body);
            if (respText == null || respText.trim().isEmpty()) {
                logger.error("DataExport 导出接口返回空响应");
                return json(HttpUtil.fail("EXPORT_ERROR", "导出接口返回空响应"));
            }
            JSONObject resp = null;
            try {
                resp = JSON.parseObject(respText);
            } catch (Exception ignore) {
                // 非 JSON 响应，下面统一按错误处理
            }
            if (resp == null) {
                logger.error("DataExport 导出接口返回非 JSON 响应：" + abbreviate(respText));
                return json(HttpUtil.fail("EXPORT_ERROR", "导出接口返回非 JSON 响应"));
            }
            String code = trim(resp.getString("code"));
            if (!"200".equals(code)) {
                String msg = trim(resp.getString("msg"));
                logger.error("DataExport 导出失败 code=" + code + ", msg=" + msg);
                return json(HttpUtil.fail("EXPORT_ERROR",
                        msg.isEmpty() ? ("导出失败，code=" + code) : msg));
            }
            JSONObject data = resp.getJSONObject("data");
            if (data == null) {
                logger.error("DataExport 导出接口未返回 data");
                return json(HttpUtil.fail("EXPORT_ERROR", "导出接口未返回 data"));
            }

            String fileName = trim(data.getString("fileName"));
            String presignedUrl = trim(data.getString("presignedUrl"));
            String fileUrl = trim(data.getString("fileUrl"));
            String jobId = trim(data.getString("jobId"));
            String logId = trim(data.getString("logId"));

            // ===== 3) 没有下载地址 → 数据量大走了异步，任务标识直接返回前端 =====
            if (presignedUrl.isEmpty() && fileUrl.isEmpty()) {
                JSONObject out = new JSONObject();
                out.put("async", true);
                out.put("jobId", jobId);
                out.put("logId", logId);
                out.put("fileName", fileName);
                logger.info("DataExport 异步导出 jobId=" + jobId + ", logId=" + logId
                        + ", costMs=" + (System.currentTimeMillis() - startAt));
                return json(HttpUtil.success(out));
            }

            // ===== 4) 有下载地址 → 服务端下载，以文件流返回前端 =====
            RkhdBlob blob = downloadExportFile(fileName, presignedUrl, fileUrl);
            if (blob == null || blob.getBytes() == null || blob.getBytes().length == 0) {
                logger.error("DataExport 导出文件下载失败 fileName=" + fileName);
                return json(HttpUtil.fail("DOWNLOAD_ERROR", "导出文件下载失败"));
            }
            logger.info("DataExport 同步导出完成 fileName=" + blob.getFileName()
                    + ", size=" + blob.getBytes().length
                    + ", costMs=" + (System.currentTimeMillis() - startAt));
            return blob;

        } catch (Exception e) {
            logger.error("DataExport 执行异常", e);
            return json(HttpUtil.fail("INTERNAL_ERROR", String.valueOf(e.getMessage())));
        }
    }

    /**
     * 调用平台 OpenAPI 数据批量导出接口。
     * 走 RkhdHttpClient，平台内部自动带上鉴权，url 用相对路径。
     *
     * @param bodyJson 前端原始请求体（原样透传）
     * @return 平台响应文本；请求异常时返回 null
     */
    private String callExport(String bodyJson) {
        try {
            RkhdHttpData httpData = new RkhdHttpData();
            httpData.setCall_type("POST");
            httpData.setCallString(EXPORT_PATH);
            httpData.setBody(bodyJson);
            httpData.putHeader("Content-Type", "application/json;charset=UTF-8");
            return RkhdHttpClient.instance().performRequest(httpData);
        } catch (Exception e) {
            logger.error("DataExport 调用导出接口异常", e);
            return null;
        }
    }

    /**
     * 下载导出文件。
     * <p>优先用 presignedUrl（S3 预签名直链）；没有或下载失败时退回 fileUrl
     * （平台相对地址，形如 /json/global_download/authDownload.action?url=...）。</p>
     *
     * @return 下载到的文件流；全部失败返回 null
     */
    private RkhdBlob downloadExportFile(String fileName, String presignedUrl, String fileUrl) {
        RkhdBlob blob = null;

        if (isAbsoluteUrl(presignedUrl)) {
            blob = downloadExternal(presignedUrl);
        }
        if (blob == null && !fileUrl.isEmpty()) {
            blob = isAbsoluteUrl(fileUrl) ? downloadExternal(fileUrl) : downloadInternal(fileUrl);
        }
        if (blob == null) {
            return null;
        }

        // 平台 S3 预签名地址下载时拿不到原始文件名，用导出接口返回的 fileName 兜底
        if (trim(blob.getFileName()).isEmpty() && !fileName.isEmpty()) {
            blob.setFileName(fileName);
        }
        return blob;
    }

    /** 下载外部地址（http/https，如 S3 预签名直链）。 */
    private RkhdBlob downloadExternal(String url) {
        try {
            CommonData commonData = new CommonData("GET", "", url);
            RkhdBlob blob = CommonHttpClient.instance().downBlob(commonData);
            if (blob != null && blob.getBytes() != null && blob.getBytes().length > 0) {
                return blob;
            }
            logger.error("DataExport 外链下载为空 url=" + abbreviate(url));
        } catch (Exception e) {
            logger.error("DataExport 外链下载失败 url=" + abbreviate(url), e);
        }
        return null;
    }

    /** 下载平台内部地址（相对路径）。 */
    private RkhdBlob downloadInternal(String path) {
        try {
            RkhdHttpData httpData = new RkhdHttpData();
            httpData.setCall_type("GET");
            httpData.setCallString(path);
            RkhdBlob blob = RkhdHttpClient.instance().downBlob(httpData);
            if (blob != null && blob.getBytes() != null && blob.getBytes().length > 0) {
                return blob;
            }
            logger.error("DataExport 内部下载为空 path=" + abbreviate(path));
        } catch (Exception e) {
            logger.error("DataExport 内部下载失败 path=" + abbreviate(path), e);
        }
        return null;
    }

    /** JSON 对象转字符串（保持与其它接口一致的 String 返回体）。 */
    private String json(JSONObject obj) {
        return obj.toJSONString();
    }

    private boolean isAbsoluteUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith(HTTP_PREFIX) || lower.startsWith(HTTPS_PREFIX);
    }

    private String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** 日志用：截断过长文本 */
    private String abbreviate(String s) {
        if (s == null) return "";
        String v = s.trim();
        return v.length() <= LOG_URL_MAX ? v : (v.substring(0, LOG_URL_MAX) + "...");
    }
}
