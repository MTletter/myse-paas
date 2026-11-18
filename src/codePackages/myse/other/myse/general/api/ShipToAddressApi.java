package other.myse.general.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.XoqlService;
import other.myse.general.utils.HttpUtil;
import other.myse.general.utils.PermissionChangeGuard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 发货地址查询接口
 * <p>
 * 入参：
 * - userId（用户id，必填）→ 映射 shipToParty__c.ownerId（所有人）
 * - accountId（账户id，必填）→ 映射 shipToParty__c.purchaseId__c（账户）
 * - pageNum / pageSize（可选，默认 1 / 20，最大 200）
 * 对象：shipToParty__c（发货地址信息）
 * 返回：list[].id / customId__c（发货地址代码）/ shipToAddress__c（发货地址）
 * 固定条件：ownerId = userId AND purchaseId__c = accountId
 * 排序：结果按地址代码（stras = customId__c.stras__c）升序返回
 * </p>
 */
@RestApi(baseUrl = "/general")
public class ShipToAddressApi {

    private static final Logger logger = LoggerFactory.getLogger();

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    @RestMapping(value = "/shipto-address/list", method = RequestMethod.POST)
    public String queryShipToAddress(@RestBeanParam(name = "json") String body) {
        // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
        String guard = PermissionChangeGuard.checkPermissionChanged("ShipToAddressApi");
        if (guard != null) {
            return guard;
        }
        if (body == null || body.isEmpty()) {
            return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
        }

        JSONObject requestBody = JSON.parseObject(body);
        logger.info("requestBody:"+requestBody);
//        Long userId = requestBody.getLong("userId");
        Long accountId = requestBody.getLong("accountId");
        if ( accountId == null) {
            return HttpUtil.fail("PARAM_ERROR", "userId 和 accountId 为必传参数").toJSONString();
        }

        int pageNum = requestBody.getIntValue("pageNum");
        int pageSize = requestBody.getIntValue("pageSize");
        if (pageNum <= 0) pageNum = 1;
        if (pageSize <= 0) pageSize = DEFAULT_PAGE_SIZE;
        if (pageSize > MAX_PAGE_SIZE) pageSize = MAX_PAGE_SIZE;
        int offset = (pageNum - 1) * pageSize;

        // 固定条件：用户id → ownerId，账户id → purchaseId__c
        String where = " purchaseId__c = " + accountId;

        // 分页查询：先取满足条件的全部记录（发货地址数量通常较少，上限 5000 防止极端情况），
        // 在 Java 端按地址代码（stras = customId__c.stras__c）升序排序 + 按 purchaseId__c+customId__c 去重后，
        // 再切片分页，保证跨页的全局有序（XOQL 对关联字段排序支持不稳定，故采用内存排序）
        JSONArray list = new JSONArray();
        List<JSONObject> records = new ArrayList<>();
        try {
            String xoql = "SELECT id, customId__c, purchaseId__c, customId__c.stras__c stras, shipToCity__c, shipToAddress__c,kunnr__c FROM shipToParty__c WHERE " + where
                    + " LIMIT 0, 5000";
            logger.info("xoql:"+xoql);
            QueryResult<JSONObject> qr = XoqlService.instance().query(xoql);
            if (qr != null && qr.getRecords() != null) {
                for (Object o : qr.getRecords()) {
                    if (o instanceof JSONObject) {
                        records.add((JSONObject) o);
                    }
                }
            }
            // 按地址代码（stras）升序排序，空值排末尾
            records.sort((a, b) -> {
                String sa = a.getString("stras");
                String sb = b.getString("stras");
                if (sa == null && sb == null) return 0;
                if (sa == null) return 1;
                if (sb == null) return -1;
                return sa.compareTo(sb);
            });
            // 去重：相同 purchaseId__c + customId__c 只保留 1 条（排序后保留 stras 最小的一条）
            List<JSONObject> deduped = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JSONObject r : records) {
                String pid = r.getString("purchaseId__c");
                String cid = r.getString("customId__c");
                String key = (pid == null ? "" : pid) + "#" + (cid == null ? "" : cid);
                if (seen.add(key)) {
                    deduped.add(r);
                }
            }
            records = deduped;
            // 内存切片分页
            int end = Math.min(offset + pageSize, records.size());
            for (int i = offset; i < end; i++) {
                JSONObject r = records.get(i);
                JSONObject item = new JSONObject();
                item.put("id", r.getString("id"));
                item.put("shipToAddress__c", r.getString("shipToAddress__c"));
                item.put("shipToCity__c", r.getString("shipToCity__c"));
                item.put("stras__c", r.getString("stras"));
                item.put("kunnr__c",r.getString("kunnr__c"));
                list.add(item);
            }
        } catch (Exception e) {
            return HttpUtil.fail("QUERY_ERROR", "查询发货地址失败：" + e.getMessage()).toJSONString();
        }

        JSONObject data = new JSONObject();
        data.put("total", records.size());
        data.put("pageNum", pageNum);
        data.put("pageSize", pageSize);
        data.put("list", list);
        logger.info("data:"+data);
        return HttpUtil.success(data).toJSONString();
    }
}
