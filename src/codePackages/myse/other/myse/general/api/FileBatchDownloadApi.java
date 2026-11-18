package other.myse.general.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.http.RkhdBlob;
import com.rkhd.platform.sdk.http.RkhdHttpClient;
import com.rkhd.platform.sdk.http.RkhdHttpData;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import other.myse.general.utils.HttpUtil;
import other.myse.general.utils.PermissionChangeGuard;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 批量文件下载接口
 * <p><b>销售易 API 路径</b>：POST /rest/data/v2.0/scripts/api/general/file/batch-download</p>
 *
 * <p><b>业务说明</b>：前端传入多个文件 id，服务端逐个调用平台 OpenAPI 文件下载接口
 * （GET /rest/file/v2.0/file/actions/files?fileIds=&lt;id&gt;&amp;outType=0）获取二进制文件流，
 * 将每个文件流做 Base64 编码后统一以 JSON 返回给前端（JSON 无法承载原始二进制，故用 Base64）。
 * 单个文件下载失败不会中断整批，会在该文件条目中标记 success=false。</p>
 *
 * <p><b>入参</b>（二选一写法）：</p>
 * <pre>{
 *   "fileIds": ["1578967492378636", "1486800316809249"]   // 数组
 * }
 * 或
 * {
 *   "fileIds": "1578967492378636,1486800316809249"          // 逗号分隔字符串
 * }</pre>
 *
 * <p><b>出参</b>：</p>
 * <pre>{
 *   "success": true,
 *   "data": {
 *     "total": 2,             // 请求的文件数（去重后）
 *     "successCount": 2,      // 成功下载数
 *     "failCount": 0,         // 失败数
 *     "files": [
 *       {
 *         "fileId": "1578967492378636",
 *         "fileName": "合同.pdf",
 *         "size": 20480,               // 原始字节数
 *         "contentBase64": "JVBERi0xLjQK...",  // 文件流的 Base64
 *         "success": true
 *       },
 *       {
 *         "fileId": "999",
 *         "success": false,
 *         "errorMessage": "下载失败: ..."
 *       }
 *     ]
 *   }
 * }</pre>
 */
@RestApi(baseUrl = "/general")
public class FileBatchDownloadApi {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 单次批量下载的最大文件数（平台文件下载接口 JSON 模式上限为 50，此处与之对齐） */
    private static final int MAX_FILE_COUNT = 50;

    /** 平台 OpenAPI 文件下载接口（二进制流模式，单个文件 id） */
    private static final String FILE_DOWNLOAD_PATH = "/rest/file/v2.0/file/actions/files";

    @RestMapping(value = "/file/batch-download", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        try {
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = PermissionChangeGuard.checkPermissionChanged("FileBatchDownloadApi");
            if (guard != null) {
                return guard;
            }
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            JSONObject req = JSON.parseObject(body);

            // 解析 fileIds：支持数组与逗号分隔字符串两种写法，并去重、去空
            List<String> fileIds = parseFileIds(req);
            if (fileIds.isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "fileIds 不能为空（支持数组或逗号分隔字符串）").toJSONString();
            }
            if (fileIds.size() > MAX_FILE_COUNT) {
                return HttpUtil.fail("PARAM_ERROR",
                        "单次最多下载 " + MAX_FILE_COUNT + " 个文件，当前 " + fileIds.size() + " 个").toJSONString();
            }

            logger.info("FileBatchDownload 开始，文件数=" + fileIds.size() + " ids=" + fileIds);

            // 复用同一个内部 HTTP 客户端
            RkhdHttpClient client = RkhdHttpClient.instance();

            JSONArray files = new JSONArray();
            int successCount = 0;
            for (String fileId : fileIds) {
                JSONObject item = downloadOne(client, fileId);
                if (Boolean.TRUE.equals(item.getBoolean("success"))) {
                    successCount++;
                }
                files.add(item);
            }

            JSONObject data = new JSONObject();
            data.put("total", fileIds.size());
            data.put("successCount", successCount);
            data.put("failCount", fileIds.size() - successCount);
            data.put("files", files);

            logger.info("FileBatchDownload 完成，成功=" + successCount + "/" + fileIds.size());
            return HttpUtil.success(data).toJSONString();

        } catch (Exception e) {
            logger.error("FileBatchDownload 执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    /**
     * 下载单个文件：调用平台 OpenAPI 文件下载接口拿二进制流，Base64 编码。
     * 失败时返回 success=false 的条目，不抛异常，保证批量不中断。
     */
    private JSONObject downloadOne(RkhdHttpClient client, String fileId) {
        JSONObject item = new JSONObject();
        item.put("fileId", fileId);
        try {
            // 二进制流模式：仅支持单个文件 id（outType=0）
            String path = FILE_DOWNLOAD_PATH + "?fileIds=" + fileId + "&outType=0";
            RkhdHttpData downloadData = RkhdHttpData.newBuilder()
                    .callString(path)
                    .callType("GET")
                    .build();

            RkhdBlob blob = client.downBlob(downloadData);
            byte[] bytes = (blob == null) ? null : blob.getBytes();
            String fileName = (blob == null) ? null : blob.getFileName();

            if (bytes == null || bytes.length == 0) {
                item.put("success", false);
                item.put("errorMessage", "文件不存在或返回空，fileId=" + fileId);
                return item;
            }

            // 平台对无效 fileId 会返回 HTTP200 + JSON 错误体（形如 {"err":"file not found","id":...}），
            // 且此时 fileName 为空。因此 fileName 为空视为下载失败，并尝试解析错误信息。
            if (fileName == null || fileName.trim().isEmpty()) {
                String msg = "文件不存在或不可下载";
                try {
                    String errText = new String(bytes, StandardCharsets.UTF_8);
                    JSONObject ej = JSON.parseObject(errText);
                    if (ej != null && ej.getString("err") != null) {
                        msg = ej.getString("err");
                    }
                } catch (Exception ignore) {
                    // 非 JSON 错误体，保留默认提示
                }
                item.put("success", false);
                item.put("errorMessage", msg + "，fileId=" + fileId);
                return item;
            }

            item.put("fileName", fileName);
            item.put("size", bytes.length);
            item.put("contentBase64", Base64.getEncoder().encodeToString(bytes));
            item.put("success", true);
            return item;

        } catch (Exception e) {
            logger.error("FileBatchDownload 单文件下载失败 fileId=" + fileId, e);
            item.put("success", false);
            item.put("errorMessage", "下载失败: " + e.getMessage());
            return item;
        }
    }

    /**
     * 解析入参 fileIds，支持：
     * 1) JSON 数组：["1","2"]
     * 2) 逗号分隔字符串："1,2,3"
     * 去空白、去空值、按插入顺序去重。
     */
    private List<String> parseFileIds(JSONObject req) {
        Set<String> ordered = new LinkedHashSet<>();
        Object raw = req.get("fileIds");
        if (raw instanceof JSONArray) {
            for (Object o : (JSONArray) raw) {
                addId(ordered, o == null ? null : o.toString());
            }
        } else if (raw instanceof String) {
            for (String part : ((String) raw).split(",")) {
                addId(ordered, part);
            }
        }
        return new ArrayList<>(ordered);
    }

    private void addId(Set<String> set, String id) {
        if (id == null) return;
        String v = id.trim();
        if (!v.isEmpty()) set.add(v);
    }
}
