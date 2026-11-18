package other.myse.general.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.http.RkhdHttpClient;
import com.rkhd.platform.sdk.http.RkhdHttpData;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import other.myse.general.utils.HttpUtil;
import other.myse.general.utils.PermissionChangeGuard;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用模块 - 批量删除接口
 * <p>
 * <b>销售易 API 路径</b>：POST /rest/data/v2.0/scripts/api/myse/general/batch-delete
 * <p>
 * <b>功能</b>：根据前端传入的「对象 API Key」和「数据 id 数组」，批量删除指定对象的记录。
 * 接口完全泛型，不依赖任何具体实体模型类；删除走平台内部 OpenAPI（RkhdHttpClient 系统鉴权），
 * 绕过 API 上下文对象级权限隔离。
 * <p>
 * <b>请求体（JSON）</b>：
 * <pre>
 * {
 *   "apiKey": "order__c",                 // 必填，对象 API Key（区分大小写，如 order__c）
 *   "ids": [4432415566119883, 4432415566119884]  // 必填，要删除的数据 id 数组（正数）
 * }
 * </pre>
 * <p>
 * <b>返回（成功）</b>：
 * <pre>
 * {
 *   "success": true,
 *   "data": {
 *     "apiKey": "order__c",
 *     "total": 2,
 *     "deleted": 2,
 *     "failed": 0,
 *     "failedItems": []
 *   }
 * }
 * </pre>
 * <b>返回（部分失败）</b>：success=false，errorCode=PARTIAL_FAILURE，data 中列出失败项。
 */
@RestApi(baseUrl = "/myse/general")
public class GeneralBatchDeleteApi {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 单次请求允许删除的最大记录数，防止误传超大数组 */
    private static final int MAX_IDS = 2000;

    @RestMapping(value = "/batch-delete", method = RequestMethod.POST)
    public String batchDelete(@RestBeanParam(name = "json") String body) {
        try {
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = PermissionChangeGuard.checkPermissionChanged("GeneralBatchDeleteApi");
            if (guard != null) {
                return guard;
            }
            JSONObject request = JSON.parseObject(body);
            if (request == null) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            // 1. 校验 apiKey
            String apiKey = request.getString("apiKey");
            if (apiKey == null || apiKey.trim().isEmpty()) {
                return HttpUtil.fail("PARAM_INVALID", "缺少必填字段 apiKey（对象 API Key）").toJSONString();
            }
            apiKey = apiKey.trim();

            // 2. 校验 ids
            Object idsObj = request.get("ids");
            if (!(idsObj instanceof JSONArray)) {
                return HttpUtil.fail("PARAM_INVALID", "ids 必须是数组").toJSONString();
            }
            JSONArray ids = (JSONArray) idsObj;
            if (ids.isEmpty()) {
                return HttpUtil.fail("PARAM_INVALID", "ids 不能为空数组").toJSONString();
            }
            if (ids.size() > MAX_IDS) {
                return HttpUtil.fail("PARAM_INVALID", "单次删除数量不能超过 " + MAX_IDS + " 条").toJSONString();
            }

            // 3. 逐条解析 id（兼容数字与字符串两种传法）
            List<Long> idList = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                Object el = ids.get(i);
                Long id;
                try {
                    id = (el instanceof Number)
                            ? ((Number) el).longValue()
                            : Long.parseLong(String.valueOf(el).trim());
                } catch (Exception e) {
                    return HttpUtil.fail("PARAM_INVALID",
                            "ids 第 " + (i + 1) + " 个元素不是有效的数字 id: " + el).toJSONString();
                }
                if (id <= 0) {
                    return HttpUtil.fail("PARAM_INVALID",
                            "ids 第 " + (i + 1) + " 个元素 id 必须为正数").toJSONString();
                }
                idList.add(id);
            }

            // 4. 逐条调用平台内部 OpenAPI 删除
            int deleted = 0;
            JSONArray failedItems = new JSONArray();
            for (Long id : idList) {
                String url = "/rest/data/v2.0/xobjects/" + apiKey + "/" + id;
                RkhdHttpData data = RkhdHttpData.newBuilder()
                        .callString(url)
                        .callType("DELETE")
                        .build();
                String resp;
                try {
                    resp = RkhdHttpClient.instance().performRequest(data);
                } catch (Exception e) {
                    logger.error("MYSE-GENERAL 删除异常 apiKey=" + apiKey + " id=" + id, e);
                    JSONObject f = new JSONObject();
                    f.put("id", id);
                    f.put("message", e.getMessage());
                    failedItems.add(f);
                    continue;
                }
                if (isDeleteSuccess(resp)) {
                    deleted++;
                    logger.info("MYSE-GENERAL 删除成功 apiKey=" + apiKey + " id=" + id);
                } else {
                    JSONObject f = new JSONObject();
                    f.put("id", id);
                    f.put("message", resp == null ? "空响应" : resp);
                    failedItems.add(f);
                    logger.warn("MYSE-GENERAL 删除失败 apiKey=" + apiKey + " id=" + id + " resp=" + resp);
                }
            }

            // 5. 组装返回
            JSONObject data = new JSONObject();
            data.put("apiKey", apiKey);
            data.put("total", idList.size());
            data.put("deleted", deleted);
            data.put("failed", failedItems.size());
            data.put("failedItems", failedItems);

            if (failedItems.isEmpty()) {
                return HttpUtil.success(data).toJSONString();
            }
            JSONObject partial = new JSONObject();
            partial.put("success", false);
            partial.put("errorCode", "PARTIAL_FAILURE");
            partial.put("errorMessage", "部分记录删除失败（成功 " + deleted + " / 共 " + idList.size() + "）");
            partial.put("data", data);
            return partial.toJSONString();

        } catch (Exception e) {
            logger.error("MYSE-GENERAL 批量删除执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    /**
     * 判断 OpenAPI 删除响应是否成功。
     * 成功判定（宽松）：空响应、无法解析、无 error 字段、success 不为 false 均视为成功。
     */
    private boolean isDeleteSuccess(String resp) {
        if (resp == null || resp.trim().isEmpty()) {
            return true;
        }
        try {
            JSONObject j = JSON.parseObject(resp);
            if (j == null) {
                return true;
            }
            if (j.containsKey("error") && j.get("error") != null) {
                return false;
            }
            if (j.containsKey("success") && !j.getBooleanValue("success")) {
                return false;
            }
            return true;
        } catch (Exception e) {
            // 非 JSON 响应体按成功处理
            return true;
        }
    }
}
