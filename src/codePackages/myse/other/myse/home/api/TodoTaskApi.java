package other.myse.home.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.context.ScriptRuntimeContext;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.model.XObject;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;
import org.apache.commons.lang.StringUtils;
import other.myse.home.utils.HttpUtil;
import other.myse.home.utils.PermissionChangeGuard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 首页待办事项分页查询接口
 * <p>
 * 数据源由废弃的 todoTask__c 切换为消息中心 messageCenter__c：
 * 只取「消息类型 = 1 待办事项」的记录，按客户（account__c）+ 可选接收人（ownerId）过滤，
 * 排序按发布时间（publishTime__c）倒序，返回结构与改造前保持一致（id/todoDesc/todoType/billType/dataId/updateDate）。
 * </p>
 * <p>
 * 字段映射：
 * <ul>
 *   <li>todoDesc   ← content__c（通知内容）</li>
 *   <li>todoType   ← todoType__c（1 待解单 / 2 待确认 / 3 待审批）</li>
 *   <li>billType   ← 由 objectKey__c（单据对象 apiKey）推导，见 BILL_TYPE_KEYS</li>
 *   <li>dataId     ← dataId__c（单据 id / 单号）</li>
 *   <li>updateDate ← publishTime__c（毫秒时间戳，与原 todoTask__c.updateDate__c 同类型）</li>
 * </ul>
 * </p>
 * <p>
 * 入参：pageNum、pageSize、accountIds（必传，数组）、userId（可选，待审批类传，按接收人过滤）、
 * todoType（可选 1/2/3）、billType（可选 1~5，转成 objectKey__c 过滤）。
 * 已读状态不参与过滤（已读/未读都返回）。
 * </p>
 * <p>销售易 API 路径：POST /rest/data/v2.0/scripts/api/home/todo/list</p>
 */
@RestApi(baseUrl = "/home")
public class TodoTaskApi {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 日志标识 */
    private static final String TAG = "TodoTask";

    private static final int MAX_PAGE_SIZE = 200;

    /** 消息类型：1=待办事项（messageCenter__c.messageType__c） */
    private static final int MESSAGE_TYPE_TODO = 1;

    /** billType（单据类型）→ objectKey__c 取值（单据对象 apiKey），同一类型可配置多个 */
    private static final Map<Integer, String[]> BILL_TYPE_KEYS = new HashMap<>();

    /** objectKey__c → billType（由上表反推，用于出参赋值） */
    private static final Map<String, Integer> KEY_BILL_TYPE = new HashMap<>();

    /** billType 名称 */
    private static final Map<Integer, String> BILL_TYPE_NAMES = new HashMap<>();

    /** todoType：code → name */
    private static final Map<Integer, String> TODO_TYPE_NAMES = new HashMap<>();


    static {
        // billType：1报价单 / 2订单 / 3发货单 / 4特价申请单 / 5用户权限审批
        BILL_TYPE_KEYS.put(1, new String[]{"quote__c"});
        BILL_TYPE_KEYS.put(2, new String[]{"order__c"});
        BILL_TYPE_KEYS.put(3, new String[]{"delivery__c"});
        BILL_TYPE_KEYS.put(4, new String[]{"specialOrderApplication__c"});
        BILL_TYPE_KEYS.put(5, new String[]{"myAccount", "roleRequest__c"});

        for (Map.Entry<Integer, String[]> e : BILL_TYPE_KEYS.entrySet()) {
            for (String key : e.getValue()) {
                KEY_BILL_TYPE.put(key, e.getKey());
            }
        }

        BILL_TYPE_NAMES.put(1, "报价单");
        BILL_TYPE_NAMES.put(2, "订单");
        BILL_TYPE_NAMES.put(3, "发货单");
        BILL_TYPE_NAMES.put(4, "特价申请单");
        BILL_TYPE_NAMES.put(5, "用户权限审批");


        TODO_TYPE_NAMES.put(1, "待解单");
        TODO_TYPE_NAMES.put(2, "待确认");
        TODO_TYPE_NAMES.put(3, "待审批");
    }

    @RestMapping(value = "/todo/list", method = RequestMethod.POST)
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

            JSONObject req = JSON.parseObject(body);

            int pageNum = req.getIntValue("pageNum");
            int pageSize = req.getIntValue("pageSize");
            if (pageNum <= 0) {
                pageNum = 1;
            }
            if (pageSize <= 0) {
                pageSize = 10;
            }
            if (pageSize > MAX_PAGE_SIZE) {
                pageSize = MAX_PAGE_SIZE;
            }

            // accountIds：必传数组（兼容单个数字 / 逗号分隔字符串）
            List<Long> accountIds = parseIdList(req.get("accountIds"));
            if (accountIds.isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "accountIds 为必传参数（客户 id 数组）").toJSONString();
            }
            // userId：可选；待审批类由前端传，按消息接收人（审批人）过滤
//            Long userId = req.getLong("userId");
            Long userId = ScriptRuntimeContext.instance().getUserId();

            JSONArray todoTypes = req.getJSONArray("todoTypes");

            logger.info(TAG + " 入参: pageNum=" + pageNum + " pageSize=" + pageSize
                    + " accountIds=" + accountIds + " todoTypes=" + todoTypes );

            List<String> conditions = new ArrayList<>();
            // 固定条件：消息类型 = 待办事项
            conditions.add("messageType__c = " + MESSAGE_TYPE_TODO);
            // 客户维度：多客户 IN 过滤
            conditions.add("account__c IN (" + joinIds(accountIds) + ")");
            // 待处理
            conditions.add("isHandle__c = 2");


            if (todoTypes != null && todoTypes.size() > 0) {
                conditions.add("todoType__c IN (" + StringUtils.join(todoTypes.toJavaList(Integer.class),",") + ")");
            }

            String where = " WHERE " + joinAnd(conditions);

            // ---- 统计总数 ----
            // 用 XOQL count(1)：XObject 查询不支持取总数（老板 2026-10-21 明确）
            long total = countBySql("SELECT count(1) FROM messageCenter__c" + where);

            // ---- 列表 ----
            int offset = (pageNum - 1) * pageSize;
            String listSql = "SELECT id, name, content__c, todoType__c, dataId__c, objectKey__c, "
                    + "publishTime__c, isRead__c,account__c FROM messageCenter__c" + where
                    + " ORDER BY publishTime__c DESC LIMIT " + offset + ", " + pageSize;

            List<JSONObject> list = new ArrayList<>();
            try {
                QueryResult<XObject> result = XObjectService.instance().query(listSql);
                if (result != null && result.getRecords() != null) {
                    for (XObject o : result.getRecords()) {
                        list.add(convert(o));
                    }
                }
            } catch (Exception e) {
                logger.error(TAG + " 查询列表异常, sql=" + listSql, e);
            }

            JSONObject data = new JSONObject();
            data.put("total", total);
            data.put("pageNum", pageNum);
            data.put("pageSize", pageSize);
            data.put("list", list);

            logger.info(TAG + " 出参: total=" + total + " 返回条数=" + list.size()
                    + " pageNum=" + pageNum + " pageSize=" + pageSize);

            return HttpUtil.success(data).toJSONString();

        } catch (Exception e) {
            logger.error(TAG + " 执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    /**
     * 单条消息 → 前端待办结构。
     * 保持改造前的出参字段：id / todoDesc / todoType / todoTypeName / billType / billTypeName / dataId / updateDate，
     * 额外补充 title（消息标题）、isRead（是否已读）供前端按需使用。
     */
    private JSONObject convert(XObject r) {
        JSONObject item = new JSONObject();

        Object idObj = r.getAttribute("id");
        item.put("id", idObj == null ? null : idObj.toString());

        // 待办描述 ← 通知内容
        item.put("todoDesc", getStr(r, "content__c"));

        // 待办类型（兼容 XOQL 返回 label 数组的情况）
        Integer todoType = r.getAttribute("todoType__c");
        item.put("todoType", todoType);
        item.put("todoTypeName", todoType == null ? "" : nameOf(TODO_TYPE_NAMES, todoType));

        // 单据类型 ← objectKey__c（单据对象 apiKey）推导
        String objectKey = getStr(r, "objectKey__c");
        item.put("billType", objectKey);

        // 单据 id / 单号
        item.put("dataId", getStr(r, "dataId__c"));

        item.put("account", getStr(r, "account__c"));

        // 时间：publishTime__c 为毫秒时间戳，与原 updateDate__c 同类型
        Object publishTime = r.getAttribute("publishTime__c");
        item.put("updateDate", publishTime == null ? null : publishTime);

        // 附加字段（增量，不影响原结构）
        item.put("title", getStr(r, "name"));
        item.put("isRead", resolveCode(r.getAttribute("isRead__c"), null));
        return item;
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

    /**
     * 解析单选项字段值：兼容 整数 / 数字字符串 / apiKey 字符串 / 中文 label 数组（XOQL 可能返回 label）。
     */
    private Integer resolveCode(Object val, Map<String, Integer> labelCodes) {
        if (val == null) {
            return null;
        }
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        if (val instanceof JSONArray) {
            JSONArray arr = (JSONArray) val;
            if (arr.isEmpty()) {
                return null;
            }
            return resolveCode(arr.get(0), labelCodes);
        }
        if (val instanceof JSONObject) {
            JSONObject jo = (JSONObject) val;
            Object code = jo.get("optionCode") != null ? jo.get("optionCode") : jo.get("optionApiKey");
            return resolveCode(code, labelCodes);
        }
        String s = val.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        if (labelCodes != null && labelCodes.containsKey(s)) {
            return labelCodes.get(s);
        }
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    private String getStr(XObject r, String key) {
        Object v = r.getAttribute(key);
        return v == null ? "" : v.toString();
    }

    private String nameOf(Map<Integer, String> names, Integer code) {
        String n = names.get(code);
        return n == null ? "" : n;
    }

    /**
     * 取满足条件的记录总数。
     *
     * <p><b>用 XOQL {@code SELECT count(1)}，不用 XObject 查询</b>（老板 2026-10-21 明确：
     * XObject 查询不支持取总数）。</p>
     *
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
}
