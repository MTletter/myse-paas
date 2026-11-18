package other.myse.coordinator.approval;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.context.ScriptRuntimeContext;
import com.rkhd.platform.sdk.model.QueryResult;
import other.myse.coordinator.utils.HttpUtil;

import java.util.*;

/**
 * 待审批列表模块 - 待审批列表页（approval 子包）
 *
 * <p>对应页面：账户权限管理 - 待审批列表 Tab（姓名/变更前/变更后/账户/申请日期/操作）。</p>
 *
 * <pre>
 * 入参:
 *   accountId       Long    必填  当前账户ID（对应 roleRequest__c.account__c）
 *   name            String  可选  申请人姓名模糊（applyUser__c -> user.name）
 *   applyDateStart  String  可选  申请日期开始(yyyy-MM-dd)，对应 createdAt
 *   applyDateEnd    String  可选  申请日期结束(yyyy-MM-dd)，对应 createdAt
 *   status          Integer 可选  业务状态过滤(status__c)；不传=全部状态（含历史审批记录）
 *   sorts           Array   可选  同状态内的排序，[{field: 字段API名, order: "ASC"|"DESC"}]；默认 createdAt DESC
 *                   注意：待审批(status__c=2)恒排最前，sorts 只决定同状态内的顺序
 *   pageNum         int     可选  页码(默认1)
 *   pageSize        int     可选  每页条数(默认20,最大200)
 *
 * 返回: {
 *   total, pageNum, pageSize,
 *   pendingCount: Long,   // 该账户待审批(status__c=2)总数，不受筛选影响，供 Tab 角标
 *   list: [
 *     {
 *       id:          String,  // roleRequest 记录id，"去处理"跳转详情用
 *       userName:    String,  // 申请人姓名
 *       roleBefore:  String,  // 变更前角色名("、"拼接)
 *       roleAfter:   String,  // 变更后角色名
 *       accountName: String,  // 账户名
 *       applyDate:   String,  // 申请日期 yyyy-MM-dd
 *       status:      Integer  // 审批状态（选项 value 数字）：1待提交 2审批(待审批) 3驳回 4同意
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p><b>列表默认包含历史审批记录</b>（老板 2026-10-26）：不传 status 时查全部状态，
 * 排序用 {@code ORDER BY status__c ASC} —— status__c 的选项 value 是 1待提交/2审批/3驳回/4同意，
 * 实际不存在"待提交"数据，所以升序即「待审批 → 驳回 → 同意」，待审批自然排最前（实测按 value 排）。</p>
 *
 * <p>迁移说明：自旧 {@code /coordinator/role-request/list} 迁来（api 包 RoleRequestListApi），
 * 新增 pendingCount 字段；其余行为保持一致。旧接口暂留，模块全部切完后统一下线。</p>
 */
@RestApi(baseUrl = "/coordinator")
public class ApprovalListApi extends ApprovalBaseApi {

    /** 日志标识：本接口所有日志统一前缀，便于线上按接口检索 */
    private static final String TAG = "ApprovalList";

    @RestMapping(value = "/approval/list", method = RequestMethod.POST)
    public String list(@RestBeanParam(name = "json") String body) {
        long startTs = System.currentTimeMillis();
        String resp;
        try {
            logIn(TAG, body);
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = checkPermissionChanged(TAG);
            if (guard != null) {
                return guard;
            }
            resp = doList(body, startTs);
        } catch (Exception e) {
            logger.error(TAG + " 处理异常: " + e.getMessage(), e);
            resp = HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
            logger.error(TAG + " 出参: " + resp);
        }
        return resp;
    }

    /** 业务逻辑（异常统一冒泡到 {@link #list(String)} 兜底，本方法不重复打异常日志） */
    private String doList(String body, long startTs) {
        if (body == null || body.isEmpty()) {
            String r = HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            logger.warn(TAG + " 出参: " + r);
            return r;
        }
        JSONObject req = JSON.parseObject(body);

        //当前操作人
        Long userId = ScriptRuntimeContext.instance().getUserId();

        // ===== 必填校验 =====
        Long accountId = req.getLong("accountId");
        if (accountId == null) {
            String r = HttpUtil.fail("PARAM_ERROR", "accountId 不能为空").toJSONString();
            logger.warn(TAG + " 出参: " + r);
            return r;
        }

        // ===== 分页参数 =====
        int pageNum = req.getIntValue("pageNum");
        int pageSize = req.getIntValue("pageSize");
        if (pageNum <= 0) {
            pageNum = 1;
        }
        if (pageSize <= 0 || pageSize > 200) {
            pageSize = 20;
        }

        // ===== 搜索条件 =====
        String name = req.getString("name");
        String applyDateStart = req.getString("applyDateStart");
        String applyDateEnd = req.getString("applyDateEnd");
        Integer status = req.getInteger("status");
        if (status == null && req.containsKey("status")) {
            // 前端约定传数字（选项 value）；传了中文/字符串视为无效 → 不按状态过滤
            logger.warn(TAG + " status 应传数字（1待提交/2审批/3驳回/4同意），传入值无效，本次不按状态过滤: "
                    + req.get("status"));
        }

        // ===== 构建 WHERE =====
        StringBuilder where = new StringBuilder(" WHERE approver__c=" + userId + " and account__c = ").append(accountId);

        if (name != null && !name.trim().isEmpty()) {
            Set<String> matchedUserIds = queryMatchedUserIds(name.trim(), null, null);
            if (matchedUserIds.isEmpty()) {
                logger.info(TAG + " 姓名搜索无匹配用户，返回空列表 name=" + name.trim());
                logOutList(TAG, true, 0, startTs);
                return emptyResult(pageNum, pageSize, queryPendingCount(accountId, userId));
            }
            where.append(" AND applyUser__c IN (").append(buildInClause(matchedUserIds)).append(")");
        }
        if (applyDateStart != null && !applyDateStart.isEmpty()) {
            long startTsParam = parseDate(applyDateStart);
            if (startTsParam > 0) {
                where.append(" AND createdAt >= ").append(startTsParam);
            }
        }
        if (applyDateEnd != null && !applyDateEnd.isEmpty()) {
            long endTs = parseDateEndOfDay(applyDateEnd);
            if (endTs < Long.MAX_VALUE) {
                where.append(" AND createdAt <= ").append(endTs);
            }
        }
        // status 不传 = 显示全部状态（含历史审批记录）；
        // 传了才按业务状态过滤（过滤用选项 value，平台要求）
        if (status != null) {
            where.append(" AND status__c = ").append(status.intValue());
        }

        // ===== 排序：待审批(status__c=2)恒排最前 =====
        // status__c 的选项 value：1待提交 / 2审批 / 3驳回 / 4同意。
        // 实际不存在"待提交"数据，所以 ORDER BY status__c ASC 就是「待审批 → 驳回 → 同意」（实测按 value 排）。
        // 前端 sorts 只决定同状态内的顺序，追加在后面。
        String orderBy = "status__c ASC";
        String customOrder = buildOrderBy(req.getJSONArray("sorts"), null);
        orderBy += (customOrder != null && !customOrder.trim().isEmpty())
                ? ", " + customOrder : ", createdAt DESC";
        logger.info(TAG + " 排序: " + orderBy);

        // ===== 查基础记录 =====
        String baseSql = "SELECT id, applyUser__c, account__c, "
                + "roleBefore__c, roleAfter__c, status__c, createdAt "
                + "FROM roleRequest__c" + where
                + " ORDER BY " + orderBy + " LIMIT 0, 5000";
        QueryResult<JSONObject> baseRt = queryWithLog(TAG, "roleRequest__c", baseSql);

        List<JSONObject> allRecords = new ArrayList<>();
        if (baseRt != null && baseRt.getRecords() != null) {
            for (Object o : baseRt.getRecords()) {
                allRecords.add((JSONObject) o);
            }
        }

        // ===== 待审批角标数（不受筛选影响）=====
        long pendingCount = queryPendingCount(accountId, userId);

        long total = allRecords.size();

        // 内存分页
        int start = (pageNum - 1) * pageSize;
        if (start < 0) {
            start = 0;
        }
        int end = Math.min(start + pageSize, allRecords.size());
        List<JSONObject> pageRecords = start >= allRecords.size()
                ? Collections.<JSONObject>emptyList() : allRecords.subList(start, end);

        JSONArray list = new JSONArray();
        if (!pageRecords.isEmpty()) {
            Set<String> userIds = new HashSet<>();
            Set<String> accountIds = new HashSet<>();
            Set<String> allRoleIds = new HashSet<>();
            for (JSONObject r : pageRecords) {
                collectId(userIds, r.get("applyUser__c"));
                collectId(accountIds, r.get("account__c"));
                parseMultiIds(allRoleIds, r.get("roleBefore__c"));
                parseMultiIds(allRoleIds, r.get("roleAfter__c"));
            }

            Map<String, JSONObject> userMap = queryUsers(userIds);
            Map<String, String> accountNameMap = queryAccountNames(accountIds);
            Map<String, String> roleNameMap = queryRoleNames(allRoleIds);

            for (JSONObject r : pageRecords) {
                JSONObject item = new JSONObject();
                item.put("id", r.getString("id"));

                String uid = str(r.get("applyUser__c"));
                JSONObject userInfo = userMap.get(uid);
                item.put("userName", userInfo != null ? str(userInfo.get("name")) : "");

                item.put("roleBefore", buildRoleDisplay(str(r.get("roleBefore__c")), roleNameMap));
                item.put("roleAfter", buildRoleDisplay(str(r.get("roleAfter__c")), roleNameMap));

                String aid = str(r.get("account__c"));
                item.put("accountName", accountNameMap.getOrDefault(aid, aid));
                item.put("applyDate", formatDate(r.get("createdAt")));

                // status__c 为 picklist，XOQL 只返回显示名数组；反查回选项 value 后以数字返回
                JSONArray st = r.getJSONArray("status__c");
                if (st != null && !st.isEmpty()) {
                    String label = st.getString(0);
                    Integer statusValue = statusValueOf(label);
                    item.put("status", statusValue != null ? statusValue : label);
                }
                list.add(item);
            }
        }

        JSONObject result = new JSONObject();
        result.put("total", total);
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        result.put("pendingCount", pendingCount);
        result.put("list", list);
        logOutList(TAG, true, list.size(), startTs);
        return HttpUtil.success(result).toJSONString();
    }

    /**
     * 该账户待审批（status__c=2）申请总数，不受列表筛选条件影响，供 Tab 角标展示。
     * 查询失败返回 0（不阻断列表主流程）。
     */
    private long queryPendingCount(Long accountId, Long userId) {
        String sql = "SELECT id FROM roleRequest__c WHERE approver__c=" + userId + " and account__c = " + accountId
                + " AND status__c = 2 LIMIT 0, 5000";
        QueryResult<JSONObject> rt = queryWithLog(TAG, "roleRequest__c(待审批数)", sql);
        return (rt != null && rt.getRecords() != null) ? rt.getRecords().size() : 0;
    }

    private String emptyResult(int pageNum, int pageSize, long pendingCount) {
        JSONObject empty = new JSONObject();
        empty.put("total", 0);
        empty.put("pageNum", pageNum);
        empty.put("pageSize", pageSize);
        empty.put("pendingCount", pendingCount);
        empty.put("list", new JSONArray());
        return HttpUtil.success(empty).toJSONString();
    }
}
