package other.myse.messagecenter.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.data.model.User;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;
import other.myse.messagecenter.utils.HttpUtil;
import other.myse.messagecenter.utils.PermissionChangeGuard;

import java.util.*;

/**
 * 我的账号信息查询接口
 * <p>
 * 查询当前登录用户的个人信息、通知设置、权限信息（按账户分组）。
 * 后端对象：User（标准对象）+ userRole__c + generalRole__c + accountRole__c + roleRequest__c + account。
 * </p>
 *
 * <p><b>销售易 API 路径</b>：POST /rest/data/v2.0/scripts/api/messagecenter/my-account</p>
 * <p><b>入参</b>：{ "userId": 用户数据id, "accountId": 当前账户id（可选） }</p>
 * <p><b>accountId 语义</b>：传了则按「相关客户」口径返回——先取该账户所属的 <b>CHAIN</b>，
 * 再查出同一 CHAIN 下的<b>全部客户</b>作为 accounts（用户在其中某些账户下没有 userRole 时 roleList 为空数组）；
 * 该账户未关联 CHAIN（数据未维护）或同 CHAIN 查询无结果 → <b>退化为仅返回当前账户</b>，
 * 保证至少能看到当前这个客户的数据（不会返回空列表）。<b>不传 accountId 时行为不变</b>（返回用户所有有角色的账户）。</p>
 *
 * <p><b>CHAIN 查询口径（2026-10-16 变更）</b>：走 CHAIN 客户中间表 {@code customer__c}，
 * 不再读 {@code account.chainID__c}（该字段现已全部为空）：
 * <pre>
 *   accountId → customer__c WHERE customer__c = accountId → chainId__c
 *   chainId__c → customer__c WHERE chainId__c = chainId  → 各行的 customer__c（账户 id）
 * </pre>
 * 当前账户始终会加入结果集兜底（即使它在 customer__c 中缺记录）。</p>
 *
 * <p><b>⚠️ XOQL 语法提醒</b>：本文件所有查询<b>不带表别名</b>（如 {@code SELECT id FROM customer__c}）。
 * 带别名的写法（{@code FROM customer__c c}）会静默返回 0 条且 WHERE 失效。</p>
 */
@RestApi(baseUrl = "/messagecenter")
public class MyAccountApi {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 日志统一前缀，便于线上按接口检索 */
    private static final String TAG = "MyAccount";

    /** 查询单次上限 */
    private static final int MAX_ROWS = 5000;

    /** 同 CHAIN 客户数上限，达到即可能截断（需告警） */
    private static final int MAX_CHAIN_ACCOUNTS = 5000;

    // 通知方式码值 → 显示名
    private static final Map<Integer, String> METHOD_LABEL_MAP = new HashMap<>();
    static {
        METHOD_LABEL_MAP.put(1, "站内信");
        METHOD_LABEL_MAP.put(2, "电子邮件");
    }

    // roleRequest__c.status__c 码值
    private static final int REQUEST_STATUS_PENDING = 2;   // 审批中

    @RestMapping(value = "/my-account", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        long startTs = System.currentTimeMillis();
        try {
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = PermissionChangeGuard.checkPermissionChanged(TAG);
            if (guard != null) {
                return guard;
            }
            if (body == null || body.isEmpty()) {
                logger.warn(TAG + " 请求体为空，返回 PARAM_EMPTY");
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            JSONObject requestBody;
            try {
                requestBody = JSON.parseObject(body);
            } catch (Exception e) {
                logger.error(TAG + " 请求体不是合法 JSON: " + body, e);
                return HttpUtil.fail("PARAM_ERROR", "请求体不是合法 JSON").toJSONString();
            }
            Long userId = requestBody.getLong("userId");
            if (userId == null) {
                logger.warn(TAG + " 缺少 userId，返回 PARAM_ERROR, 入参=" + body);
                return HttpUtil.fail("PARAM_ERROR", "userId 不能为空").toJSONString();
            }
            // accountId：当前账户（可选）。传了则按 CHAIN 查「相关客户」，不传则走原逻辑
            String accountId = trimToNull(requestBody.get("accountId"));
            logger.info(TAG + " 查询账号信息 userId=" + userId + " accountId=" + accountId);

            // 1. 加载用户记录
            User u = new User();
            u.setId(userId);
            User user;
            try {
                user = XObjectService.instance().get(u);
            } catch (Exception e) {
                logger.warn(TAG + " 用户查询失败(视为不存在) userId=" + userId, e);
                return HttpUtil.fail("USER_NOT_FOUND", "用户不存在 userId=" + userId).toJSONString();
            }
            if (user == null) {
                logger.warn(TAG + " 用户不存在 userId=" + userId);
                return HttpUtil.fail("USER_NOT_FOUND", "用户不存在 userId=" + userId).toJSONString();
            }

            // 2. 个人信息
            JSONObject personalInfo = new JSONObject();
            personalInfo.put("name", user.getName());
            personalInfo.put("mobilePhone", user.getPhone());
            personalInfo.put("email", user.getPersonalEmail());

            // 3. 通知设置
            //   receiveMethod：拼接 User.notificationMethod__c（multipicklist Integer[]），
            //                  1=站内信 / 2=电子邮件；空时默认「站内信」
            //   receiveEmail：取 User.noticeEmail__c，null 兜底转空串
            JSONObject notificationSettings = new JSONObject();
            notificationSettings.put("receiveMethod", buildReceiveMethod(user.getAttribute("notificationMethod__c")));
            Object noticeEmailObj = user.getAttribute("noticeEmail__c");
            notificationSettings.put("receiveEmail", noticeEmailObj == null ? "" : noticeEmailObj);

            // 4. 权限信息（按账户分组）
            JSONObject permissionInfo = buildPermissionInfo(userId, accountId);

            JSONObject data = new JSONObject();
            data.put("personalInfo", personalInfo);
            data.put("notificationSettings", notificationSettings);
            data.put("permissionInfo", permissionInfo);

            logger.info(TAG + " 查询完成 userId=" + userId + " name=" + user.getName()
                    + " accountCount=" + permissionInfo
                    + " 耗时=" + (System.currentTimeMillis() - startTs) + "ms");
            return HttpUtil.success(data).toJSONString();

        } catch (Exception e) {
            logger.error(TAG + " 执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    // ===================== 权限信息组装 =====================

    /**
     * 组装权限信息：userRole__c → 按账户分组 → 每账户展开 role__c → 状态 + 权限描述
     *
     * @param accountId 当前账户 id（可选）。非空时按 CHAIN 取「相关客户」作为返回范围
     */
    private JSONObject buildPermissionInfo(Long userId, String accountId) {
        try {
            return doBuildPermissionInfo(userId, accountId);
        } catch (Exception e) {
            logger.error("buildPermissionInfo 异常 userId=" + userId + " accountId=" + accountId, e);
            JSONObject empty = new JSONObject();
            empty.put("totalCount", 0);
            empty.put("accounts", new JSONArray());
            return empty;
        }
    }

    private JSONObject doBuildPermissionInfo(Long userId, String accountId) throws com.rkhd.platform.sdk.exception.ApiEntityServiceException {
        JSONObject out = new JSONObject();

        // 4.0 确定返回的账户范围：
        //     传了 accountId → 按该账户所属 CHAIN 查出全部相关客户（含自身）；
        //                      未关联 CHAIN（数据未维护）→ 退化为「仅返回当前账户」，
        //                      保证至少能看到当前这个客户的数据，而不是空列表
        //     未传 accountId → 用户所有有 userRole 的账户（原逻辑）
        Set<String> targetAccountIds = null;   // null = 不限制范围
        String chainId = null;
        if (accountId != null) {
            out.put("chainId", "");
            chainId = queryChainIdByAccount(accountId);
            if (chainId == null) {
                logger.warn(TAG + " 当前账户未关联 CHAIN 客户，退化为仅返回该账户 accountId=" + accountId);
                targetAccountIds = new LinkedHashSet<>();
                targetAccountIds.add(accountId);
            } else {
                out.put("chainId", chainId);
                targetAccountIds = queryAccountIdsByChainId(chainId, accountId);
                if (targetAccountIds.isEmpty()) {
                    logger.warn(TAG + " 未查到同 CHAIN 相关客户，退化为仅返回该账户 accountId="
                            + accountId + " chainId=" + chainId);
                    targetAccountIds.add(accountId);
                }
            }
        }

        // 4.1 取该用户的 userRole（同一账户可能多条 userRole 记录），按账户聚合 generalRole id（去重）
        Map<String, Set<String>> accountRolesMap = queryUserRolesByAccount(userId, targetAccountIds);

        // 4.2 取该用户审批中的角色申请 roleRequest__c(status=2) → 按账户聚合 roleAfter__c
        //     注意：此处【不按账户预设范围】。用户在某账户只有「新角色申请」而无任何 userRole 时，
        //     该账户不在 accountRolesMap 里，若再按账户收窄就会漏查；故先全量查，再按返回范围裁剪。
        Map<String, Set<String>> pendingRolesByAccount = queryPendingRoleAfter(userId, null);

        // 4.3 最终返回的账户集合
        Set<String> finalAccountIds;
        if (targetAccountIds != null) {
            // 相关客户模式：账户范围固定为链上相关客户，审批中的申请账户不超出该范围
            pendingRolesByAccount.keySet().retainAll(targetAccountIds);
            finalAccountIds = targetAccountIds;
        } else {
            // 未传 accountId：原有有角色的账户 + 仅有审批申请的账户
            finalAccountIds = new LinkedHashSet<>(accountRolesMap.keySet());
            finalAccountIds.addAll(pendingRolesByAccount.keySet());
        }
        if (finalAccountIds.isEmpty()) {
            // ⚠️ 这里原来完全没有日志：用户"看不到客户"时后端毫无痕迹，是最难排查的场景
            logger.info(TAG + " 用户无任何角色/客户，返回空账户列表"
                    + " userId=" + userId + " accountId=" + accountId + " chainId=" + chainId
                    + "（已授权角色账户 " + accountRolesMap.size()
                    + " 个, 审批中申请账户 " + pendingRolesByAccount.size() + " 个）");
            out.put("totalCount", 0);
            out.put("accounts", new JSONArray());
            return out;
        }

        // 4.4 批量取所有 generalRole（一次性，小数据量），用于名称+角色自带描述+角色类型
        Map<String, JSONObject> generalRoleMap = queryAllGeneralRoles();

        // 4.5 协调员标记位放在每个账户项里（4.9 组装时逐账户计算），
        //     直接用 4.1 已查出的 userRole（已拥有角色）+ 4.4 的 generalRole，无额外 SQL
        // 4.6 批量取账户名称
        Map<String, AccountInfo> accountInfoMap = queryAccountInfos(finalAccountIds);
        // 4.7 批量取这些账户的实际 accountRole 配置（含 roleDescription__c 角色描述）
        Map<String, Map<String, String>> accountRoleDescMap = queryAccountRoleDescMap(finalAccountIds);

        // 4.8 按账户组装
        JSONArray accounts = buildAccountItems(finalAccountIds, accountRolesMap, pendingRolesByAccount,
                generalRoleMap, accountInfoMap, accountRoleDescMap);

        out.put("totalCount", accounts.size());
        out.put("accounts", accounts);
        return out;
    }

    /**
     * 按账户组装 accounts 列表（纯内存计算，不发 SQL）。
     *
     * <p>从 {@code doBuildPermissionInfo} 拆出，保持该方法在 100 行以内。</p>
     *
     * @param finalAccountIds      最终返回的账户集合
     * @param accountRolesMap      账户 → 已拥有角色 id 集合
     * @param pendingRolesByAccount 账户 → 审批中目标角色 id 集合
     * @param generalRoleMap       角色 id → 角色记录（含 name/roleDescription__c/roleType__c/customItem2__c）
     * @param accountInfoMap       账户 id → 账户信息（名称/mySE管理员）
     * @param accountRoleDescMap   账户 → (角色 → 角色描述文本)
     * @return accounts 数组
     */
    private JSONArray buildAccountItems(Set<String> finalAccountIds,
                                        Map<String, Set<String>> accountRolesMap,
                                        Map<String, Set<String>> pendingRolesByAccount,
                                        Map<String, JSONObject> generalRoleMap,
                                        Map<String, AccountInfo> accountInfoMap,
                                        Map<String, Map<String, String>> accountRoleDescMap) {
        JSONArray accounts = new JSONArray();
        // 按账户名排序（名称取不到时退化为 id）
        List<String> sortedAccIds = new ArrayList<>(finalAccountIds);
        sortedAccIds.sort((a, b) -> {
            AccountInfo ai = accountInfoMap.get(a);
            AccountInfo bi = accountInfoMap.get(b);
            String an = (ai != null && !ai.name.isEmpty()) ? ai.name : a;
            String bn = (bi != null && !bi.name.isEmpty()) ? bi.name : b;
            return an.compareTo(bn);
        });

        // 异常计数（循环结束后集中告警一次，避免逐条打日志刷屏）
        int danglingRoleIds = 0;
        int missingAccountInfo = 0;
        int noRoleAccounts = 0;
        int fallbackPoolCount = 0;

        for (String accId : sortedAccIds) {
            // 同链相关客户中用户可能没有 userRole → 已拥有角色为空集合
            Set<String> ownedIds = accountRolesMap.get(accId);
            if (ownedIds == null) {
                ownedIds = Collections.<String>emptySet();
            }
            // 审批中的目标角色（roleAfter）：其中用户尚未拥有的，就是「审批中的新角色」
            Set<String> pendingSet = pendingRolesByAccount.getOrDefault(accId, Collections.<String>emptySet());
            if (ownedIds.isEmpty() && pendingSet.isEmpty()) {
                noRoleAccounts++;
            }

            // 展示的角色集合 = 已拥有 ∪ 审批中（否则只在申请里、还没写 userRole 的角色看不到）
            Set<String> roleIds = new LinkedHashSet<>(ownedIds);
            roleIds.addAll(pendingSet);

            AccountInfo info = accountInfoMap.get(accId);
            if (info == null) {
                // 账户记录查不到（权限受限/脏数据）→ 名称退化成 id、coordinator 空，此处计数便于发现
                missingAccountInfo++;
            }
            String accountName = (info != null && !info.name.isEmpty()) ? info.name : accId;
            String coordinator = (info != null) ? info.coordinator : "";

            JSONObject accItem = new JSONObject();
            accItem.put("accountId", accId);
            accItem.put("accountName", accountName);
            accItem.put("displayName", accountName);     // 与 accountName 同值，前端展示
            accItem.put("coordinator", coordinator);     // 取 account.myseAdmin__c（mySE管理员，文本姓名）
            // 该用户在本账户下是否为协调员（已拥有角色里含「协调员角色」类型）
            accItem.put("isCoordinator", isCoordinatorInAccount(accId, accountRolesMap, generalRoleMap));

            JSONArray roleList = new JSONArray();
            // 按 generalRole id 排序
            List<String> sortedRoleIds = new ArrayList<>(roleIds);
            Collections.sort(sortedRoleIds);

            int idx = 0;
            for (String roleId : sortedRoleIds) {
                idx++;
                JSONObject gr = generalRoleMap.get(roleId);
                String roleName = (gr != null) ? str(gr.get("name")) : "";
                if (gr == null) {
                    // 角色 id 悬空（generalRole__c 里查不到）→ 角色名空，是真正的数据异常
                    danglingRoleIds++;
                }

                // 状态：精确差集口径——审批中的目标角色里「用户目前还没有」的才算申请中；
                //      目标集合里已拥有的角色保持「适用中」（本次申请并未改变它）
                boolean isPending = pendingSet.contains(roleId) && !ownedIds.contains(roleId);
                String status = isPending ? "申请中" : "适用中";
                int statusCode = isPending ? REQUEST_STATUS_PENDING : 1;

                // 权限描述：直接取 accountRole__c.roleDescription__c（无记录则兜底 generalRole__c.roleDescription__c）
                String desc = buildPermissionDescription(accId, roleId, accountRoleDescMap, generalRoleMap);
                if (!desc.isEmpty() && isAccountRoleUnconfigured(accId, roleId, accountRoleDescMap)) {
                    // 该账户未配置此角色的描述，权限描述来自角色自带描述（聚合统计，不逐条打）
                    fallbackPoolCount++;
                }

                JSONObject roleItem = new JSONObject();
                roleItem.put("index", idx);
                roleItem.put("roleId", roleId);
                roleItem.put("roleName", roleName);
                roleItem.put("status", status);
                roleItem.put("statusCode", statusCode);
                roleItem.put("permissionDescription", desc);
                roleList.add(roleItem);
            }
            accItem.put("roleList", roleList);
            accounts.add(accItem);
        }

        // 汇总异常计数（集中告警一次）
        if (danglingRoleIds > 0) {
            logger.warn(TAG + " 有 " + danglingRoleIds + " 个角色 id 在 generalRole__c 中查不到（角色名为空）");
        }
        if (missingAccountInfo > 0) {
            logger.warn(TAG + " 有 " + missingAccountInfo + " 个账户在 account 表中查不到（名称退化为 id）");
        }
        if (noRoleAccounts > 0) {
            logger.info(TAG + " 有 " + noRoleAccounts + " 个账户下该用户既无已授权角色也无审批中申请（roleList 为空）");
        }
        if (fallbackPoolCount > 0) {
            logger.info(TAG + " 有 " + fallbackPoolCount
                    + " 处「该账户未配置此角色描述」，权限描述取自角色自带描述（generalRole__c.roleDescription__c）");
        }
        return accounts;
    }

    /**
     * 取该用户的 userRole，按账户聚合 generalRole id（去重）。
     *
     * @param accountIds 限定账户范围（null/空 = 不限，查该用户全部 userRole）
     * @return key=accountId, value=LinkedHashSet&lt;generalRoleId&gt;
     */
    private Map<String, Set<String>> queryUserRolesByAccount(Long userId, Set<String> accountIds) {
        Map<String, Set<String>> accountRolesMap = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT id, purchaseId__c, role__c FROM userRole__c WHERE user__c = ").append(userId);
        if (accountIds != null && !accountIds.isEmpty()) {
            sql.append(" AND purchaseId__c IN (").append(joinQuotedIds(accountIds)).append(")");
        }
        sql.append(" LIMIT 0, " + MAX_ROWS);
        try {
            QueryResult<JSONObject> urRt = queryWithLog("userRole__c(用户已授权角色)", sql.toString());
            if (urRt != null && urRt.getRecords() != null) {
                int skipped = 0;
                for (Object o : urRt.getRecords()) {
                    JSONObject r = (JSONObject) o;
                    String accId = str(r.get("purchaseId__c"));
                    if (accId.isEmpty()) {
                        skipped++;
                        continue;
                    }
                    Set<String> roleIds = accountRolesMap.computeIfAbsent(accId, k -> new LinkedHashSet<>());
                    // role__c 多值字段（可能是数组或字符串）
                    parseIds(r.get("role__c"), roleIds);
                }
                if (skipped > 0) {
                    logger.warn(TAG + " userRole__c 有 " + skipped
                            + " 条缺 purchaseId__c（无法归属账户），已跳过, userId=" + userId);
                }
            }
        } catch (Exception e) {
            logger.warn("queryUserRolesByAccount 失败 userId=" + userId, e);
        }
        return accountRolesMap;
    }

    /**
     * 查指定账户所属的 CHAINID：accountId → {@code customer__c} 中间表 → {@code chainId__c}。
     *
     * <p><b>2026-10-16 口径变更</b>：不再读 {@code account.chainID__c}（实测该字段现已全部为空），
     * 改走 CHAIN 客户中间表 {@code customer__c}：
     * <pre>
     *   customer__c.customer__c = accountId   （客户 → 账户）
     *   customer__c.chainId__c  = CHAINID__c  （detail 字段，指向 CHAINID__c 记录）
     * </pre>
     * 一个账户理论上只属于一个 CHAIN；若命中多条，取第一条并 warn（数据异常时保持确定性）。</p>
     *
     * @return CHAINID（customer__c.chainId__c）；未关联 CHAIN / chainId__c 为空 / 查询异常 均返回 null
     */
    private String queryChainIdByAccount(String accountId) {
        if (accountId == null || accountId.trim().isEmpty()) {
            return null;
        }
        try {
            QueryResult<JSONObject> rt = queryWithLog("customer__c(账户→CHAIN)",
                    "SELECT id, chainId__c FROM customer__c WHERE customer__c = '"
                            + escapeSql(accountId.trim()) + "' LIMIT 0, 10");
            if (rt == null || rt.getRecords() == null || rt.getRecords().isEmpty()) {
                logger.warn("queryChainIdByAccount: 账户未在 customer__c 中关联 CHAIN 客户 accountId=" + accountId);
                return null;
            }
            String chainId = str(rt.getRecords().get(0).get("chainId__c")).trim();
            if (rt.getRecords().size() > 1) {
                logger.warn("queryChainIdByAccount: 账户在 customer__c 中命中 " + rt.getRecords().size()
                        + " 条 CHAIN 记录（一个账户应只属于一个 CHAIN），取第一条 chainId=" + chainId
                        + " accountId=" + accountId);
            }
            return chainId.isEmpty() ? null : chainId;
        } catch (Exception e) {
            logger.warn("queryChainIdByAccount 失败 accountId=" + accountId, e);
        }
        return null;
    }

    /**
     * 按 CHAINID 查出全部相关客户（账户）id。
     * <p>走 CHAIN 客户中间表 {@code customer__c}：{@code chainId__c = ?} → {@code customer__c}（账户 id）。
     * 兜底：结果里一定包含当前账户自身（避免它在 customer__c 中缺记录、或权限过滤导致自身缺失）。</p>
     */
    private Set<String> queryAccountIdsByChainId(String chainId, String currentAccountId) {
        Set<String> ids = new LinkedHashSet<>();
        if (chainId == null || chainId.trim().isEmpty()) {
            return ids;
        }
        try {
            QueryResult<JSONObject> rt = queryWithLog("customer__c(同CHAIN客户)",
                    "SELECT id, customer__c FROM customer__c WHERE chainId__c = '"
                            + escapeSql(chainId.trim()) + "' LIMIT 0, " + MAX_CHAIN_ACCOUNTS);
            int rows = 0;
            if (rt != null && rt.getRecords() != null) {
                rows = rt.getRecords().size();
                for (Object o : rt.getRecords()) {
                    JSONObject r = (JSONObject) o;
                    String aid = str(r.get("customer__c")).trim();
                    if (!aid.isEmpty()) {
                        ids.add(aid);
                    } else {
                        logger.warn(TAG + " customer__c 记录缺 customer__c（客户账户），已跳过 id="
                                + r.get("id"));
                    }
                }
            }
            // 同 CHAIN 客户数达上限 → 可能有截断，必须告警（原来静默截断，数据不完整却无痕迹）
            if (rows >= MAX_CHAIN_ACCOUNTS) {
                logger.warn(TAG + " 同 CHAIN 客户已达上限 " + MAX_CHAIN_ACCOUNTS
                        + " 条，可能存在截断, chainId=" + chainId);
            }
            logger.info(TAG + " chainId=" + chainId + " → customer__c 命中 " + rows
                    + " 行, 去重后账户 " + ids.size() + " 个（含当前账户）");
        } catch (Exception e) {
            logger.warn("queryAccountIdsByChainId 失败 chainId=" + chainId, e);
        }
        if (currentAccountId != null && !currentAccountId.isEmpty()) {
            ids.add(currentAccountId);
        }
        return ids;
    }

    /** 把 id 集合拼成 SQL IN 用的 'a','b' 形式 */
    private String joinQuotedIds(Set<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append("'").append(escapeSql(id)).append("'");
        }
        return sb.toString();
    }

    /** 全量拉 generalRole（小表），key=id */
    private Map<String, JSONObject> queryAllGeneralRoles() {
        Map<String, JSONObject> map = new HashMap<>();
        try {
            QueryResult<JSONObject> rt = queryWithLog("generalRole__c(角色)",
                    "SELECT id, name, roleDescription__c, customItem2__c, roleType__c"
                            + " FROM generalRole__c LIMIT 0, " + MAX_ROWS);
            if (rt != null && rt.getRecords() != null) {
                for (Object o : rt.getRecords()) {
                    JSONObject r = (JSONObject) o;
                    map.put(r.getString("id"), r);
                }
            }
        } catch (Exception e) {
            logger.warn("queryAllGeneralRoles 失败", e);
        }
        return map;
    }

    /**
     * 批量取账户信息：名称 + 协调员（account.myseAdmin__c「mySE管理员」，text 类型，存姓名文本）
     */
    private Map<String, AccountInfo> queryAccountInfos(Set<String> accountIds) {
        Map<String, AccountInfo> map = new HashMap<>();
        if (accountIds.isEmpty()) {
            return map;
        }
        try {
            String inSb = joinQuotedIds(accountIds);
            QueryResult<JSONObject> rt = queryWithLog("account(客户名称)",
                    "SELECT id, accountName, myseAdmin__c FROM account WHERE id IN (" + inSb + ") LIMIT 0, 5000");
            if (rt != null && rt.getRecords() != null) {
                for (Object o : rt.getRecords()) {
                    JSONObject r = (JSONObject) o;
                    AccountInfo info = new AccountInfo();
                    info.name = str(r.get("accountName"));
                    info.coordinator = str(r.get("myseAdmin__c"));
                    map.put(r.getString("id"), info);
                }
            }
        } catch (Exception e) {
            logger.warn("queryAccountInfos 失败", e);
        }
        return map;
    }

    /** 账户信息（名称 + 协调员），仅用于 queryAccountInfos 返回 */
    private static class AccountInfo {
        String name = "";
        String coordinator = "";
    }

    /**
     * 批量取这些账户的 accountRole 配置
     * 返回：Map&lt;accountId, Map&lt;basicRoleId, 角色描述文本&gt;&gt;
     *
     * <p><b>语义</b>：内层 Map 里 <b>没有</b>某个 basicRoleId 的 key → 该账户从未配置过此角色
     * → 调用方兜底 generalRole__c.roleDescription__c；
     * key 存在但值为空串 → 该账户配置过但描述为空 → 如实返回空，不再兜底。</p>
     */
    private Map<String, Map<String, String>> queryAccountRoleDescMap(Set<String> accountIds) {
        Map<String, Map<String, String>> out = new HashMap<>();
        if (accountIds == null || accountIds.isEmpty()) {
            return out;
        }
        try {
            String inSb = joinQuotedIds(accountIds);
            QueryResult<JSONObject> rt = queryWithLog("accountRole__c(账户角色描述)",
                    "SELECT id, purchaseId__c, basicRole__c, roleDescription__c FROM accountRole__c"
                            + " WHERE purchaseId__c IN (" + inSb + ") LIMIT 0, " + MAX_ROWS);
            if (rt == null || rt.getRecords() == null) {
                return out;
            }
            int emptyDescCount = 0;
            for (Object o : rt.getRecords()) {
                JSONObject r = (JSONObject) o;
                String accId = str(r.get("purchaseId__c"));
                String basicRoleId = str(r.get("basicRole__c"));
                if (accId.isEmpty() || basicRoleId.isEmpty()) {
                    logger.warn(TAG + " accountRole__c 记录缺 purchaseId__c 或 basicRole__c，已跳过: id=" + r.get("id"));
                    continue;
                }
                // 角色描述：直接取文本字段，不再做权限点 id → 名称的翻译拼接
                String desc = str(r.get("roleDescription__c")).trim();
                if (desc.isEmpty()) {
                    emptyDescCount++;
                }
                out.computeIfAbsent(accId, k -> new HashMap<>()).put(basicRoleId, desc);
            }
            if (emptyDescCount > 0) {
                logger.info(TAG + " accountRole__c 命中 " + rt.getRecords().size()
                        + " 条，其中角色描述为空的 " + emptyDescCount + " 条");
            }
        } catch (Exception e) {
            logger.warn("queryAccountRoleDescMap 失败", e);
        }
        return out;
    }

    /**
     * 批量取该用户在这些账户下"审批中"（status__c=2）的 roleAfter 角色集合
     * 返回：Map&lt;accountId, Set&lt;generalRoleId&gt;&gt;
     *
     * @param accountIds 限定账户范围；<b>null = 不限</b>（调用方拿到后自行按返回范围裁剪）
     *                   —— 不能默认按 userRole 推导的账户集合收窄，否则「只有申请、没有 userRole」的场景会漏查
     */
    private Map<String, Set<String>> queryPendingRoleAfter(Long userId, Set<String> accountIds) {
        Map<String, Set<String>> out = new HashMap<>();
        if (userId == null) {
            return out;
        }
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT id, account__c, roleAfter__c FROM roleRequest__c")
                    .append(" WHERE applyUser__c = ").append(userId)
                    .append(" AND status__c = ").append(REQUEST_STATUS_PENDING);
            if (accountIds != null && !accountIds.isEmpty()) {
                sql.append(" AND account__c IN (").append(joinQuotedIds(accountIds)).append(")");
            }
            sql.append(" LIMIT 0, 5000");
            logger.info(TAG + " SQL: " + sql);
            QueryResult<JSONObject> rt = queryWithLog("roleRequest__c(审批中申请)", sql.toString());
            if (rt != null && rt.getRecords() != null) {
                for (Object o : rt.getRecords()) {
                    JSONObject r = (JSONObject) o;
                    String accId = str(r.get("account__c"));
                    if (accId.isEmpty()) {
                        continue;
                    }
                    Set<String> roleIds = out.computeIfAbsent(accId, k -> new HashSet<>());
                    parseIds(r.get("roleAfter__c"), roleIds);
                }
            }
            logger.info(TAG + " 审批中角色申请 命中账户数=" + out.size());
        } catch (Exception e) {
            logger.warn("queryPendingRoleAfter 失败 userId=" + userId, e);
        }
        return out;
    }

    /**
     * 判断当前用户在<b>指定账户</b>下是否为协调员（逐账户独立判定）。
     * <p>
     * 口径：userRole__c（user__c=userId 且 purchaseId__c=该账户，<b>只认已拥有的角色</b>）
     * 里的任一 generalRole，其 roleType__c 含「协调员」/ coordinator 即为协调员。
     * 与 coordinator 包 PermissionRoleOptionsApi 的分组口径一致。
     * </p>
     *
     * @param accountId 账户 id（accounts 里逐项传入）；null/空 → false
     */
    private boolean isCoordinatorInAccount(String accountId,
                                           Map<String, Set<String>> accountRolesMap,
                                           Map<String, JSONObject> generalRoleMap) {
        if (accountId == null || accountId.isEmpty()) {
            return false;
        }
        Set<String> ownedIds = accountRolesMap.get(accountId);
        if (ownedIds == null || ownedIds.isEmpty()) {
            return false;   // 该账户下没有任何 userRole → 不是协调员
        }
        for (String roleId : ownedIds) {
            JSONObject gr = generalRoleMap.get(roleId);
            if (gr != null && isCoordinatorRoleType(gr.get("roleType__c"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 角色类型是否为协调员：roleType__c 含「协调员」或为 coordinator（忽略大小写）。
     * 兼容 XOQL 返回形态：标量 / JSON 数组字符串 ["协调员角色"] / List toString [协调员角色] / 多值
     */
    private boolean isCoordinatorRoleType(Object raw) {
        String rawStr = str(raw);
        if (rawStr.isEmpty()) {
            return false;
        }
        String first = parseFirstArrayItem(rawStr);
        return first.contains("协调员") || rawStr.contains("协调员")
                || "coordinator".equalsIgnoreCase(first.trim());
    }

    /**
     * 形如 ["基础角色"] / [基础角色] 的字符串取首项；非数组原样返回。
     * （与 coordinator 包 UserPermissionBaseApi.parseFirstArrayItem 同逻辑，跨包不可见故内联）
     */
    private String parseFirstArrayItem(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        try {
            JSONArray arr = JSON.parseArray(s);
            if (arr != null && !arr.isEmpty()) {
                Object first = arr.get(0);
                return first != null ? first.toString() : "";
            }
        } catch (Exception ignored) {
            // 不是合法 JSON 数组，原样返回
        }
        return s;
    }

    /**
     * 解析多值关联字段（role__c / roleAfter__c），兼容：id 数组 / {id,name} 对象数组 / 单个标量
     */
    /**
     * 解析多值关联字段（role__c / roleAfter__c），兼容：
     * <ul>
     *   <li>{@link JSONArray} / {@link List}：逐项递归；</li>
     *   <li>{@link JSONObject}：取其 id；</li>
     *   <li>JSON 数组字符串 {@code "[1,2]"}：解析后逐项；</li>
     *   <li><b>逗号分隔串 {@code "1,2"}：按逗号拆分</b>（XOQL 对多值字段有返回逗号串的形态，
     *       不拆的话整串会被当成一个 id，导致角色名/权限描述查不到）；</li>
     *   <li>单个标量：原样加入。</li>
     * </ul>
     */
    private void parseIds(Object raw, Set<String> out) {
        if (raw == null) {
            return;
        }
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            for (int i = 0; i < arr.size(); i++) {
                parseIds(arr.get(i), out);
            }
        } else if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                parseIds(o, out);
            }
        } else if (raw instanceof JSONObject) {
            String id = str(((JSONObject) raw).get("id"));
            if (!id.isEmpty()) {
                out.add(id);
            }
        } else {
            String s = str(raw).trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
                return;
            }
            // JSON 数组字符串
            if (s.startsWith("[")) {
                try {
                    JSONArray arr = JSON.parseArray(s);
                    if (arr != null && !arr.isEmpty()) {
                        parseIds(arr, out);
                        return;
                    }
                } catch (Exception ignored) {
                    // 不是 JSON 数组，按逗号串处理
                }
            }
            // 逗号分隔串：拆开逐个加入
            if (s.indexOf(',') >= 0) {
                for (String part : s.split(",")) {
                    String one = part.trim();
                    if (!one.isEmpty()) {
                        out.add(one);
                    }
                }
                return;
            }
            out.add(s);
        }
    }

    /**
     * 组装单个 (账户, 角色) 的权限描述。
     *
     * <p><b>取值优先级</b>（2026-10-29 老板要求：直接用 roleDescription__c 显示，不再做权限点翻译拼接）：</p>
     * <ol>
     *   <li>该账户下有这条 accountRole 配置 → 直接用 {@code accountRole__c.roleDescription__c}
     *       （为空就如实返回空串，不做兜底）；</li>
     *   <li>该账户<b>没有</b>这条 accountRole 配置（key 不存在）→ 兜底
     *       {@code generalRole__c.roleDescription__c}（角色自带描述），并由调用方聚合统计兜底次数。</li>
     * </ol>
     */
    private String buildPermissionDescription(String accountId, String roleId,
                                              Map<String, Map<String, String>> accountRoleDescMap,
                                              Map<String, JSONObject> generalRoleMap) {
        Map<String, String> perAccount = accountRoleDescMap == null ? null : accountRoleDescMap.get(accountId);
        if (perAccount != null) {
            String desc = perAccount.get(roleId);
            if (desc != null) {
                // key 存在：已配置过。描述为空 = 该账户就没配描述，如实返回空，不再兜底角色默认
                return desc;
            }
        }
        // key 不存在：该账户从未配置过此角色 → 兜底角色自带描述
        String fallback = buildRoleDefaultDescription(roleId, generalRoleMap);
        // 注：不在这里逐条打日志——本方法在「账户 × 角色」双层循环内调用，
        //     逐条打会一次请求刷出几十上百条。由调用方在循环外聚合统计。
        return fallback;
    }

    /**
     * 判断该账户是否<b>从未配置过</b>此角色（key 不存在 → 走角色默认描述兜底）。
     * <p>注意与「已配置但描述为空」区分：后者 key 存在但值为空串，<b>不算兜底</b>。</p>
     */
    private boolean isAccountRoleUnconfigured(String accountId, String roleId,
                                              Map<String, Map<String, String>> accountRoleDescMap) {
        Map<String, String> perAccount = accountRoleDescMap == null ? null : accountRoleDescMap.get(accountId);
        return perAccount == null || !perAccount.containsKey(roleId);
    }

    /**
     * 角色自带描述：generalRole__c.roleDescription__c（文本，直接显示，不做拼接）。
     */
    private String buildRoleDefaultDescription(String roleId, Map<String, JSONObject> generalRoleMap) {
        JSONObject gr = generalRoleMap == null ? null : generalRoleMap.get(roleId);
        if (gr == null) {
            return "";
        }
        return str(gr.get("roleDescription__c")).trim();
    }

    /**
     * 把 User.notificationMethod__c（multipicklist Integer[]）拼成「站内信+电子邮件」格式
     * 兼容 JSONArray / List<Integer> / Integer[] / 字符串「1,2」/ null（默认「站内信」）
     */
    private String buildReceiveMethod(Object raw) {
        Set<String> labels = new LinkedHashSet<>();
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            for (int i = 0; i < arr.size(); i++) {
                addLabel(labels, arr.get(i));
            }
        } else if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                addLabel(labels, o);
            }
        } else if (raw instanceof Integer[]) {
            for (Integer v : (Integer[]) raw) {
                addLabel(labels, v);
            }
        } else if (raw != null) {
            String s = String.valueOf(raw).trim();
            if (!s.isEmpty()) {
                for (String t : s.split(",")) {
                    addLabel(labels, t.trim());
                }
            }
        }
        if (labels.isEmpty()) {
            // 字段无值（未配置）→ 默认仅「站内信」
            labels.add(METHOD_LABEL_MAP.get(1));
        }
        StringBuilder out = new StringBuilder();
        for (String l : labels) {
            if (out.length() > 0) {
                out.append("+");
            }
            out.append(l);
        }
        return out.toString();
    }

    private void addLabel(Set<String> labels, Object raw) {
        if (raw == null) {
            return;
        }
        Integer code = null;
        if (raw instanceof Number) {
            code = ((Number) raw).intValue();
        } else {
            try {
                code = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                // 通知方式码值非法 → 数据异常，静默丢弃会让用户的通知方式被悄悄改掉，这里告警
                logger.warn(TAG + " notificationMethod__c 含非法码值，已跳过: " + raw);
                return;
            }
        }
        if (code != null) {
            String label = METHOD_LABEL_MAP.get(code);
            if (label != null) {
                labels.add(label);
            } else {
                logger.warn(TAG + " notificationMethod__c 含未知码值 " + code + "，已跳过");
            }
        }
    }

    /**
     * 带日志的 XOQL 查询：执行前打 SQL、执行后打条数、异常打 error 并返回 null。
     * <p>统一走这个方法，避免各处查询日志风格不一（改前只有 1 处打 SQL，其余 7 处无痕）。</p>
     *
     * @param desc 查询描述（对象名+用途），如 "customer__c(账户→CHAIN)"
     * @param sql  完整 XOQL
     */
    private QueryResult<JSONObject> queryWithLog(String desc, String sql) {
        logger.info(TAG + " 查询 " + desc + " SQL: " + sql);
        try {
            QueryResult<JSONObject> rt = XoqlService.instance().query(sql);
            int n = (rt == null || rt.getRecords() == null) ? 0 : rt.getRecords().size();
            logger.info(TAG + " 查询 " + desc + " 返回 " + n + " 条");
            return rt;
        } catch (Exception e) {
            logger.error(TAG + " 查询 " + desc + " 失败: " + e.getMessage(), e);
            return null;
        }
    }

    private String str(Object v) {
        return v == null ? "" : v.toString();
    }

    /** 入参归一：null / 空串 / 纯空白 → null；其余转去空白字符串（兼容前端传数字）。 */
    private String trimToNull(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private String escapeSql(String s) {
        return s == null ? "" : s.replace("'", "''");
    }
}