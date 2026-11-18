package other.myse.messagecenter.api;

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
import other.myse.messagecenter.utils.HttpUtil;
import other.myse.messagecenter.utils.PermissionChangeGuard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 消息中心列表查询接口
 * <p>
 * 从 messageCenter__c（消息中心）分页查询，支持筛选：
 * - 用户id（ownerId）：必传固定条件
 * - 客户id（account__c）：必传固定条件
 * - 是否已读（isRead__c）：1=是 2=否
 * - 消息类型（messageType__c）：可选，传选项码值（1待办事项/2订阅信息/3转单通知/4公告通知/5权限变更）
 * - 发布时间范围（publishTime__c）：传开始/结束毫秒时间戳
 * - 标题(name) + 通知内容(content__c) 模糊搜索（contains，OR 关系）
 * 按发布时间倒序返回。
 * </p>
 * <p>messageType 入参兼容三种写法：单个整数 {"messageType":1}、数组 {"messageType":[1,2]}、
 * 逗号分隔字符串 {"messageType":"1,2"}；单值走 = 过滤，多值走 IN 过滤，不传/非法值=不过滤。</p>
 * <p>销售易 API 路径：POST /rest/data/v2.0/scripts/api/messagecenter/list</p>
 * <p>
 * 注意：销售易 XOQL 的 LIKE 仅支持前缀匹配（xxx%），且富文本字段不支持 LIKE，
 * 因此“标题/内容模糊搜索”在 Java 端对查询出的候选集做 contains 过滤后，再排序分页。
 * </p>
 */
@RestApi(baseUrl = "/messagecenter")
public class MessageCenterApi {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 日志统一前缀，便于线上按接口检索 */
    private static final String TAG = "MessageCenter";

    private static final int MAX_PAGE_SIZE = 200;
    // 关键字搜索时，先按基础条件（已读/时间）取候选集的上限，再 Java 端 contains 过滤
    private static final int KEYWORD_CANDIDATE_LIMIT = 2000;

    @RestMapping(value = "/list", method = RequestMethod.POST)
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
            logger.info("requestBody:"+requestBody);
            int pageNum = requestBody.getIntValue("pageNum");
            int pageSize = requestBody.getIntValue("pageSize");
            if (pageNum <= 0) {
                pageNum = 1;
            }
            if (pageSize <= 0) {
                pageSize = 10;
            }
            if (pageSize > MAX_PAGE_SIZE) {
                pageSize = MAX_PAGE_SIZE;
            }

            // 必传固定查询条件：用户id（ownerId）/ 客户id（account__c）
            // accountId 支持多选：{"accountId":[1,2]} / {"accountId":"1,2"} / {"accountIds":[1,2]} / 单值 1
            Long userId = requestBody.containsKey("userId") ? requestBody.getLong("userId") : null;
            List<Long> accountIds = parseIdList(requestBody.containsKey("accountIds")
                    ? requestBody.get("accountIds") : requestBody.get("accountId"));
            if (userId == null || accountIds.isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "userId 和 accountId 为必传参数（accountId 支持多选）").toJSONString();
            }

            Integer isRead = requestBody.containsKey("isRead") ? requestBody.getInteger("isRead") : null;
            Long publishStartTime = requestBody.containsKey("publishStartTime") ? requestBody.getLong("publishStartTime") : null;
            Long publishEndTime = requestBody.containsKey("publishEndTime") ? requestBody.getLong("publishEndTime") : null;
            String keyword = requestBody.getString("keyword");
            String kw = (keyword != null && !keyword.trim().isEmpty()) ? keyword.trim() : null;

            // 基础条件（必传固定条件 + 可选筛选）
            List<String> baseConditions = new ArrayList<>();


            baseConditions.add("ownerId = " + userId);
            baseConditions.add("account__c IN (" + joinIds(accountIds) + ")");
            Boolean isAnnouncement=false;
            if (requestBody.getJSONArray("messageType") != null && !requestBody.getJSONArray("messageType").isEmpty()) {

                    StringBuilder in = new StringBuilder("messageType__c IN (");
                    for (int i = 0; i < requestBody.getJSONArray("messageType").size(); i++) {
                        if (i > 0) {
                            in.append(",");
                        }
                        in.append(requestBody.getJSONArray("messageType").getInteger(i));
                        if(requestBody.getJSONArray("messageType").getInteger(i)==4) {
                            isAnnouncement=true;
                        }
                    }
                    in.append(")");
                    baseConditions.add(in.toString());

            }
            if (isRead != null && isRead > 0) {
                baseConditions.add("isRead__c = " + isRead);
            }
            if (publishStartTime != null) {
                baseConditions.add("publishTime__c >= " + publishStartTime);
            }
            if (publishEndTime != null) {
                baseConditions.add("publishTime__c <= " + publishEndTime);
            }

            String baseWhere =" WHERE " + String.join(" AND ", baseConditions);

            if (requestBody.getJSONArray("messageType") == null || requestBody.getJSONArray("messageType").isEmpty()||isAnnouncement) {
                baseWhere=" WHERE (" + String.join(" AND ", baseConditions)+") or messageType__c=4";
            }

            List<JSONObject> list = new ArrayList<>();
            long total;

            if (kw != null) {
                // 关键字：XOQL 不支持 contains / 富文本 LIKE，改为 Java 端 contains 过滤
                String sql = "SELECT id, name, content__c, messageType__c, publishTime__c, isRead__c,dataId__c,objectKey__c "
                        + "FROM messageCenter__c " + baseWhere + " LIMIT " + KEYWORD_CANDIDATE_LIMIT;
                logger.info(TAG + " 消息列表 SQL: " + sql);
                QueryResult qr = XObjectService.instance().query(sql,true);
                List<XObject> matched = new ArrayList<>();
                if (qr != null && qr.getRecords() != null) {
                    for (Object o : qr.getRecords()) {
                        XObject r = (XObject) o;
                        if (getStr(r, "name").contains(kw) || getStr(r, "content__c").contains(kw)) {
                            matched.add(r);
                        }
                    }
                }
                // 按发布时间倒序
                matched.sort((a, b) -> Long.compare(asLong(b, "publishTime__c"), asLong(a, "publishTime__c")));
                total = matched.size();
                int from = (pageNum - 1) * pageSize;
                int to = Math.min(from + pageSize, matched.size());
                for (int i = from; i < to; i++) {
                    list.add(convert(matched.get(i)));
                }
            } else {
                // 无关键字：纯 SQL 分页统计 + 列表
                // 总数用 XOQL count(1)：XObject 查询不支持取总数（老板 2026-10-21 明确）
                total = countBySql("SELECT count(1) FROM messageCenter__c" + baseWhere);

                int offset = (pageNum - 1) * pageSize;
                String listSql = "SELECT id, name, content__c, messageType__c, publishTime__c, isRead__c,dataId__c,objectKey__c,account__c "
                        + "FROM messageCenter__c" + baseWhere
                        + " ORDER BY publishTime__c DESC LIMIT " + offset + ", " + pageSize;
                logger.info(TAG + " 消息列表 SQL: " + listSql);

                QueryResult result = XObjectService.instance().query(listSql);

                if (result != null && result.getRecords() != null) {
                    for (Object o : result.getRecords()) {
                        list.add(convert((XObject) o));
                    }
                }
            }

            JSONObject data = new JSONObject();
            data.put("total", total);
            data.put("pageNum", pageNum);
            data.put("pageSize", pageSize);
            data.put("list", list);

            logger.info("MessageCenter list 完成, userId=" + userId + " accountIds=" + accountIds
                    + " pageNum=" + pageNum + " pageSize=" + pageSize
                    + " total=" + total + " returned=" + list.size() + " keyword=" + kw);

            return HttpUtil.success(data).toJSONString();

        } catch (Exception e) {
            logger.error("MessageCenter list 执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }
    /**
     * 解析客户 id 入参：支持多选
     * [1,2] / "1,2" / "[1,2]" / 单值 1 或 "1"，去重并忽略非法值。
     */
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
                    logger.warn("MessageCenter accountId 解析失败: " + s);
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

    /** 拼 IN 条件："1,2,3" */
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

    private Integer toInt(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        String s = val.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject convert(XObject r) {
        JSONObject item = new JSONObject();
        Object idObj = r.getAttribute("id");
        item.put("id", idObj == null ? null : idObj.toString());
        item.put("title", getStr(r, "name"));
        item.put("content", getStr(r, "content__c"));
        Integer messageType = getInt(r, "messageType__c");
        item.put("messageType", messageType);
        item.put("messageTypeName", messageType == null ? "" : messageTypeName(messageType));
        Object publishTime = r.getAttribute("publishTime__c");
        item.put("publishTime", publishTime == null ? null : publishTime);
        Integer isRead = getInt(r, "isRead__c");
        item.put("isRead", isRead);
        item.put("isReadName", isRead == null ? "" : isReadName(isRead));
        item.put("dataId__c",getStr(r, "dataId__c"));
        item.put("objectKey__c",getStr(r, "objectKey__c"));
        item.put("account",getStr(r,"account__c"));
        return item;
    }

    private String getStr(XObject r, String key) {
        Object v = r.getAttribute(key);
        return v == null ? "" : v.toString();
    }

    private Integer getInt(XObject r, String key) {
        Object v = r.getAttribute(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(v.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private long asLong(XObject r, String key) {
        Object v = r.getAttribute(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return v == null ? 0L : Long.parseLong(v.toString());
        } catch (Exception e) {
            return 0L;
        }
    }

    private String messageTypeName(int code) {
        switch (code) {
            case 1:
                return "待办事项";
            case 2:
                return "订阅信息";
            case 3:
                return "转单通知";
            case 4:
                return "公告通知";
            case 5:
                return "权限变更";
            default:
                return "";
        }
    }

    private String isReadName(int code) {
        switch (code) {
            case 1:
                return "是";
            case 2:
                return "否";
            default:
                return "";
        }
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
