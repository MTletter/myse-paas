package other.myse.messagecenter.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.data.model.MessageCenter__c;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.BatchOperateResult;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.model.XObject;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;
import other.myse.messagecenter.utils.HttpUtil;
import other.myse.messagecenter.utils.PermissionChangeGuard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 消息中心「全部已读」接口
 * <p>
 * 查询条件与列表查询接口（MessageCenterApi#/list）保持一致：
 * - 必传固定条件：用户 id（ownerId / userId）+ 客户 id（account__c / accountId，支持多选）
 * - 可选筛选：消息类型（messageType 数组）、发布时间范围（publishStartTime/publishEndTime）
 * </p>
 * <p>差异：</p>
 * <ul>
 *   <li>仅处理未读（固定追加 isRead__c = 2），已读的不重复更新；</li>
 *   <li>排除公告（固定追加 messageType__c != 4）：公告全租户共享、不过滤 ownerId，不误标他人公告；</li>
 *   <li>不支持 keyword（标题/内容模糊搜索）。</li>
 * </ul>
 * <p>销售易 API 路径：POST /rest/data/v2.0/scripts/api/messagecenter/readAll</p>
 */
@RestApi(baseUrl = "/messagecenter")
public class MessageCenterReadAllApi {

    private static final Logger logger = LoggerFactory.getLogger();

    private static final String TAG = "MessageCenterReadAll";

    /** 单次 XOQL 查询分页大小（平台单次响应硬上限约 300 条，此处取安全值） */
    private static final int PAGE_SIZE = 200;

    /** 单批批量 update 的条数上限 */
    private static final int BATCH_SIZE = 200;

    @RestMapping(value = "/readAll", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        try {
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = PermissionChangeGuard.checkPermissionChanged(TAG);
            if (guard != null) {
                return guard;
            }
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            JSONObject requestBody = JSON.parseObject(body);
            logger.info("requestBody:" + requestBody);

            // 必传固定条件：用户 id / 客户 id（与列表一致）
            Long userId = requestBody.containsKey("userId") ? requestBody.getLong("userId") : null;
            List<Long> accountIds = parseIdList(requestBody.containsKey("accountIds")
                    ? requestBody.get("accountIds") : requestBody.get("accountId"));
            if (userId == null || accountIds.isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "userId 和 accountId 为必传参数（accountId 支持多选）").toJSONString();
            }

            // 可选筛选：消息类型（数组）/ 发布时间范围
            List<Integer> messageTypes = new ArrayList<>();
            if (requestBody.getJSONArray("messageType") != null && !requestBody.getJSONArray("messageType").isEmpty()) {
                JSONArray mtArr = requestBody.getJSONArray("messageType");
                for (int i = 0; i < mtArr.size(); i++) {
                    Integer v = mtArr.getInteger(i);
                    if (v != null) {
                        messageTypes.add(v);
                    }
                }
            }
            Long publishStartTime = requestBody.containsKey("publishStartTime") ? requestBody.getLong("publishStartTime") : null;
            Long publishEndTime = requestBody.containsKey("publishEndTime") ? requestBody.getLong("publishEndTime") : null;

            // 构造 WHERE 条件（固定追加未读 + 排除公告）
            List<String> conditions = new ArrayList<>();
            conditions.add("ownerId = " + userId);
            conditions.add("account__c IN (" + joinIds(accountIds) + ")");
            conditions.add("isRead__c = 2");        // 只处理未读
            conditions.add("messageType__c != 4");  // 排除公告
            if (!messageTypes.isEmpty()) {
                StringBuilder in = new StringBuilder("messageType__c IN (");
                for (int i = 0; i < messageTypes.size(); i++) {
                    if (i > 0) {
                        in.append(",");
                    }
                    in.append(messageTypes.get(i));
                }
                in.append(")");
                conditions.add(in.toString());
            }
            if (publishStartTime != null) {
                conditions.add("publishTime__c >= " + publishStartTime);
            }
            if (publishEndTime != null) {
                conditions.add("publishTime__c <= " + publishEndTime);
            }
            String where = " WHERE " + String.join(" AND ", conditions);

            // 分页查出全部未读 id（平台单次响应有硬上限，必须循环分页）
            List<Long> ids = new ArrayList<>();
            int offset = 0;
            while (true) {
                String sql = "SELECT id FROM messageCenter__c" + where
                        + " ORDER BY id LIMIT " + offset + ", " + PAGE_SIZE;
                QueryResult<JSONObject> res = XoqlService.instance().query(sql);
                if (res == null || res.getRecords() == null || res.getRecords().isEmpty()) {
                    break;
                }
                List<JSONObject> records = res.getRecords();
                for (JSONObject row : records) {
                    Long id = row.getLong("id");
                    if (id != null && id > 0) {
                        ids.add(id);
                    }
                }
                if (records.size() < PAGE_SIZE) {
                    break;
                }
                offset += PAGE_SIZE;
            }

            if (ids.isEmpty()) {
                JSONObject data = new JSONObject();
                data.put("total", 0);
                data.put("successCount", 0);
                data.put("failCount", 0);
                logger.info(TAG + " 无未读消息需要处理, userId=" + userId + " accountIds=" + accountIds);
                return HttpUtil.success(data).toJSONString();
            }

            // 批量置为已读（每 BATCH_SIZE 一批）
            int successCount = 0;
            int failCount = 0;
            for (int i = 0; i < ids.size(); i += BATCH_SIZE) {
                int to = Math.min(i + BATCH_SIZE, ids.size());
                List<MessageCenter__c> batch = new ArrayList<>();
                for (int j = i; j < to; j++) {
                    MessageCenter__c obj = new MessageCenter__c();
                    obj.setId(ids.get(j));
                    obj.setAttribute("isRead__c", 1);
                    batch.add(obj);
                }
                try {
                    BatchOperateResult br = XObjectService.instance().update(batch, true, true);
                    boolean ok = br != null && Boolean.TRUE.equals(br.getSuccess());
                    if (ok) {
                        successCount += batch.size();
                    } else {
                        failCount += batch.size();
                        logger.error(TAG + " 批量已读失败 条数=" + batch.size()
                                + " err=" + (br == null ? "null" : br.getErrorMessage()));
                    }
                } catch (Exception e) {
                    failCount += batch.size();
                    logger.error(TAG + " 批量已读异常 条数=" + batch.size() + " err=" + e.getMessage(), e);
                }
            }

            JSONObject data = new JSONObject();
            data.put("total", ids.size());
            data.put("successCount", successCount);
            data.put("failCount", failCount);

            logger.info(TAG + " 全部已读完成, userId=" + userId + " accountIds=" + accountIds
                    + " total=" + ids.size() + " success=" + successCount + " fail=" + failCount);
            return HttpUtil.success(data).toJSONString();

        } catch (Exception e) {
            logger.error(TAG + " 执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    /** 解析客户 id 入参：支持多选 [1,2] / "1,2" / "[1,2]" / 单值，去重并忽略非法值。 */
    private List<Long> parseIdList(Object val) {
        Set<Long> ids = new LinkedHashSet<>();
        if (val == null) {
            return new ArrayList<>(ids);
        }
        if (val instanceof JSONArray) {
            for (Object o : (JSONArray) val) {
                addId(ids, o);
            }
        } else if (val instanceof String) {
            String s = ((String) val).trim();
            if (s.startsWith("[") && s.endsWith("]")) {
                try {
                    JSONArray arr = JSON.parseArray(s);
                    if (arr != null) {
                        for (Object o : arr) {
                            addId(ids, o);
                        }
                    }
                } catch (Exception e) {
                    logger.warn(TAG + " accountId 解析失败: " + s);
                }
            } else {
                for (String part : s.split(",")) {
                    addId(ids, part);
                }
            }
        } else {
            addId(ids, val);
        }
        return new ArrayList<>(ids);
    }

    private void addId(Set<Long> ids, Object o) {
        if (o == null) {
            return;
        }
        try {
            long v = (o instanceof Number) ? ((Number) o).longValue() : Long.parseLong(o.toString().trim());
            if (v > 0) {
                ids.add(v);
            }
        } catch (Exception ignore) {
            // 非法值忽略
        }
    }

    private String joinIds(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(ids.get(i));
        }
        return sb.toString();
    }
}
