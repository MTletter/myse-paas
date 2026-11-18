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
import com.rkhd.platform.sdk.service.XoqlService;
import other.myse.messagecenter.utils.HttpUtil;
import other.myse.messagecenter.utils.PermissionChangeGuard;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 我的账号 - 角色申请记录查询（other.myse.messagecenter.api）
 *
 * <p>按<b>申请人</b>（roleRequest__c.applyUser__c）查询该用户提交过的全部角色申请记录，
 * 供「我的账号 - 权限信息」页面展示申请历史（审批中/已通过/已驳回）。</p>
 *
 * <p><b>销售易 API 路径</b>：POST /rest/data/v2.0/scripts/api/messagecenter/role-request/list</p>
 *
 * <p><b>数据口径</b>：一条 roleRequest__c = 一次角色申请。查询条件只有 applyUser__c = 传入的userId，
 * <b>不区分状态</b>（不传 status 即返回全部状态）；默认按创建时间倒序（最新申请在前）。</p>
 *
 * <pre>
 * 入参:
 *   userId   String  必填  申请人 id（roleRequest__c.applyUser__c，指向 User）
 *   status   Integer 可选  状态过滤（status__c 选项 value）：1待提交 / 2审批 / 3驳回 / 4同意；不传=全部
 *   pageNum   Integer 可选  页码（默认 1）
 *   pageSize  Integer 可选  每页条数（默认 20，最大 200）
 *
 * 返回: {
 *   totalCount: 3,
 *   pageNum: 1,
 *   pageSize: 20,
 *   list: [
 *     {
 *       id: "4493516415305750",              // roleRequest__c id
 *       name: "众业达电气-权限申请-20260910160118", // 申请单名称
 *       applyUserId: "4433780814545882",      // 申请人 id
 *       applyUserName: "张三",                // 申请人姓名
 *       accountId: "4400183696671784",        // 申请账户 id
 *       accountName: "众业达电气股份有限公司",   // 申请账户名
 *       statusCode: 3,                        // status__c 选项值（1/2/3/4）
 *       status: "驳回",                        // status__c 中文 label（XOQL picklist 返回形态）
 *       approvalStatusCode: 2,                // approvalStatus 选项值（0待提交/1审批中/2审批拒绝/3审批通过/4撤回）
 *       approvalStatus: "审批拒绝",            // 平台审批流状态
 *       workflowStageName: "",                // 当前审批节点名（未走流程时为空）
 *       roleBefore: [ {roleId, roleName} ],   // 变更前角色
 *       roleAfter:  [ {roleId, roleName} ],   // 变更后角色（申请的角色）
 *       createdAt: "2026-09-10 16:01:18",     // 申请时间
 *       updatedAt: "2026-09-11 09:20:33"      // 最后更新时间
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p><b>实现要点</b>：4 次批量查询、零 N+1 —— ① roleRequest 主表 ② user 姓名 ③ account 名称
 * ④ generalRole 角色名（含roleBefore / roleAfter 合并后的全部角色 id，一次 IN 取回）。</p>
 *
 * <p><b>picklist 形态坑</b>：status__c / approvalStatus 在 XOQL 里返回<b>中文 label 数组</b>
 * （如 {@code ["驳回"]}）而非整数，getInteger() 会恒为 null，故本接口直接透出 label 字符串 + 单独映射
 * 出 statusCode 供前端做逻辑判断。</p>
 */
@RestApi(baseUrl = "/messagecenter")
public class RoleRequestListApi {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 日志标识 */
    private static final String TAG = "RoleRequestList";

    /** 单次最大拉取条数（Java 层分页上限，防误传超大 userId 拉爆内存） */
    private static final int MAX_FETCH = 5000;

    /** 默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 每页条数上限 */
    private static final int MAX_PAGE_SIZE = 200;

    /** status__c 中文 label → 选项值（XOQL 返回 label，接口需还原成码值给前端做逻辑判断） */
    private static final Map<String, Integer> STATUS_LABEL_TO_CODE = new HashMap<>();
    /** approvalStatus 中文 label → 选项值 */
    private static final Map<String, Integer> APPROVAL_LABEL_TO_CODE = new HashMap<>();

    static {
        // status__c：1待提交 / 2审批 / 3驳回 / 4同意
        STATUS_LABEL_TO_CODE.put("待提交", 1);
        STATUS_LABEL_TO_CODE.put("审批", 2);
        STATUS_LABEL_TO_CODE.put("驳回", 3);
        STATUS_LABEL_TO_CODE.put("同意", 4);

        // approvalStatus：0待提交 / 1审批中 / 2审批拒绝 / 3审批通过 / 4撤回
        APPROVAL_LABEL_TO_CODE.put("待提交", 0);
        APPROVAL_LABEL_TO_CODE.put("审批中", 1);
        APPROVAL_LABEL_TO_CODE.put("审批拒绝", 2);
        APPROVAL_LABEL_TO_CODE.put("审批通过", 3);
        APPROVAL_LABEL_TO_CODE.put("撤回", 4);
    }

    private static final ThreadLocal<SimpleDateFormat> DT_FMT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));

    @RestMapping(value = "/role-request/list", method = RequestMethod.POST)
    public String list(@RestBeanParam(name = "json") String body) {
        long startTs = System.currentTimeMillis();
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
            logger.info(TAG + " 入参: " + body);

            // ---------- 1) 参数校验 ----------
            Long userId = toLong(req.get("userId"));
            if (userId == null) {
                logger.warn(TAG + " 参数校验失败: userId 缺失或非法, 入参=" + body);
                return HttpUtil.fail("PARAM_ERROR", "userId 不能为空").toJSONString();
            }
            Integer statusFilter = req.getInteger("status");
            int pageNum = Math.max(1, req.getIntValue("pageNum"));
            int pageSize = req.getIntValue("pageSize");
            if (pageSize <= 0 || pageSize > MAX_PAGE_SIZE) {
                pageSize = DEFAULT_PAGE_SIZE;
            }
            logger.info(TAG + " 查询条件: applyUser=" + userId
                    + ", status=" + (statusFilter == null ? "全部" : statusFilter)
                    + ", page=" + pageNum + "/" + pageSize);

            // ---------- 2) 查 roleRequest__c ----------
            List<JSONObject> requests = queryRoleRequests(userId, statusFilter);

            // ---------- 3) Java 层分页 ----------
            int total = requests.size();
            List<JSONObject> pageRecords = page(requests, pageNum, pageSize);
            logger.info(TAG + " 查询结果: 命中=" + total + ", 本页=" + pageRecords.size());

            // ---------- 4) 批量补名称后组装 ----------
            JSONArray list = buildList(pageRecords);

            JSONObject result = new JSONObject();
            result.put("totalCount", total);
            result.put("pageNum", pageNum);
            result.put("pageSize", pageSize);
            result.put("list", list);
            logger.info(TAG + " 完成: 返回 " + list.size() + " 条, 耗时="
                    + (System.currentTimeMillis() - startTs) + "ms");
            return HttpUtil.success(result).toJSONString();

        } catch (Exception e) {
            logger.error(TAG + " 执行异常, 耗时=" + (System.currentTimeMillis() - startTs) + "ms", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    // ===================== 数据查询 =====================

    /**
     * 查该用户（applyUser__c）的角色申请记录，按创建时间倒序。
     *
     * @param userId        申请人 id
     * @param statusFilter  状态过滤码（null = 全部状态）
     * @return 记录列表（新建的 ArrayList，可安全分页）
     */
    private List<JSONObject> queryRoleRequests(Long userId, Integer statusFilter) {
        List<JSONObject> list = new ArrayList<>();
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT id, name, applyUser__c, account__c, userRole__c, "
                            + "roleBefore__c, roleAfter__c, status__c, approvalStatus, "
                            + "workflowStageName, createdAt, updatedAt "
                            + "FROM roleRequest__c WHERE applyUser__c = ").append(userId);
            if (statusFilter != null) {
                // status__c 是 picklist，WHERE 只能用选项值
                sql.append(" AND status__c = ").append(statusFilter);
            }
            sql.append(" ORDER BY createdAt DESC LIMIT 0, ").append(MAX_FETCH);

            long t0 = System.currentTimeMillis();
            QueryResult<JSONObject> rt = XoqlService.instance().query(sql.toString());
            if (rt != null && rt.getRecords() != null) {
                for (Object o : rt.getRecords()) {
                    list.add((JSONObject) o);
                }
            }
            logger.info(TAG + " SQL: " + sql + " → 返回 " + list.size()
                    + " 行, 耗时=" + (System.currentTimeMillis() - t0) + "ms");
        } catch (Exception e) {
            logger.error(TAG + " 查询 roleRequest__c 失败 userId=" + userId, e);
        }
        if (list.size() >= MAX_FETCH) {
            logger.warn(TAG + " 命中数已达上限 " + MAX_FETCH + " 条，可能存在截断，请确认是否需要缩小范围");
        }
        return list;
    }

    // ===================== 结果组装 =====================

    /**
     * 批量补齐本页所需的 user 姓名 / account 名称 / 角色名后逐条组装。
     * <p>角色名把 roleBefore ∪ roleAfter 的 id 合并成一次 IN 查询，避免拆两次。</p>
     */
    private JSONArray buildList(List<JSONObject> pageRecords) {
        JSONArray list = new JSONArray();
        if (pageRecords.isEmpty()) {
            return list;
        }

        // ---- 批量收集关联id ----
        Set<String> userIds = new LinkedHashSet<>();
        Set<String> accountIds = new LinkedHashSet<>();
        Set<String> roleIds = new LinkedHashSet<>();
        for (JSONObject r : pageRecords) {
            collectId(userIds, r.get("applyUser__c"));
            collectId(accountIds, r.get("account__c"));
            parseMultiIds(roleIds, r.get("roleBefore__c"));
            parseMultiIds(roleIds, r.get("roleAfter__c"));
        }

        // ---- 批量查询关联数据（3 次查询，无 N+1）----
        Map<String, String> userNameMap = queryUserNames(userIds);
        Map<String, String> accountNameMap = queryAccountNames(accountIds);
        Map<String, String> roleNameMap = queryRoleNames(roleIds);
        logger.info(TAG + " 关联数据: 本页 " + pageRecords.size() + " 行 → user " + userIds.size()
                + " 个/account " + accountIds.size() + " 个/role " + roleIds.size()
                + " 个, 命中 user " + userNameMap.size() + "、account " + accountNameMap.size()
                + "、role " + roleNameMap.size());

        // ---- 逐条组装 ----
        int missingUser = 0;
        int missingAccount = 0;
        for (JSONObject r : pageRecords) {
            String applyUserId = str(r.get("applyUser__c"));
            String accountId = str(r.get("account__c"));

            String userName = userNameMap.get(applyUserId);
            if (applyUserId.isEmpty() || userName == null) {
                missingUser++;
                userName = "";
            }
            String accountName = accountNameMap.get(accountId);
            if (accountId.isEmpty() || accountName == null) {
                missingAccount++;
                accountName = "";
            }

            // picklist 在 XOQL 里返回中文 label 数组（["驳回"]），不是整数
            String statusLabel = firstLabel(r.get("status__c"));
            String approvalLabel = firstLabel(r.get("approvalStatus"));

            JSONObject item = new JSONObject();
            item.put("id", str(r.get("id")));
            item.put("name", str(r.get("name")));
            item.put("applyUserId", applyUserId);
            item.put("applyUserName", userName);
            item.put("accountId", accountId);
            item.put("accountName", accountName);
            item.put("userRoleId", str(r.get("userRole__c")));
            item.put("status", statusLabel);
            item.put("statusCode", STATUS_LABEL_TO_CODE.get(statusLabel));
            item.put("approvalStatus", approvalLabel);
            item.put("approvalStatusCode", APPROVAL_LABEL_TO_CODE.get(approvalLabel));
            item.put("workflowStageName", str(r.get("workflowStageName")));
            item.put("roleBefore", buildRoleArray(r.get("roleBefore__c"), roleNameMap));
            item.put("roleAfter", buildRoleArray(r.get("roleAfter__c"), roleNameMap));
            item.put("createdAt", formatDateTime(r.get("createdAt")));
            item.put("updatedAt", formatDateTime(r.get("updatedAt")));
            list.add(item);
        }
        if (missingUser > 0) {
            logger.warn(TAG + " 有 " + missingUser + "/" + pageRecords.size()
                    + " 条记录未查到申请人（applyUser__c 为空或用户已删除），applyUserName 留空");
        }
        if (missingAccount > 0) {
            logger.warn(TAG + " 有 " + missingAccount + "/" + pageRecords.size()
                    + " 条记录未查到账户（account__c 为空或不可见），accountName 留空");
        }
        return list;
    }

    /** 单条记录的多值角色字段 → [{roleId, roleName}]，按原始顺序输出 */
    private JSONArray buildRoleArray(Object raw, Map<String, String> roleNameMap) {
        JSONArray arr = new JSONArray();
        Set<String> ids = new LinkedHashSet<>();
        parseMultiIds(ids, raw);
        for (String rid : ids) {
            JSONObject o = new JSONObject();
            o.put("roleId", rid);
            o.put("roleName", roleNameMap.getOrDefault(rid, ""));
            arr.add(o);
        }
        return arr;
    }

    /** 批量查用户姓名 */
    private Map<String, String> queryUserNames(Set<String> userIds) {
        Map<String, String> map = new HashMap<>();
        if (userIds.isEmpty()) {
            return map;
        }
        try {
            QueryResult<JSONObject> rt = XoqlService.instance().query(
                    "SELECT id, name FROM user WHERE id IN (" + buildInClause(userIds) + ") LIMIT 0, 2000");
            if (rt != null && rt.getRecords() != null) {
                for (Object o : rt.getRecords()) {
                    JSONObject u = (JSONObject) o;
                    map.put(str(u.get("id")), str(u.get("name")));
                }
            }
        } catch (Exception e) {
            logger.warn(TAG + " 查询用户姓名失败", e);
        }
        return map;
    }

    /** 批量查账户名称 */
    private Map<String, String> queryAccountNames(Set<String> accountIds) {
        Map<String, String> map = new HashMap<>();
        if (accountIds.isEmpty()) {
            return map;
        }
        try {
            QueryResult<JSONObject> rt = XoqlService.instance().query(
                    "SELECT id, accountName FROM account WHERE id IN (" + buildInClause(accountIds) + ") LIMIT 0, 2000");
            if (rt != null && rt.getRecords() != null) {
                for (Object o : rt.getRecords()) {
                    JSONObject a = (JSONObject) o;
                    map.put(str(a.get("id")), str(a.get("accountName")));
                }
            }
        } catch (Exception e) {
            logger.warn(TAG + " 查询账户名称失败", e);
        }
        return map;
    }

    /** 批量查基础角色名称 */
    private Map<String, String> queryRoleNames(Set<String> roleIds) {
        Map<String, String> map = new HashMap<>();
        if (roleIds.isEmpty()) {
            return map;
        }
        try {
            QueryResult<JSONObject> rt = XoqlService.instance().query(
                    "SELECT id, name FROM generalRole__c WHERE id IN (" + buildInClause(roleIds) + ") LIMIT 0, 2000");
            if (rt != null && rt.getRecords() != null) {
                for (Object o : rt.getRecords()) {
                    JSONObject r = (JSONObject) o;
                    map.put(str(r.get("id")), str(r.get("name")));
                }
            }
        } catch (Exception e) {
            logger.warn(TAG + " 查询角色名称失败", e);
        }
        return map;
    }

    // ===================== 工具方法（本包私有，不依赖其他包） =====================

    /** Java 层分页，越界返回空列表 */
    private List<JSONObject> page(List<JSONObject> all, int pageNum, int pageSize) {
        int start = (pageNum - 1) * pageSize;
        if (start >= all.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(all.subList(start, Math.min(start + pageSize, all.size())));
    }

    /**
     * 取 picklist 值的中文 label。
     * <p>XOQL 对 picklist 字段返回<b>label 数组</b>（如 {@code ["驳回"]}），也可能是标量或数字，
     * 这里统一取首个非空项转字符串。</p>
     */
    private String firstLabel(Object raw) {
        if (raw == null) {
            return "";
        }
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            for (int i = 0; i < arr.size(); i++) {
                String v = str(arr.get(i)).trim();
                if (!v.isEmpty()) {
                    return v;
                }
            }
            return "";
        }
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                String v = str(o).trim();
                if (!v.isEmpty()) {
                    return v;
                }
            }
            return "";
        }
        return str(raw).trim();
    }

    /**
     * 解析多值关联字段到有序 Set（保留原始顺序）。
     * <p>兼容三种形态：JSONArray / List / "id1,id2" 字符串；元素可能是标量或 {id:..} 对象。</p>
     * <b>注意</b>：不用 {@code getJSONArray()} —— fastjson 对非数组输入返回 null 而不抛异常，
     * 会静默丢数据（multireference 空值在 XOQL 里是空字符串）。</p>
     */
    private void parseMultiIds(Set<String> target, Object raw) {
        if (raw == null) {
            return;
        }
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            for (int i = 0; i < arr.size(); i++) {
                parseMultiIds(target, arr.get(i));
            }
            return;
        }
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                parseMultiIds(target, o);
            }
            return;
        }
        if (raw instanceof JSONObject) {
            addIfNotEmpty(target, str(((JSONObject) raw).get("id")));
            return;
        }
        if (raw instanceof Number) {
            addIfNotEmpty(target, raw.toString());
            return;
        }
        // 字符串：可能是 "id1,id2"，也可能是空串
        String s = raw.toString().trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return;
        }
        if (s.startsWith("[")) {
            try {
                JSONArray arr = JSON.parseArray(s);
                if (arr != null) {
                    parseMultiIds(target, arr);
                    return;
                }
            } catch (Exception ignored) {
                // 不是 JSON 数组，按逗号串处理
            }
        }
        for (String part : s.split(",")) {
            addIfNotEmpty(target, part);
        }
    }

    private void addIfNotEmpty(Set<String> target, String v) {
        if (v != null) {
            String t = v.trim();
            if (!t.isEmpty()) {
                target.add(t);
            }
        }
    }

    private void collectId(Set<String> target, Object value) {
        addIfNotEmpty(target, str(value));
    }

    /** 构建 IN ('a','b') 子句 */
    private String buildInClause(Set<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append("'").append(escapeSql(id)).append("'");
        }
        return sb.toString();
    }

    /** 时间戳（毫秒/秒）→ yyyy-MM-dd HH:mm:ss；空值返回空串 */
    private String formatDateTime(Object val) {
        if (val == null) {
            return "";
        }
        String s = str(val).trim();
        if (s.isEmpty()) {
            return "";
        }
        try {
            long ts = Long.parseLong(s);
            long millis = ts > 1000000000000L ? ts : ts * 1000L;
            return DT_FMT.get().format(new Date(millis));
        } catch (NumberFormatException e) {
            // 已是字符串日期则原样返回
            return s;
        }
    }

    private String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private String escapeSql(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    private Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }
}