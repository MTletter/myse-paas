package other.myse.home.api;

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
import com.rkhd.platform.sdk.model.XObject;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;
import org.apache.commons.lang.StringUtils;
import other.myse.home.utils.HttpUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 首页待办数量统计接口（按客户分组）
 * <p>
 * 用途：前端首页/客户列表页需要在每个客户（account）旁边显示待办数量徽标，
 * 前端只传 accountId 数组，后端返回每个客户的待办条数。
 * </p>
 * <p>
 * <b>统计口径与待办列表接口（TodoTaskApi /todo/list）完全一致</b>：
 * <ul>
 *   <li>数据源：messageCenter__c（消息中心）</li>
 *   <li>固定条件：messageType__c = 1（待办事项）、isHandle__c = 2（未处理）</li>
 *   <li>客户维度：account__c IN (入参 accountIds)</li>
 *   <li>可选：todoTypes（1 待解单 / 2 待确认 / 3 待审批），不传 = 全部类型</li>
 *   <li>已读状态（isRead__c）不参与过滤（与列表一致，已读/未读都算待办）</li>
 *   <li>不按接收人（ownerId）过滤 —— 与列表接口一致，统计的是这些客户下全部待办</li>
 * </ul>
 * </p>
 * <p>
 * <b>实现要点</b>：优先一次拉取命中记录、在内存里按 account__c 分组计数（1 次查询）；
 * 若命中条数触达上限（说明数据被截断、计数会失真），退化为「逐客户 count(1)」精确统计。
 * </p>
 * <p><b>注意</b>：本接口<b>不</b>调用 {@code PermissionChangeGuard}——
 * 该守卫是"一次性令牌"，调用即清空标记，若在此消费会导致前端在列表接口收不到
 * PERMISSION_CHANGED 提示。</p>
 * <p>销售易 API 路径：POST /rest/data/v2.0/scripts/api/home/todo/count</p>
 */
@RestApi(baseUrl = "/home")
public class TodoTaskCountApi {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 日志标识 */
    private static final String TAG = "TodoTaskCount";

    /** 消息类型：1=待办事项（messageCenter__c.messageType__c） */
    private static final int MESSAGE_TYPE_TODO = 1;

    /** 单次拉取上限：命中条数达到该值说明可能被截断 → 退化为逐客户 count */
    private static final int GROUP_LIMIT = 2000;

    @RestMapping(value = "/todo/count", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        try {
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            JSONObject req = JSON.parseObject(body);

            // accountIds：必传数组（兼容单个数字 / 逗号分隔字符串）
            List<Long> accountIds = parseIdList(req.get("accountIds"));
            if (accountIds.isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "accountIds 为必传参数（客户 id 数组）").toJSONString();
            }

            // todoTypes：可选，不传 = 全部待办类型
            JSONArray todoTypes = req.getJSONArray("todoTypes");

            logger.info(TAG + " 入参: accountIds=" + accountIds + " todoTypes=" + todoTypes);

            // 固定条件（与客户无关的部分）
            List<String> baseConditions = new ArrayList<>();
            baseConditions.add("messageType__c = " + MESSAGE_TYPE_TODO);
            baseConditions.add("isHandle__c = 2");
            if (todoTypes != null && !todoTypes.isEmpty()) {
                baseConditions.add("todoType__c IN ("
                        + StringUtils.join(todoTypes.toJavaList(Integer.class), ",") + ")");
            }
            String baseWhere = " WHERE " + joinAnd(baseConditions);

            // 按客户分组计数
            Map<Long, Integer> counter = countByAccount(accountIds, baseWhere);

            JSONArray list = new JSONArray();
            int total = 0;
            for (Long acc : accountIds) {
                int cnt = counter.get(acc) == null ? 0 : counter.get(acc);
                total += cnt;
                JSONObject item = new JSONObject();
                item.put("accountId", String.valueOf(acc));
                item.put("todoCount", cnt);
                list.add(item);
            }

            JSONObject data = new JSONObject();
            data.put("list", list);
            data.put("total", total);

            logger.info(TAG + " 出参: 客户数=" + accountIds.size() + " 待办合计=" + total + " 明细=" + list);

            return HttpUtil.success(data).toJSONString();

        } catch (Exception e) {
            logger.error(TAG + " 执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    // ===================== 统计实现 =====================

    /**
     * 按客户统计待办条数。
     *
     * <p>优先：一次查询拉出命中记录的 account__c，内存分组计数（1 次查询）。</p>
     * <p>兜底：命中条数触达 {@link #GROUP_LIMIT}（数据被截断、计数会失真）或查询异常时，
     * 退化为「逐客户 count(1)」精确统计。</p>
     *
     * @param accountIds 入参客户 id 列表（去重后，保持顺序）
     * @param baseWhere  不含客户条件的 WHERE 子句
     * @return accountId → 待办条数（无待办的客户不在 map 中，输出时按 0 处理）
     */
    private Map<Long, Integer> countByAccount(List<Long> accountIds, String baseWhere) {
        Map<Long, Integer> counter = new LinkedHashMap<>();

        String groupSql = "SELECT id, account__c FROM messageCenter__c"
                + baseWhere + " AND account__c IN (" + joinIds(accountIds) + ")"
                + " LIMIT 0, " + GROUP_LIMIT;
        try {
            QueryResult<XObject> rt = XObjectService.instance().query(groupSql);
            List<XObject> rows = rt == null ? null : rt.getRecords();
            if (rows != null && rows.size() < GROUP_LIMIT) {
                for (XObject r : rows) {
                    Long acc = resolveAccountId(r.getAttribute("account__c"));
                    if (acc != null) {
                        Integer old = counter.get(acc);
                        counter.put(acc, old == null ? 1 : old + 1);
                    }
                }
                return counter;
            }
            logger.warn(TAG + " 待办条数触顶(" + GROUP_LIMIT + ")，内存分组计数可能失真，退化为逐客户 count");
        } catch (Exception e) {
            logger.error(TAG + " 内存分组统计失败，退化为逐客户 count, sql=" + groupSql, e);
        }

        // 兜底：逐客户 count(1)
        for (Long acc : accountIds) {
            String sql = "SELECT count(1) FROM messageCenter__c"
                    + baseWhere + " AND account__c = " + acc;
            counter.put(acc, (int) countBySql(sql));
        }
        return counter;
    }

    /**
     * 取满足条件的记录总数。
     *
     * <p><b>用 XOQL {@code SELECT count(1)}</b>（XObject 查询不支持取总数，老板 2026-10-21 明确）。</p>
     * <p>返回结构：{@code data.records[0]["count(1)"]}，值是<b>字符串</b>（如 "18"），需自行转数字。</p>
     */
    private long countBySql(String sql) {
        try {
            QueryResult<JSONObject> rt = XoqlService.instance().query(sql);
            if (rt != null && rt.getRecords() != null && !rt.getRecords().isEmpty()) {
                JSONObject row = rt.getRecords().get(0);
                if (row != null && !row.isEmpty()) {
                    Object v = row.values().iterator().next();
                    if (v != null) {
                        return Long.parseLong(v.toString().trim());
                    }
                }
            }
        } catch (Exception e) {
            logger.error(TAG + " 统计异常, sql=" + sql, e);
        }
        return 0L;
    }

    /**
     * 解析 account__c 的值 → 客户 id。
     *
     * <p>兼容：整数 / 数字字符串 / lookup 对象（取 id）/ 对象 JSON 字符串。</p>
     */
    private Long resolveAccountId(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        if (val instanceof JSONObject) {
            JSONObject jo = (JSONObject) val;
            Object id = jo.get("id");
            if (id == null) {
                id = jo.get("apiKey");
            }
            return id == null ? null : resolveAccountId(id);
        }
        if (val instanceof JSONArray) {
            JSONArray arr = (JSONArray) val;
            return arr.isEmpty() ? null : resolveAccountId(arr.get(0));
        }
        String s = val.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.startsWith("{")) {
            try {
                return resolveAccountId(JSON.parseObject(s));
            } catch (Exception e) {
                return null;
            }
        }
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return null;
        }
    }

    // ===================== 工具方法 =====================

    /** 解析 id 数组入参：兼容 [1,2] / "1,2" / 1 / "1" */
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
                    logger.warn(TAG + " accountIds 解析失败: " + s);
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

    private String joinAnd(List<String> conditions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) {
                sb.append(" AND ");
            }
            sb.append(conditions.get(i));
        }
        return sb.toString();
    }
}
