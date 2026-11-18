package other.myse.coordinator.account;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.context.ScriptRuntimeContext;
import com.rkhd.platform.sdk.model.QueryResult;
import other.myse.coordinator.api.UserPermissionBaseApi;
import other.myse.coordinator.utils.HttpUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 账户访问管理 - 列表页（other.myse.coordinator.account）
 *
 * <p>账户访问管理 Tab 的列表：与当前账户同属一个 CHAIN（连锁）的所有经销商客户（公司）。
 * 列：公司名 / 受影响账号（该账户下用户数）/ 允许账户访问（开关）。</p>
 *
 * <pre>
 * 数据链路（6 次查询，全程批量、无 N+1）：
 *   ① <b>accountId → CHAINID</b>：走 CHAIN 客户中间表 {@code customer__c}
 *      （{@code customer__c.customer__c = accountId} → {@code customer__c.chainId__c}）
 *      ⚠️ 2026-10-16 起不再读 {@code account.chainID__c}（该字段现已全部为空）
 *   ② CHAINID 相同 → 全部相关账户 id（{@code customer__c.customer__c}），当前账户自身兜底加入
 *   ②' <b>协调员过滤</b>：只保留"当前登录用户在该账户下是协调员"的账户（非协调员的直接不返回）
 *   ③ 按过滤后 id 批量查 account → accountName / kunnr__c(兜底 zkunnr__c) / ort01__c / isAuthorized__c
 *   ④ 按过滤后 id 批量查 userRole__c → 按 purchaseId__c 分组计数 = 各账户受影响账号数
 *
 * 入参:
 *   accountId   String  必填  当前账户ID（Account 对象 id）
 *
 * 注：CHAIN 未维护（accountId 在 customer__c 中无记录或 chainId__c 为空）或账户查不到时，
 *     返回空列表，不报错；当前登录用户在任何账户下都不是协调员时，同样返回空列表。
 *
 * 返回: {
 *   userName: "",        // 固定空串（沿用旧版结构，前端仍在用）
 *   departmentName: "",  // 固定空串
 *   totalCount: 7,
 *   list: [
 *     {
 *       accountId: "xxx",
 *       accountName: "XYZ工业集团公司",              // account.accountName
 *       displayName: "002345678-XYZ工业集团公司-上海", // 编号-名称-城市 拼接（缺段自动省略）
 *       accountNumber: "002345678",                // kunnr__c，兜底 zkunnr__c
 *       city: "上海",                               // ort01__c
 *       relatedUserCount: 23,                      // 该账户下 userRole 记录数
 *       allowAccess: true                          // account.isAuthorized__c（boolean，默认 false）
 *     }
 *   ]
 * }
 * </pre>
 */
@RestApi(baseUrl = "/coordinator")
public class AccountAccessListApi extends UserPermissionBaseApi {

    /** 日志标识：本接口所有日志统一前缀，便于线上按接口检索 */
    private static final String TAG = "AccountAccessList";

    /** 同链客户最大拉取条数（连锁下账户数量可控，设上限防误配 chainID 拉爆内存） */
    private static final int MAX_CHAIN_ACCOUNTS = 500;

    /** 协调员角色类型关键字：generalRole__c.roleType__c 含该值即视为协调员 */

    @RestMapping(value = "/account/list", method = RequestMethod.POST)
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
        String accountId = trimToNull(req.getString("accountId"));
        if (accountId == null) {
            return fail("PARAM_ERROR", "accountId 不能为空", startTs);
        }

        // ---------- ① 当前账户 → CHAINID（走 CHAIN 客户中间表 customer__c）----------
        String chainId = queryChainIdByAccount(accountId);
        if (chainId == null) {
            // 未维护 CHAIN / 账户不存在时返回空列表（不报错）
            logger.warn(TAG + " 账户未关联 CHAIN 客户或 CHAINID 为空，返回空列表, accountId=" + accountId);
            return success(buildResult(new JSONArray()), 0, startTs);
        }

        // ---------- ② CHAINID → 相关客户 id 集合 ----------
        Set<String> accountIds = queryAccountIdsByChainId(chainId, accountId);
        if (accountIds.isEmpty()) {
            logger.warn(TAG + " CHAIN 无关联客户, chainId=" + chainId);
            return success(buildResult(new JSONArray()), 0, startTs);
        }

        // ---------- ②' 只保留"当前用户是协调员"的账户 ----------
        // ⚠️ 当前用户必须取服务端会话，不能信任入参 userId：
        //    入参可任意伪造，会绕过「协调员」过滤看到他人有权限的账户。
        Long currentUserId = ScriptRuntimeContext.instance().getUserId();
        if (currentUserId == null) {
            logger.error(TAG + " 取不到当前登录用户（服务端会话为空），无法判定协调员，返回空列表, accountId=" + accountId);
            return success(buildResult(new JSONArray()), 0, startTs);
        }
        if (req.getLong("userId") != null && !req.getLong("userId").equals(currentUserId)) {
            logger.warn(TAG + " 入参 userId 与当前登录用户不一致，已忽略入参、按会话用户处理, 入参="
                    + req.getLong("userId") + ", 当前=" + currentUserId);
        }
        Set<String> coordinatorAccountIds = filterCoordinatorAccounts(accountIds, currentUserId);
        if (coordinatorAccountIds.isEmpty()) {
            logger.warn(TAG + " 当前用户在该 CHAIN 下没有任何协调员账户，返回空列表, userId=" + currentUserId
                    + ", chainId=" + chainId);
            return success(buildResult(new JSONArray()), 0, startTs);
        }
        // 后续只针对过滤后的账户做关联查询，减少无谓开销
        accountIds = coordinatorAccountIds;

        // ---------- ③④ 批量补关联数据（只查当前用户有协调员权限的账户） ----------
        Map<String, JSONObject> accountMap = queryAccountInfos(accountIds);
        Map<String, Long> userCountMap = queryAccountUserCounts(accountIds);
        logger.info(TAG + " 关联数据: 客户 " + accountIds.size() + " 个 → account 明细命中 "
                + accountMap.size() + " 个, userRole 统计命中 " + userCountMap.size() + " 个");

        // ---------- ⑤ 组装 ----------
        JSONArray list = buildList(accountIds, accountMap, userCountMap);
        return success(buildResult(list), list.size(), startTs);
    }

    // ===================== 数据查询 =====================

    /**
     * 查当前账户所属的 CHAINID：accountId → {@code customer__c} 中间表 → {@code chainId__c}。
     *
     * <p><b>2026-10-16 口径变更</b>：不再读 {@code account.chainID__c}（实测该字段现已全部为空），
     * 改走 CHAIN 客户中间表 {@code customer__c}：
     * <pre>
     *   customer__c.customer__c = accountId   （客户 → 账户）
     *   customer__c.chainId__c  = CHAINID__c  （detail 字段，指向 CHAINID__c 记录）
     * </pre>
     * 一个账户理论上只属于一个 CHAIN；若命中多条，取第一条并 warn（数据异常时保持确定性）。</p>
     *
     * @return CHAINID（customer__c.id）；未关联 CHAIN / chainId__c 为空 / 查询异常 均返回 null
     */
    private String queryChainIdByAccount(String accountId) {
        if (accountId == null || accountId.isEmpty()) {
            return null;
        }
        String sql = "SELECT id, chainId__c FROM customer__c WHERE customer__c = '"
                + escapeSql(accountId) + "' LIMIT 0, 10";
        QueryResult<JSONObject> rt = queryWithLog(TAG, "customer__c(账户→CHAIN)", sql);
        if (rt == null || rt.getRecords() == null || rt.getRecords().isEmpty()) {
            logger.warn(TAG + " 账户未在 customer__c 中关联 CHAIN 客户, accountId=" + accountId);
            return null;
        }
        String chainId = str(rt.getRecords().get(0).get("chainId__c")).trim();
        if (rt.getRecords().size() > 1) {
            logger.warn(TAG + " 账户在 customer__c 中命中 " + rt.getRecords().size()
                    + " 条 CHAIN 记录（一个账户应只属于一个 CHAIN），取第一条 chainId=" + chainId
                    + ", accountId=" + accountId);
        }
        return chainId.isEmpty() ? null : chainId;
    }

    /**
     * 按 CHAINID 查出全部相关客户 id（含当前账户自身）。
     * <p>走 CHAIN 客户中间表 {@code customer__c}：{@code chainId__c = ?} → {@code customer__c}（账户 id）。
     * 当前账户兜底加入：防止该账户在 customer__c 中缺记录、或自身被同链查询漏掉的极端情况。</p>
     *
     * @return 相关客户 id，LinkedHashSet 去重且保持查询顺序（当前账户始终排在最后）
     */
    private Set<String> queryAccountIdsByChainId(String chainId, String currentAccountId) {
        Set<String> ids = new LinkedHashSet<>();
        if (chainId == null || chainId.isEmpty()) {
            return ids;
        }
        String sql = "SELECT id, customer__c FROM customer__c WHERE chainId__c = '" + escapeSql(chainId)
                + "' LIMIT 0, " + MAX_CHAIN_ACCOUNTS;
        QueryResult<JSONObject> rt = queryWithLog(TAG, "customer__c(同CHAIN)", sql);
        int rows = 0;
        if (rt != null && rt.getRecords() != null) {
            rows = rt.getRecords().size();
            for (JSONObject o : rt.getRecords()) {
                String aid = str(o.get("customer__c")).trim();
                if (!aid.isEmpty()) {
                    ids.add(aid);
                } else {
                    logger.warn(TAG + " customer__c 记录缺 customer__c（客户账户），已跳过: id=" + o.get("id"));
                }
            }
        }
        if (rows >= MAX_CHAIN_ACCOUNTS) {
            logger.warn(TAG + " 同 CHAIN 客户已达上限 " + MAX_CHAIN_ACCOUNTS + " 条，可能存在截断, chainId=" + chainId);
        }
        // 当前账户兜底（即使它自身未出现在同链结果里，也必须展示）
        ids.add(currentAccountId);
        logger.info(TAG + " chainId=" + chainId + " → customer__c 命中 " + rows
                + " 行, 去重后客户 " + ids.size() + " 个（已含当前账户）");
        return ids;
    }

    /**
     * 批量查 account 详情：id → {accountName, kunnr__c, zkunnr__c, ort01__c, isAuthorized__c}
     */
    private Map<String, JSONObject> queryAccountInfos(Set<String> accountIds) {
        Map<String, JSONObject> map = new HashMap<>();
        if (accountIds == null || accountIds.isEmpty()) {
            return map;
        }
        String sql = "SELECT id, accountName, kunnr__c, zkunnr__c, ort01__c, isAuthorized__c "
                + "FROM account WHERE id IN (" + buildInClause(accountIds) + ")";
        QueryResult<JSONObject> rt = queryWithLog(TAG, "account", sql);
        if (rt != null && rt.getRecords() != null) {
            for (JSONObject o : rt.getRecords()) {
                JSONObject a = o;
                map.put(str(a.get("id")), a);
            }
        }
        return map;
    }

    /**
     * 协调员过滤：只保留"当前登录用户在该账户下是协调员"的账户。
     * <p>链路：userRole__c(user__c=当前用户 AND purchaseId__c IN 候选账户) → 取 role__c
     * → generalRole__c.roleType__c 含"协调员"即视为该账户下是协调员（只认已授权角色，审批中不算）。</p>
     *
     * @param accountIds 候选账户 id
     * @param userId    当前登录用户 id
     * @return 保留的账户 id（保持候选集顺序）；非协调员的账户已被剔除
     */
    private Set<String> filterCoordinatorAccounts(Set<String> accountIds, Long userId) {
        // purchaseId__c 为数值字段，IN 不加引号；非数字 id 跳过
        String ids = buildNumericIn(accountIds, "accountId");
        if (ids.isEmpty()) {
            return new LinkedHashSet<>();
        }

        // 1) 查当前用户在这些账户下的已授权角色
        String sql = "SELECT id, purchaseId__c, role__c FROM userRole__c"
                + " WHERE user__c = '" + escapeSql(String.valueOf(userId))
                + "' AND purchaseId__c IN (" + ids + ")";
        QueryResult<JSONObject> rt = queryWithLog(TAG, "userRole__c(当前用户协调员判定)", sql);

        // purchaseId__c → 该账户下当前用户的角色id集合
        Map<String, Set<String>> accountRoleMap = new HashMap<>();
        Set<String> allRoleIds = new LinkedHashSet<>();
        if (rt != null && rt.getRecords() != null) {
            for (JSONObject o : rt.getRecords()) {
                String purchaseId = str(o.get("purchaseId__c")).trim();
                if (purchaseId.isEmpty()) {
                    continue;
                }
                Set<String> roleIds = accountRoleMap.computeIfAbsent(purchaseId, k -> new LinkedHashSet<>());
                parseMultiIds(roleIds, o.get("role__c"));
                allRoleIds.addAll(roleIds);
            }
        }

        // 2) 查这些角色的角色类型，识别哪些是协调员角色
        Set<String> coordinatorRoleIds = queryCoordinatorRoleIds(allRoleIds);

        // 3) 逐账户判定：该账户下当前用户的角色里只要有一个协调员角色就保留
        Set<String> kept = new LinkedHashSet<>();
        for (String aid : accountIds) {
            Set<String> roleIds = accountRoleMap.get(aid.trim());
            if (roleIds == null || roleIds.isEmpty()) {
                logger.info(TAG + " 剔除账户（当前用户无授权角色）: accountId=" + aid);
                continue;
            }
            boolean isCoordinator = false;
            for (String rid : roleIds) {
                if (coordinatorRoleIds.contains(rid)) {
                    isCoordinator = true;
                    break;
                }
            }
            if (isCoordinator) {
                kept.add(aid);
            } else {
                logger.info(TAG + " 剔除账户（当前用户非协调员）: accountId=" + aid);
            }
        }
        logger.info(TAG + " 协调员过滤: userId=" + userId + ", 候选 " + accountIds.size()
                + " 个 → 保留 " + kept.size() + " 个（其角色 " + allRoleIds.size()
                + " 个，其中协调员角色 " + coordinatorRoleIds.size() + " 个）");
        return kept;
    }

    /**
     * 从角色id集合中挑出"协调员"角色：generalRole__c.roleType__c 解析首值后含"协调员"即命中。
     * roleType__c 是单选项数组（形如 ["协调员角色"]），取首值即可。
     * <p>2026-10-19：实现已下沉到基类 {@link #queryCoordinatorRoleIds(String, Set)}，
     * 这里保留同名方法作为薄封装，避免本类其余调用点改动。</p>
     */
    private Set<String> queryCoordinatorRoleIds(Set<String> roleIds) {
        return queryCoordinatorRoleIds(TAG, roleIds);
    }

    /**
     * 统计每个账户的 userRole__c 记录数（即该账户下受影响账号数）。
     * <p>purchaseId__c 是数值字段，IN 子句不带引号；非数字 id 直接跳过防{@code Long.parseLong} 抛错。</p>
     *
     * @return Map&lt;accountId, 该账户 userRole 记录数&gt;；无记录的账户不出现在 Map 中（取默认 0）
     */
    private Map<String, Long> queryAccountUserCounts(Set<String> accountIds) {
        Map<String, Long> map = new HashMap<>();
        if (accountIds == null || accountIds.isEmpty()) {
            return map;
        }
        // 账户 id 为雪花ID（纯数字）；purchaseId__c 为数值字段，IN 不加引号
        String ids = buildNumericIn(accountIds, "accountId");
        if (ids.isEmpty()) {
            return map;
        }
        String sql = "SELECT id, purchaseId__c FROM userRole__c WHERE purchaseId__c IN (" + ids + ")";
        QueryResult<JSONObject> rt = queryWithLog(TAG, "userRole__c", sql);
        if (rt != null && rt.getRecords() != null) {
            for (JSONObject o : rt.getRecords()) {
                String aid = str(o.get("purchaseId__c")).trim();
                if (!aid.isEmpty()) {
                    map.put(aid, map.getOrDefault(aid, 0L) + 1);
                }
            }
        }
        return map;
    }

    // ===================== 结果组装 =====================

    /**
     * 逐个账户组装列表行。
     * <p>account 明细查不到的账户不丢行（名称等留空），仅 warn 提示，方便发现脏数据。</p>
     */
    private JSONArray buildList(Set<String> accountIds, Map<String, JSONObject> accountMap,
                                Map<String, Long> userCountMap) {
        JSONArray list = new JSONArray();
        int missingInfo = 0;
        int enabledCount = 0;
        for (String aid : accountIds) {
            JSONObject info = accountMap.get(aid);
            if (info == null) {
                missingInfo++;
            }

            String accountName = info != null ? str(info.get("accountName")) : "";
            // 编号：kunnr__c 优先，为空兜底 zkunnr__c
            String number = info != null ? str(info.get("kunnr__c")) : "";
            if (number.isEmpty() && info != null) {
                number = str(info.get("zkunnr__c"));
            }
            String city = info != null ? str(info.get("ort01__c")) : "";
            // ⚠️ info 可能为 null（账户查不到），这里必须保护，否则整接口 500
            JSONArray allowAccess = info == null ? null : info.getJSONArray("isAuthorized__c");
            boolean allow = allowAccess != null && !allowAccess.isEmpty()
                    && "是".equals(allowAccess.getString(0));
            if (allow) {
                enabledCount++;
            }

            JSONObject item = new JSONObject();
            item.put("accountId", aid);
            item.put("accountName", accountName);
            item.put("displayName", buildDisplayName(number, accountName, city));
            item.put("accountNumber", number);
            item.put("city", city);
            item.put("relatedUserCount", userCountMap.getOrDefault(aid, 0L));
            item.put("allowAccess", allow);
            list.add(item);
        }
        if (missingInfo > 0) {
            logger.warn(TAG + " 有 " + missingInfo + "/" + accountIds.size()
                    + " 个账户未查到 account 明细（已删除或不可见），名称等字段留空");
        }
        logger.info(TAG + " 列表组装完成: 共 " + list.size() + " 个客户, 其中允许访问(allowAccess=true) "
                + enabledCount + " 个");
        return list;
    }

    private JSONObject buildResult(JSONArray list) {
        JSONObject result = new JSONObject();
        result.put("userName", "");
        result.put("departmentName", "");
        result.put("totalCount", list.size());
        result.put("list", list);
        return result;
    }

    /** 拼接展示名：编号-名称-城市（缺段自动省略） */
    private String buildDisplayName(String number, String name, String city) {
        List<String> parts = new ArrayList<>();
        if (number != null && !number.isEmpty()) parts.add(number);
        if (name != null && !name.isEmpty()) parts.add(name);
        if (city != null && !city.isEmpty()) parts.add(city);
        return String.join("-", parts);
    }

    // ===================== 响应与工具 =====================

}