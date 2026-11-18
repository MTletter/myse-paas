package other.myse.coordinator.permission;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.model.QueryResult;
import other.myse.coordinator.utils.HttpUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限管理 - 权限管理列表（other.myse.coordinator.permission）
 *
 * <p>对应页面：账户权限管理 - 权限管理 Tab（用户列表）。</p>
 *
 * <pre>
 * 入参:
 *   accountId     Array/Long 必填  当前账户ID（支持数组、单值）
 *   name          String   可选  姓名模糊
 *   phone         String   可选  电话号模糊
 *   email         String   可选  邮箱号模糊
 *   isInternal    Boolean  可选  是否内部用户（不传=全部）
 *   authDateStart String   可选  上次授权开始日期(yyyy-MM-dd)
 *   authDateEnd   String   可选  上次授权结束日期(yyyy-MM-dd)
 *   roleId        String   可选  权限角色ID筛选(generalRole.id)
 *   sorts         Array    可选  排序，[{field: 字段API名, order: "ASC"|"DESC"}]；默认 updatedAt DESC
 *   pageNum       int      可选  页码(默认1)
 *   pageSize      int      可选  每页条数(默认10,最大200)
 *
 * 返回: { total, pageNum, pageSize, list:[ {
 *   id,            // userRole 记录ID（详情/弹窗/保存/移除用户均用此ID）
 *   userName, phone, email,
 *   roleNames,     // 权限角色，"、"拼接
 *   accountName,
 *   lastLoginDate, // 上次登陆日期(user.lastestLoginAt, yyyy-MM-dd)
 *   lastAuthTime   // 上次授权时间(userRole.updatedAt, yyyy-MM-dd)
 *   userId,
 *   isCoordinator  // 是否协调员：该用户本账户下任一角色的 roleType__c 含"协调员"
 * } ] }
 * </pre>
 *
 */
@RestApi(baseUrl = "/coordinator")
public class PermissionListApi extends PermissionBaseApi {

    /** 日志标识：本接口所有日志统一前缀，便于线上按接口检索 */
    private static final String TAG = "PermissionList";

    /** 主表最大拉取行数（Java 层分页的上限，防止大账户拖垮接口） */
    private static final int MAX_FETCH = 5000;

    /** 默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /** 每页条数上限 */
    private static final int MAX_PAGE_SIZE = 200;

    /** 协调员角色类型关键字：generalRole__c.roleType__c 含该值即视为协调员 */

    @RestMapping(value = "/permission/list", method = RequestMethod.POST)
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
            logOut(TAG, resp);
        }
        return resp;
    }

    /** 业务逻辑（异常统一冒泡到 {@link #list(String)} 兜底，本方法不重复打异常日志） */
    private String doList(String body, long startTs) {
        if (body == null || body.isEmpty()) {
            return fail("PARAM_EMPTY", "请求体不能为空", startTs);
        }
        JSONObject req = JSON.parseObject(body);

        // ---------- 1) 必填：账户ID ----------
        LinkedHashSet<String> accountIds = parseAccountIds(req.get("accountId"));
        if (accountIds.isEmpty()) {
            return fail("PARAM_ERROR", "accountId 不能为空", startTs);
        }

        // ---------- 2) 分页 ----------
        int pageNum = Math.max(1, req.getIntValue("pageNum"));
        int pageSize = req.getIntValue("pageSize");
        if (pageSize <= 0 || pageSize > MAX_PAGE_SIZE) {
            pageSize = DEFAULT_PAGE_SIZE;
        }

        // ---------- 3) 搜索条件 + WHERE ----------
        String name = trimToNull(req.getString("name"));
        String phone = trimToNull(req.getString("phone"));
        String email = trimToNull(req.getString("email"));
        Boolean isInternal = req.getBoolean("isInternal");
        String authDateStart = trimToNull(req.getString("authDateStart"));
        String authDateEnd = trimToNull(req.getString("authDateEnd"));
        String roleId = trimToNull(req.getString("roleId"));
        String orderBy = buildOrderBy(req.getJSONArray("sorts"), "updatedAt DESC");

        logger.info(TAG + " 查询条件: accountId=" + accountIds
                + ", name=" + name + ", phone=" + phone + ", email=" + email
                + ", isInternal=" + isInternal
                + ", authDate=[" + authDateStart + "~" + authDateEnd + "]"
                + ", roleId=" + roleId
                + ", sort=" + orderBy
                + ", page=" + pageNum + "/" + pageSize);

        StringBuilder where = buildWhere(accountIds, authDateStart, authDateEnd);
        // 无任何用户侧条件时 queryMatchedUserIdsEx 返回 null（=不过滤）；返回空集合说明确实无匹配用户 → 直接空结果
        Set<String> matchedUserIds = queryMatchedUserIdsEx(name, phone, email, isInternal);
        if (matchedUserIds != null) {
            if (matchedUserIds.isEmpty()) {
                logger.info(TAG + " 用户侧条件无匹配用户，直接返回空列表");
                return success(emptyResult(pageNum, pageSize), 0, startTs);
            }
            where.append(" AND user__c IN (").append(buildInClause(matchedUserIds)).append(")");
        }

        // ---------- 4) 查 userRole__c（排序在此层做，Java 层分页才能拿到正确的当前页）----------
        List<JSONObject> records = queryUserRoles(where, orderBy);

        // ---------- 5) 角色筛选（role__c 多值字段，只能 Java 层过滤）----------
        List<JSONObject> filtered = filterByRole(records, roleId);

        // ---------- 6) Java 层分页 ----------
        int total = filtered.size();
        List<JSONObject> pageRecords = page(filtered, pageNum, pageSize);
        logger.info(TAG + " 查询结果: 命中=" + records.size()
                + ", 角色筛选后=" + total
                + ", 本页=" + pageRecords.size());

        // ---------- 7) 批量补关联名称并组装 ----------
        JSONArray list = buildList(pageRecords);
        return success(buildResult(total, pageNum, pageSize, list), list.size(), startTs);
    }

    // ===================== 构建查询 =====================

    /**
     * 构建 userRole__c 主表 WHERE（账户 + 上次授权时间范围）。
     * <p>user__c 条件依赖用户侧查询结果，由调用方在返回的片段后继续拼接。</p>
     *
     * @return 以 " WHERE ..." 开头的片段
     */
    private StringBuilder buildWhere(Set<String> accountIds, String authDateStart, String authDateEnd) {
        StringBuilder where = new StringBuilder(" WHERE purchaseId__c IN (").append(buildInClause(accountIds)).append(")");
        if (authDateStart != null) {
            where.append(" AND updatedAt >= ").append(parseDate(authDateStart));
        }
        if (authDateEnd != null) {
            where.append(" AND updatedAt <= ").append(parseDateEndOfDay(authDateEnd));
        }
        return where;
    }

    /** 查 userRole__c 全量候选（上限 {@link #MAX_FETCH}），返回记录列表。 */
    private List<JSONObject> queryUserRoles(StringBuilder where, String orderBy) {
        String sql = "SELECT id, user__c, purchaseId__c, role__c, updatedAt FROM userRole__c"
                + where
                + " ORDER BY " + orderBy
                + " LIMIT 0, " + MAX_FETCH;
        QueryResult<JSONObject> rt = queryWithLog(TAG, "userRole__c", sql);
        List<JSONObject> list = new ArrayList<>();
        if (rt != null && rt.getRecords() != null) {
            for (Object o : rt.getRecords()) {
                list.add((JSONObject) o);
            }
        }
        if (list.size() >= MAX_FETCH) {
            logger.warn(TAG + " 候选记录已达上限 " + MAX_FETCH + " 条，可能存在截断，请确认账户规模");
        }
        return list;
    }

    /** 按基础角色筛选：roleId 为空直接返回原列表；role__c 多值，命中即保留。 */
    private List<JSONObject> filterByRole(List<JSONObject> records, String roleId) {
        if (roleId == null) {
            return records;
        }
        List<JSONObject> filtered = new ArrayList<>();
        for (JSONObject rec : records) {
            Set<String> rids = new HashSet<>();
            parseMultiIds(rids, rec.get("role__c"));
            if (rids.contains(roleId)) {
                filtered.add(rec);
            }
        }
        logger.info(TAG + " 角色筛选: roleId=" + roleId + ", " + records.size() + " → " + filtered.size() + " 条");
        return filtered;
    }

    /** Java 层分页，越界返回空列表。 */
    private List<JSONObject> page(List<JSONObject> all, int pageNum, int pageSize) {
        int start = (pageNum - 1) * pageSize;
        if (start >= all.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(all.subList(start, Math.min(start + pageSize, all.size())));
    }

    // ===================== 结果组装 =====================

    /**
     * 批量补齐本页所需的 user / account / generalRole 名称后逐条组装。
     * <p>只对当前页做关联查询（3 次批量查询），不做逐条查询。</p>
     */
    private JSONArray buildList(List<JSONObject> pageRecords) {
        JSONArray list = new JSONArray();
        if (pageRecords.isEmpty()) {
            return list;
        }

        // ---- 批量收集关联ID ----
        Set<String> userIds = new HashSet<>();
        Set<String> purchaseIds = new HashSet<>();
        Set<String> roleIds = new HashSet<>();
        for (JSONObject r : pageRecords) {
            collectId(userIds, r.get("user__c"));
            collectId(purchaseIds, r.get("purchaseId__c"));
            parseMultiIds(roleIds, r.get("role__c"));
        }

        // ---- 批量查询关联数据 ----
        Map<String, JSONObject> userMap = queryUsers(userIds);
        Map<String, String> accountNameMap = queryAccountNames(purchaseIds);
        Map<String, JSONObject> roleMap = queryRoleList(roleIds);
        Map<String, String> roleNameMap = new HashMap<>();
        Map<String, String> roleTypeMap = new HashMap<>();
        for (Map.Entry<String, JSONObject> e : roleMap.entrySet()) {
            roleNameMap.put(e.getKey(), str(e.getValue().get("name")));
            roleTypeMap.put(e.getKey(), parseFirstArrayItem(str(e.getValue().get("roleType__c"))));
        }
        String loginField = getLoginField();
        logger.info(TAG + " 关联数据: 本页" + pageRecords.size() + " 行 → user " + userIds.size()
                + " 个/account " + purchaseIds.size() + " 个/role " + roleIds.size()
                + " 个, 命中 user " + userMap.size() + "、account " + accountNameMap.size()
                + "、role " + roleNameMap.size() + " 个");

        // ---- 逐条组装 ----
        int missingUser = 0;
        int coordinatorCount = 0;
        for (JSONObject r : pageRecords) {
            String userId = r.getString("user__c");
            JSONObject item = new JSONObject();
            item.put("id", r.getString("id"));
            item.put("userId", userId);

            JSONObject userInfo = userMap.get(userId);
            if (userInfo != null) {
                item.put("userName", str(userInfo.get("name")));
                item.put("phone", str(userInfo.get("phone")));
                item.put("email", str(userInfo.get("personalEmail")));
                item.put("lastLoginDate", loginField != null ? formatDate(userInfo.get(loginField)) : "");
            } else {
                // 用户被删/跨租户不可见时不让整行崩掉，字段留空
                missingUser++;
                item.put("userName", "");
                item.put("phone", "");
                item.put("email", "");
                item.put("lastLoginDate", "");
            }

            item.put("roleNames", buildRoleDisplay(str(r.get("role__c")), roleNameMap));
            // 是否协调员：该用户在本账户下任一角色的角色类型含"协调员"即true（只认已授权角色）
            boolean isCoordinator = isCoordinator(r.get("role__c"), roleTypeMap);
            item.put("isCoordinator", isCoordinator);
            if (isCoordinator) {
                coordinatorCount++;
            }
            String purchaseId = str(r.get("purchaseId__c"));
            item.put("accountName", accountNameMap.getOrDefault(purchaseId, purchaseId));
            item.put("lastAuthTime", formatDate(r.get("updatedAt")));
            list.add(item);
        }
        if (missingUser > 0) {
            logger.warn(TAG + " 有 " + missingUser + " 条记录未查到 user 明细（用户可能已删除），姓名/联系方式留空");
        }
        logger.info(TAG + " 本页协调员标记: 协调员 " + coordinatorCount + " / 共 " + pageRecords.size() + " 条");
        return list;
    }

    /**
     * 判定是否协调员：userRole__c.role__c 中任一角色的 generalRole__c.roleType__c 含"协调员"即 true。
     *
     * @param roleRaw     userRole__c.role__c 原始值（多值）
     * @param roleTypeMap 角色id → 角色类型（首值），来自 {@link #queryRoleList(Set)}
     */
    private boolean isCoordinator(Object roleRaw, Map<String, String> roleTypeMap) {
        Set<String> rids = new HashSet<>();
        parseMultiIds(rids, roleRaw);
        for (String rid : rids) {
            String type = roleTypeMap.get(rid);
            if (type != null && type.contains(ROLE_TYPE_COORDINATOR)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 批量查基础角色（id → {name, roleType__c}）。
     * <p>基类 {@code queryRoleNames} 只取 name，这里需要角色类型以判定协调员，故本类自查。</p>
     */
    private Map<String, JSONObject> queryRoleList(Set<String> roleIds) {
        Map<String, JSONObject> map = new HashMap<>();
        if (roleIds == null || roleIds.isEmpty()) {
            return map;
        }
        String sql = "SELECT id, name, roleType__c FROM generalRole__c WHERE id IN (" + buildInClause(roleIds) + ")";
        QueryResult<JSONObject> rt = queryWithLog(TAG, "generalRole__c(含角色类型)", sql);
        if (rt != null && rt.getRecords() != null) {
            for (Object o : rt.getRecords()) {
                JSONObject r = (JSONObject) o;
                map.put(r.getString("id"), r);
            }
        }
        return map;
    }

    private JSONObject buildResult(int total, int pageNum, int pageSize, JSONArray list) {
        JSONObject result = new JSONObject();
        result.put("total", total);
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        result.put("list", list);
        return result;
    }

    private JSONObject emptyResult(int pageNum, int pageSize) {
        return buildResult(0, pageNum, pageSize, new JSONArray());
    }

    // ===================== 入参解析 =====================

    /**
     * 解析 accountId：支持数组 [1,2]、逗号串 "1,2"、单值 1/"1"；去重且保持传入顺序，非法项跳过。
     */
    private LinkedHashSet<String> parseAccountIds(Object v) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (v == null) {
            return ids;
        }
        if (v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            for (int i = 0; i < arr.size(); i++) {
                addAccountId(ids, arr.get(i), "accountId[" + i + "]");
            }
            return ids;
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            return ids;
        }
        if (s.startsWith("[")) {
            // 传了字符串形态的数组
            try {
                JSONArray arr = JSON.parseArray(s);
                for (int i = 0; i < arr.size(); i++) {
                    addAccountId(ids, arr.get(i), "accountId[" + i + "]");
                }
                return ids;
            } catch (Exception ignored) {
                // 落回逗号串解析
            }
        }
        for (String part : s.split(",")) {
            addAccountId(ids, part, "accountId");
        }
        return ids;
    }

    private void addAccountId(LinkedHashSet<String> target, Object raw, String label) {
        String s = raw == null ? "" : raw.toString().trim();
        if (s.isEmpty()) {
            return;
        }
        if (!s.matches("\\d+")) {
            logger.warn(TAG + " " + label + " 非法值已跳过: " + s);
            return;
        }
        target.add(s);
    }

    // ===================== 响应与工具 =====================

}