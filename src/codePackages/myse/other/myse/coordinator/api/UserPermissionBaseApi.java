package other.myse.coordinator.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.context.ScriptRuntimeContext;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.service.XoqlService;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.CacheService;
import com.rkhd.platform.sdk.service.CustomConfigService;
import other.myse.coordinator.utils.HttpUtil;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 权限管理接口共享基类（other.myse.coordinator）
 *
 * <p>提取 列表 / 详情 / 可编辑角色 / 更新 四个接口共用的关联查询与工具方法。</p>
 * <p>本身不注册为 REST 接口，由 {@code UserPermission*Api} 各子类继承，子类各自用
 * {@code @RestApi} + {@code @RestMapping} 暴露一个独立接口。</p>
 *
 * <p><b>日志规范（全 coordinator 包统一，子类照此执行）：</b>
 * <ol>
 *   <li>入参一条：入口处 {@code logIn(TAG, body)}，打原始 body，不打解析后的中间对象；</li>
 *   <li>出参一条：出口处 {@code logOut(TAG, resp)}（列表类用 {@code logOutList} 只打条数）；</li>
 *   <li>每次 XOQL：统一走 {@code queryWithLog(TAG, desc, sql)}，执行前打 SQL、执行后打条数；</li>
 *   <li>异常：统一 {@code logger.error(TAG + " xxx失败: " + e.getMessage(), e)}，不打 printStackTrace；</li>
 *   <li>不打：中间过程对象（entity.toString() / rt.getRecords()）、不打全量记录。</li>
 * </ol>
 * </p>
 */
public abstract class UserPermissionBaseApi {

    protected static final Logger logger = LoggerFactory.getLogger();

    /** 基类内部查询的日志标识（子类应传自己的 TAG） */
    protected static final String TAG_BASE = "CoordinatorBase";

    // ===================== 协调员权限守卫 =====================

    /** 角色类型中代表「协调员」的关键词（generalRole__c.roleType__c 解析首值后按此匹配） */
    protected static final String ROLE_TYPE_COORDINATOR = "协调员";

    /** 角色类型的英文兜底（部分租户 roleType__c 用英文） */
    protected static final String ROLE_TYPE_COORDINATOR_EN = "coordinator";

    /**
     * 权限已失效的错误码：当前用户的权限发生了变化。
     * <p>前端收到此码应提示「您的权限已发生变化」并引导重新登录/重新申请，
     * 而不是当成业务错误反复提交。</p>
     */
    public static final String CODE_PERMISSION_CHANGED = "PERMISSION_CHANGED";

    /** 统一的权限失效提示语 */
    public static final String MSG_PERMISSION_CHANGED = "您的权限已发生变化，请重新登录或联系管理员申请权限";

    // ===================== 权限变化守卫（租户级缓存令牌） =====================

    /**
     * 权限变更标记的 value（固定字符串，语义是「有变更」）。
     * <p>用固定值而非时间戳：守卫只关心"有没有变更"，不关心"什么时候变的"。</p>
     */
    protected static final String PERM_CHANGED_FLAG = "1";

    /**
     * 权限变化守卫：查租户级缓存里有没有当前用户的「权限变更」标记。
     *
     * <p><b>机制（老板 2026-10-22 定）</b>：</p>
     * <pre>
     *   权限变更时（PermissionSaveApi / RoleDefSaveApi 等写入方）
     *     → 往 CacheService 写一条 key = {userId} 的记录
     *   接口调用时（本方法）
     *     → 用当前登录用户的 id 命中这条记录
     *     → 命中 = 权限确实变了 → 返回 PERMISSION_CHANGED 给前端，并【清空该标记】
     *     → 未命中 = 权限没变 → 放行
     * </pre>
     *
     * <p><b>为什么用 CacheService 而不是内存 Map</b>：CacheService 是<b>租户级</b>的，
     * 写权限的请求和读权限的请求即使落在不同实例上也能互相看到；进程重启也不会丢标记。</p>
     *
     * <p><b>标记是一次性的</b>：命中后立即删除。所以前端收到 PERMISSION_CHANGED 后
     * 重新登录/刷新，后续调用即恢复正常，不会反复报错。</p>
     *
     * <p><b>缓存读失败时放行（fail-open）</b>：缓存只是"变化检测"的加速手段，
     * 不该因为缓存服务抖动就让所有接口不可用；此时打 error 日志，交由 TTL/人工介入兜底。
     * （若改成 fail-closed，缓存服务一挂全站接口全废，风险更大。）</p>
     *
     * @param tag 调用方 TAG（用于日志）
     * @return {@code null} = 权限未变化，可继续执行业务；
     *         非 null = 失败响应 JSON，调用方应直接 return
     */
    protected String checkPermissionChanged(String tag) {
        // 本地调试（IDEA 直接跑 main，无平台上下文）→ 放行，否则本地完全没法调试
        if (isLocalDebug()) {
            logger.info(tag + " 本地调试环境（无平台上下文），跳过权限变化检查");
            return null;
        }
        Long userId = currentUserId(tag);
        if (userId == null) {
            // 平台上有租户但取不到登录用户 → 无法确认权限状态，保守拒绝
            logger.error(tag + " 权限守卫：取不到当前登录用户，拒绝访问");
            return permissionChangedResp(tag);
        }
        String key = permChangedKey(userId);
        try {
            String flag = CacheService.instance().get(key);
            if (flag != null && !flag.trim().isEmpty()) {
                // 命中 = 权限已变化 → 清空标记（一次性令牌）
                try {
                    CacheService.instance().delete(key);
                } catch (Exception de) {
                    logger.error(tag + " 权限守卫：清空变更标记失败, key=" + key, de);
                }
                logger.warn(tag + " 权限守卫：检测到权限已变化，拒绝访问并清空标记, userId=" + userId);
                return permissionChangedResp(tag);
            }
        } catch (Exception e) {
            // fail-open：缓存读失败不阻断业务
            logger.error(tag + " 权限守卫：读缓存失败，本次放行, key=" + key, e);
        }
        return null;
    }

    /**
     * 标记某用户的权限已发生变化（由权限写入方在<b>数据库写入成功之后</b>调用）。
     *
     * @param tag    调用方 TAG
     * @param userId 用户 id
     * @return true = 标记成功；false = 标记失败（已打日志）
     */
    protected boolean markPermissionChanged(String tag, Long userId) {
        if (userId == null || userId <= 0) {
            logger.error(tag + " 标记权限变更失败：userId 非法, userId=" + userId);
            return false;
        }
        String key = permChangedKey(userId);
        try {
            CacheService.instance().set(key, PERM_CHANGED_FLAG);
            logger.info(tag + " 已标记权限变更, userId=" + userId + ", key=" + key);
            return true;
        } catch (Exception e) {
            // 标记失败 = 漏检风险，必须 error
            logger.error(tag + " 标记权限变更失败（该用户可能收不到变更通知）, userId=" + userId, e);
            return false;
        }
    }

    /**
     * 标记某账户下所有用户的权限已发生变化
     * （角色功能权限是账户级配置，改动会影响该账户下所有拥有该角色的用户）。
     *
     * @param tag       调用方 TAG
     * @param accountId 账户 id
     * @return 标记成功的用户数
     */
    protected int markPermissionChangedForAccount(String tag, String accountId) {
        if (accountId == null || accountId.trim().isEmpty()) {
            return 0;
        }
        int n = 0;
        try {
            Set<Long> userIds = queryUserIdsByAccount(accountId.trim());
            for (Long uid : userIds) {
                if (markPermissionChanged(tag, uid)) {
                    n++;
                }
            }
            logger.info(tag + " 已标记账户权限变更, accountId=" + accountId + ", 标记用户数=" + n);
        } catch (Exception e) {
            logger.error(tag + " 标记账户权限变更失败, accountId=" + accountId, e);
        }
        return n;
    }

    /** 查某账户下所有拥有用户角色的用户 id（用于批量打变更标记） */
    protected Set<Long> queryUserIdsByAccount(String accountId) {
        Set<Long> ids = new LinkedHashSet<Long>();
        String sql = "SELECT DISTINCT user__c FROM userRole__c WHERE purchaseId__c = '"
                + escapeSql(accountId) + "'";
        QueryResult<JSONObject> rt = queryWithLog(TAG_BASE, "userRole__c(账户下用户)", sql);
        if (rt != null && rt.getRecords() != null) {
            for (JSONObject o : rt.getRecords()) {
                Long uid = toLong(o.get("user__c"));
                if (uid != null) {
                    ids.add(uid);
                }
            }
        }
        return ids;
    }

    /**
     * 权限变更标记的缓存 key：<b>直接用 userId</b>（老板 2026-10-22 定，不加额外前缀）。
     * <p>⚠️ 副作用：若租户上其他业务也用 {@link CacheService} 且 key 恰好是纯数字 userId，
     * 会互相干扰。当前项目内只有本守卫使用 CacheService，无冲突。</p>
     */
    protected String permChangedKey(Long userId) {
        return String.valueOf(userId);
    }

    /** 把任意值转成 Long（数字 / 数字字符串 / {id:...} 对象），转不了返回 null */
    protected Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof JSONObject) {
            JSONObject j = (JSONObject) v;
            Object inner = j.get("id");
            if (inner == null) {
                inner = j.get("value");
            }
            return inner == null ? null : toLong(inner);
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 检查当前登录用户是否仍具有协调员角色。
     *
     * <p><b>⚠️ 2026-10-22 已下线</b>：接口入口守卫改为「权限变化检测」
     * （见 {@link #checkPermissionChanged(String)} —— 查租户级缓存里的变更标记），
     * 不再每次调用都查库重算协调员身份。本方法<b>已无调用方</b>，保留仅作口径参考。</p>
     *
     * <p>协调员身份的<b>业务用途</b>（非守卫）仍在 {@code AccountAccessListApi}：
     * 剔除当前用户非协调员的账户 —— 那条链路走 {@link #queryCoordinatorRoleIds(String, Set)}。</p>
     *
     * <p><b>判定链路</b>（与 AccountAccessListApi 的过滤口径一致）：</p>
     * <pre>
     *   user__c = 当前用户 → userRole__c（所有账户，只认已授权角色，审批中不算）
     *     → role__c（多值关联）→ generalRole__c.roleType__c
     *     → 解析首值后包含「协调员」或等于 coordinator（忽略大小写）即命中
     * </pre>
     *
     * @param tag 调用方 TAG（用于日志）
     * @return true=仍是协调员；false=已失去协调员身份
     */
    @Deprecated
    protected boolean isCoordinator(String tag) {
        Long userId = currentUserId(tag);
        if (userId == null) {
            return false;
        }
        // ① 当前用户的所有已授权角色
        String sql = "SELECT id, role__c FROM userRole__c WHERE user__c = " + userId;
        QueryResult<JSONObject> rt = queryWithLog(tag, "userRole__c(协调员判定)", sql);
        Set<String> roleIds = new LinkedHashSet<String>();
        if (rt != null && rt.getRecords() != null) {
            for (JSONObject o : rt.getRecords()) {
                parseMultiIds(roleIds, o.get("role__c"));
            }
        }
        if (roleIds.isEmpty()) {
            logger.warn(tag + " 协调员判定失败：当前用户没有任何已授权角色, userId=" + userId);
            return false;
        }
        // ② 这些角色里是否含协调员角色
        Set<String> coordinatorRoleIds = queryCoordinatorRoleIds(tag, roleIds);
        if (coordinatorRoleIds.isEmpty()) {
            logger.warn(tag + " 协调员判定失败：当前用户的 " + roleIds.size()
                    + " 个角色均不是协调员角色, userId=" + userId);
            return false;
        }
        logger.info(tag + " 协调员判定通过: userId=" + userId + ", 协调员角色 " + coordinatorRoleIds);
        return true;
    }

    /** 从角色id 集合中筛出「协调员」角色：roleType__c 首值含「协调员」或等于 coordinator。 */
    protected Set<String> queryCoordinatorRoleIds(String tag, Set<String> roleIds) {
        Set<String> coordinatorRoleIds = new LinkedHashSet<String>();
        if (roleIds == null || roleIds.isEmpty()) {
            return coordinatorRoleIds;
        }
        String sql = "SELECT id, name, roleType__c FROM generalRole__c WHERE id IN ("
                + buildInClause(roleIds) + ")";
        QueryResult<JSONObject> rt = queryWithLog(tag, "generalRole__c(协调员角色判定)", sql);
        if (rt == null || rt.getRecords() == null) {
            return coordinatorRoleIds;
        }
        for (JSONObject o : rt.getRecords()) {
            String roleId = str(o.get("id")).trim();
            if (roleId.isEmpty()) {
                continue;
            }
            String roleType = parseFirstArrayItem(str(o.get("roleType__c"))).trim();
            if (roleType.contains(ROLE_TYPE_COORDINATOR)
                    || ROLE_TYPE_COORDINATOR_EN.equalsIgnoreCase(roleType)) {
                coordinatorRoleIds.add(roleId);
            }
        }
        return coordinatorRoleIds;
    }

    /**
     * 取当前登录用户 id（服务端会话，<b>不信任入参</b>）。
     * @return 取不到时返回 null
     */
    protected Long currentUserId(String tag) {
        Long userId = null;
        try {
            userId = ScriptRuntimeContext.instance().getUserId();
        } catch (Exception e) {
            logger.error(tag + " 取当前登录用户失败: " + e.getMessage(), e);
        }
        if (userId == null) {
            logger.error(tag + " 取不到当前登录用户（服务端会话为空）");
        }
        return userId;
    }

    /**
     * 判断当前是否运行在<b>本地调试环境</b>（IDEA 里直接跑 main）。
     *
     * <p><b>判据</b>（实测）：本地无平台上下文时
     * {@code ScriptRuntimeContext.getTenantId()} 返回 <b>-1</b>、
     * {@code getEnvInfo()} 返回 <b>null</b>；平台上两者都有效。</p>
     *
     * <p><b>为什么要区分</b>：本地跑 main 时没有登录会话，取不到 userId，
     * 权限守卫会直接拒绝 → <b>本地完全没法调试</b>。本地放行不影响平台上的安全性
     * （平台上有 tenantId，仍然走正常校验）。</p>
     */
    protected boolean isLocalDebug() {
        try {
            Long tenantId = ScriptRuntimeContext.instance().getTenantId();
            return tenantId == null || tenantId <= 0;
        } catch (Exception e) {
            // 取不到租户 → 视为本地（保守放行，避免本地调试被阻断）
            return true;
        }
    }

    /**
     * 构造「权限已变化」的失败响应，供各端点统一返回。
     * <p>用法：{@code String guard = checkPermissionChanged(TAG); if (guard != null) { return guard; }}</p>
     *
     * @param tag 调用方 TAG（用于日志）
     */
    protected String permissionChangedResp(String tag) {
        logger.warn(tag + " 拒绝访问：检测到用户权限已发生变化, code=" + CODE_PERMISSION_CHANGED);
        return HttpUtil.fail(CODE_PERMISSION_CHANGED, MSG_PERMISSION_CHANGED).toJSONString();
    }

    // ===================== 上次登录字段容错探测 =====================
    /**
     * 销售易 User 对象的"上次登录时间"字段 apiKey 在不同租户可能不同，
     * 首次调用时自动探测候选字段名并缓存；探测不到则返回 null（该列留空）。
     */
    protected static final String[] LOGIN_FIELD_CANDIDATES = {
            "lastestLoginAt", "latestLoginAt", "lastLoginDate", "lastLoginTime", "loginDate", "loginTime",
            "lastLogonDate", "lastLogonTime", "lastSignInDate", "recentLoginTime",
            "logonDate", "signInDate", "lastActiveTime", "lastActiveDate",
            "lastLogin", "lastLogonTime", "lastVisitDate", "lastVisitTime"
    };
    protected static volatile String cachedLoginField = null;
    protected static volatile boolean loginFieldResolved = false;

    protected String getLoginField() {
        if (!loginFieldResolved) {
            cachedLoginField = resolveLoginField();
            loginFieldResolved = true;
        }
        return cachedLoginField;
    }

    private String resolveLoginField() {
        for (String f : LOGIN_FIELD_CANDIDATES) {
            try {
                // 探测性查询：不逐条打 SQL（候选字段多达十余个，打日志会刷屏），只在最终结论打一条
                QueryResult<JSONObject> rt = XoqlService.instance().query("SELECT id, " + f + " FROM user LIMIT 1");
                if (rt != null && rt.getRecords() != null && !rt.getRecords().isEmpty()) {
                    logger.info(TAG_BASE + " 上次登录时间字段探测命中: " + f);
                    return f;
                }
            } catch (Exception ignored) {
                // 该字段不存在，尝试下一个候选
            }
        }
        logger.warn(TAG_BASE + " 未探测到上次登录时间字段（" + LOGIN_FIELD_CANDIDATES.length
                + " 个候选均不可用），列表 lastLoginDate 将返回空");
        return null;
    }

    // ===================== 关联查询方法 =====================

    /**
     * 按姓名/电话/邮箱模糊匹配 User，返回匹配的用户 id 集合
     * （列表搜索用：XOQL 不支持跨对象子查询，故先查 User 再 IN 过滤）
     */
    protected Set<String> queryMatchedUserIds(String name, String phone, String email) {
        Set<String> ids = new HashSet<>();
        List<String> conds = new ArrayList<>();
        if (name != null && !name.trim().isEmpty()) {
            conds.add("name LIKE '%" + escapeLike(name.trim()) + "%'");
        }
        if (phone != null && !phone.trim().isEmpty()) {
            conds.add("phone LIKE '%" + escapeLike(phone.trim()) + "%'");
        }
        if (email != null && !email.trim().isEmpty()) {
            conds.add("personalEmail LIKE '%" + escapeLike(email.trim()) + "%'");
        }
        if (conds.isEmpty()) {
            return ids;
        }
        String sql = "SELECT id FROM user WHERE " + String.join(" OR ", conds);
        QueryResult<JSONObject> rt = queryWithLog(TAG_BASE, "user(模糊匹配)", sql);
        if (rt != null && rt.getRecords() != null) {
            for (Object o : rt.getRecords()) {
                JSONObject u = (JSONObject) o;
                String id = u.getString("id");
                if (id != null && !id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /**
     * 批量查询用户信息（姓名/电话/邮箱/上次登录时间）
     */
    protected Map<String, JSONObject> queryUsers(Set<String> userIds) {
        Map<String, JSONObject> map = new HashMap<>();
        if (userIds.isEmpty()) {
            return map;
        }
        try {
            String inClause = buildInClause(userIds);
            String loginF = getLoginField();
            String cols = "id, name, phone, personalEmail";
            if (loginF != null) {
                cols += ", " + loginF;
            }
            String sql = "SELECT " + cols + " FROM user WHERE id IN (" + inClause + ")";
            QueryResult<JSONObject> rt = queryWithLog(TAG_BASE, "user", sql);
            if (rt != null && rt.getRecords() != null) {
                for (Object o : rt.getRecords()) {
                    JSONObject u = (JSONObject) o;
                    map.put(u.getString("id"), u);
                }
            }
        } catch (Exception e) {
            logger.error(TAG_BASE + " 解析用户信息失败", e);
        }
        return map;
    }

    /**
     * 批量查询账户名称
     */
    protected Map<String, String> queryAccountNames(Set<String> accountIds) {
        Map<String, String> map = new HashMap<>();
        if (accountIds.isEmpty()) {
            return map;
        }
        try {
            String inClause = buildInClause(accountIds);
            String sql = "SELECT id, accountName FROM account WHERE id IN (" + inClause + ")";
            QueryResult<JSONObject> rt = queryWithLog(TAG_BASE, "account", sql);
            if (rt != null && rt.getRecords() != null) {
                for (Object o : rt.getRecords()) {
                    JSONObject a = (JSONObject) o;
                    map.put(a.getString("id"), str(a.get("accountName")));
                }
            }
        } catch (Exception e) {
            logger.error(TAG_BASE + " 解析账户名称失败", e);
        }
        return map;
    }

    // ===================== 统一日志工具 =====================

    /**
     * 入参日志：在接口入口处调用一次，打印原始请求体（不打解析后的中间对象）。
     */
    protected void logIn(String tag, String body) {
        logger.info(tag + " 入参: " + body);
    }

    /**
     * 出参日志：在接口出口处调用一次，成功失败都打（返回体较小的接口用这个）。
     */
    protected void logOut(String tag, String resp) {
        logger.info(tag + " 出参: " + resp);
    }

    /**
     * 列表类接口出参日志：只打「是否成功 + 返回条数 + 耗时」，不打全量 list（避免日志膨胀）。
     *
     * @param tag     业务标识
     * @param ok      是否成功
     * @param count   返回条数（list.size()）
     * @param startTs 接口开始时间戳（System.currentTimeMillis()）
     */
    protected void logOutList(String tag, boolean ok, int count, long startTs) {
        logger.info(tag + " 出参: success=" + ok + " 返回 " + count + " 条"
                + " 耗时=" + (System.currentTimeMillis() - startTs) + "ms");
    }

    /**
     * 统一 SQL 日志：执行 XOQL 前打印完整 SQL（便于线上复现问题）。
     *
     * @param tag 业务标识，建议用 "类名.方法" 或接口名，如 "RoleDefSave"
     * @param sql 完整 XOQL
     */
    protected void logSql(String tag, String sql) {
        logger.info(tag + " SQL: " + sql);
    }

    /**
     * 统一查询结果日志：只打印条数，不打印全量数据（避免日志膨胀）。
     *
     * @param tag  业务标识
     * @param desc 查询描述，如 "accountRole__c"
     * @param rt   查询返回
     * @return 记录条数
     */
    protected int logQueryResult(String tag, String desc, QueryResult<JSONObject> rt) {
        int n = (rt == null || rt.getRecords() == null) ? 0 : rt.getRecords().size();
        // 只打条数，绝不打全量 records（否则大表会把日志打爆；且 rt 为 null 时会 NPE）
        logger.info(tag + " 查询 " + desc + " 返回 " + n + " 条");
        return n;
    }

    /**
     * 带日志的 XOQL 查询：执行前打 SQL、执行后打条数、失败打 error 并返回 null
     * （与原有各处 catch 后按空结果处理的口径一致）。
     *
     * @param tag  业务标识
     * @param desc 查询描述
     * @param sql  完整 XOQL
     * @return 查询结果；异常时返回 null
     */
    protected QueryResult<JSONObject> queryWithLog(String tag, String desc, String sql) {
        logSql(tag, sql);
        try {
            QueryResult<JSONObject> rt = XoqlService.instance().query(sql);
            logQueryResult(tag, desc, rt);
            return rt;
        } catch (Exception e) {
            logger.error(tag + " 查询 " + desc + " 失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 带日志的 XOQL 查询（基类内部共用方法专用，日志标识固定 {@link #TAG_BASE}）。
     */
    protected QueryResult<JSONObject> queryWithLog(String desc, String sql) {
        return queryWithLog(TAG_BASE, desc, sql);
    }

    /**
     * 批量查询基础角色名称
     */
    protected Map<String, String> queryRoleNames(Set<String> roleIds) {
        Map<String, String> map = new HashMap<>();
        if (roleIds.isEmpty()) {
            return map;
        }
        try {
            String inClause = buildInClause(roleIds);
            String sql = "SELECT id, name FROM generalRole__c WHERE id IN (" + inClause + ")";
            QueryResult<JSONObject> rt = queryWithLog("generalRole__c", sql);
            if (rt != null && rt.getRecords() != null) {
                for (Object o : rt.getRecords()) {
                    JSONObject r = (JSONObject) o;
                    map.put(r.getString("id"), str(r.get("name")));
                }
            }
        } catch (Exception e) {
            logger.error(TAG_BASE + " 解析角色名称失败", e);
        }
        return map;
    }

    /**
     * 查询某账户下某基础角色的实际权限点名称列表
     * 链路: accountRole(purchaseId + basicRole) -> accountRoleDes__c(多值->roleDes.id) -> roleDes.name
     *
     * @param accountId 账户ID (purchaseId__c)
     * @param roleId    基础角色ID (basicRole__c -> generalRole.id)
     * @return 权限点名称列表，如 ["价格货期查询","查看报价/订单"]
     */
    protected List<String> queryAccountRolePermissions(String accountId, String roleId) {
        List<String> result = new ArrayList<>();
        if (accountId == null || accountId.isEmpty() || roleId == null || roleId.isEmpty()) {
            return result;
        }
        try {
            // 1. 查 accountRole 拿 accountRoleDes__c
            String sql = "SELECT id, accountRoleDes__c FROM accountRole__c "
                    + " WHERE purchaseId__c = '" + escapeSql(accountId)
                    + "' AND basicRole__c = '" + escapeSql(roleId) + "' LIMIT 1";
            QueryResult<JSONObject> rt = queryWithLog("accountRole__c", sql);
            if (rt == null || rt.getRecords() == null || rt.getRecords().isEmpty()) {
                return result; // 该账户下未配置此角色的权限，返回空
            }
            JSONObject ar = (JSONObject) rt.getRecords().get(0);
            String desRaw = str(ar.get("accountRoleDes__c"));
            if (desRaw.isEmpty()) {
                return result;
            }

            // 2. 解析 accountRoleDes__c（多值关联 -> roleDes id 集合）
            Set<String> desIds = new HashSet<>();
            parseMultiIds(desIds, desRaw);
            if (desIds.isEmpty()) {
                return result;
            }

            // 3. 批量查 roleDes.name
            Map<String, String> desNameMap = queryRoleDesNames(desIds);
            for (String did : desIds) {
                String name = desNameMap.get(did);
                if (name != null && !name.isEmpty()) {
                    result.add(name);
                }
            }
        } catch (Exception e) {
            logger.error(TAG_BASE + " 查询账户角色权限失败 accountId=" + accountId + " roleId=" + roleId, e);
        }
        return result;
    }

    /**
     * 批量查询功能权限定义(roleDes)的名称
     */
    protected Map<String, String> queryRoleDesNames(Set<String> desIds) {
        Map<String, String> map = new HashMap<>();
        if (desIds.isEmpty()) {
            return map;
        }
        try {
            String inClause = buildInClause(desIds);
            String sql = "SELECT id, name FROM roleDes__c WHERE id IN (" + inClause + ")";
            QueryResult<JSONObject> rt = queryWithLog("roleDes__c", sql);
            if (rt != null && rt.getRecords() != null) {
                for (Object o : rt.getRecords()) {
                    JSONObject r = (JSONObject) o;
                    map.put(r.getString("id"), str(r.get("name")));
                }
            }
        } catch (Exception e) {
            logger.error(TAG_BASE + " 解析功能权限名称失败", e);
        }
        return map;
    }

    /**
     * 根据多值 roleDes ID 列表构建权限描述文本
     * 格式: "价格货期查询，查看报价/订单"
     */
    protected String buildPermissionDescription(Object rawDesIds, Map<String, String> desNameMap) {
        if (rawDesIds == null) {
            return "";
        }
        Set<String> ids = new HashSet<>();
        parseMultiIds(ids, rawDesIds);
        if (ids.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (String did : ids) {
            String name = desNameMap.get(did);
            if (name != null && !name.isEmpty()) {
                names.add(name);
            }
        }
        return String.join("，", names);
    }

    // ===================== 辅助方法 =====================

    /**
     * 将多值角色ID原始值解析为逗号分隔的角色名显示
     * role__c 存储格式可能是: ["id1","id2"] 或 id1,id2
     */
    protected String buildRoleDisplay(String rolesRaw, Map<String, String> roleNameMap) {
        if (rolesRaw == null || rolesRaw.isEmpty()) {
            return "";
        }
        List<String> roleIds = new ArrayList<>();
        // 尝试按 JSON 数组解析
        try {
            JSONArray arr = JSON.parseArray(rolesRaw);
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    roleIds.add(arr.getString(i));
                }
            }
        } catch (Exception ignored) {
            // 不是 JSON 格式，尝试按逗号分隔
            String[] parts = rolesRaw.split(",");
            for (String p : parts) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) {
                    roleIds.add(trimmed);
                }
            }
        }
        // 映射为角色名
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < roleIds.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(roleNameMap.getOrDefault(roleIds.get(i), roleIds.get(i)));
        }
        return sb.toString();
    }

    /** 解析多值关联字段的 ID 到 Set（兼容字符串/数字/Long类型的JSON数组元素） */
    protected void parseMultiIds(Set<String> target, Object rawValue) {
        if (rawValue == null) {
            return;
        }
        String s = rawValue.toString().trim();
        if (s.isEmpty()) {
            return;
        }
        // 尝试 JSON 数组
        try {
            JSONArray arr = JSON.parseArray(s);
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    Object val = arr.get(i);
                    if (val == null) continue;
                    String id;
                    if (val instanceof String) {
                        id = (String) val;
                    } else {
                        id = val.toString();  // 数字(Long)/布尔等统一转字符串
                    }
                    if (!id.isEmpty()) {
                        target.add(id);
                    }
                }
                return;
            }
        } catch (Exception ignored) {
        }
        // 降级：逗号分隔
        String[] parts = s.split(",");
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                target.add(trimmed);
            }
        }
    }

    /** 收集单个 ID 到 Set */
    protected void collectId(Set<String> target, Object value) {
        if (value != null) {
            String s = value.toString().trim();
            if (!s.isEmpty()) {
                target.add(s);
            }
        }
    }

    /** 构建 IN ('a','b') 子句 */
    protected String buildInClause(Set<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append("'").append(escapeSql(id)).append("'");
        }
        return sb.toString();
    }

    /**
     * 构建 ORDER BY 子句（通用排序参数）
     * <p>
     * 入参格式：sorts = [ { "field": "createdAt", "order": "ASC" }, ... ]
     * field 为对象字段 API 名，直接拼入 SQL（由调用方保证合法性）；
     * order 只认 ASC / DESC（忽略大小写），其它值按 ASC 处理；
     * 未传 / 空数组 / field 为空 / 非对象元素 → 忽略该项；全部无效时返回 defaultOrderBy。
     * </p>
     */
    protected String buildOrderBy(JSONArray sorts, String defaultOrderBy) {
        StringBuilder sb = new StringBuilder();
        if (sorts != null) {
            for (int i = 0; i < sorts.size(); i++) {
                Object o = sorts.get(i);
                if (!(o instanceof JSONObject)) {
                    continue;
                }
                JSONObject s = (JSONObject) o;
                String field = s.getString("field");
                if (field == null || field.trim().isEmpty()) {
                    continue;
                }
                String ord = "DESC".equalsIgnoreCase(s.getString("order")) ? "DESC" : "ASC";
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(field.trim()).append(" ").append(ord);
            }
        }
        return sb.length() == 0 ? defaultOrderBy : sb.toString();
    }

    /** 日期字符串 yyyy-MM-dd -> 当天 00:00:00 毫秒时间戳；解析失败返回 0（调用方视为不限制下界） */
    protected long parseDate(String dateStr) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            Date d = sdf.parse(dateStr);
            return d.getTime();
        } catch (Exception e) {
            logger.warn(TAG_BASE + " parseDate 日期解析失败，按 0 处理（视为不限制下界）: " + dateStr, e);
            return 0;
        }
    }

    /** 日期字符串 yyyy-MM-dd -> 当天 23:59:59 毫秒时间戳；解析失败返回 Long.MAX_VALUE（视为不限制上界） */
    protected long parseDateEndOfDay(String dateStr) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            Date d = sdf.parse(dateStr);
            return d.getTime() + 86399999L; // +23h59m59s999ms
        } catch (Exception e) {
            logger.warn(TAG_BASE + " parseDateEndOfDay 日期解析失败，按 Long.MAX_VALUE 处理"
                    + "（视为不限制上界，可能放大查询范围）: " + dateStr, e);
            return Long.MAX_VALUE;
        }
    }

    /** 日期格式化（支持时间戳数字/字符串 和 yyyy-MM-dd） */
    protected String formatDate(Object val) {
        if (val == null) {
            return "";
        }
        String s = val.toString();
        // 时间戳
        try {
            long ts = Long.parseLong(s);
            SimpleDateFormat sf = new SimpleDateFormat("yyyy-MM-dd");
            return sf.format(new Date(ts));
        } catch (NumberFormatException ignored) {
        }
        return s;
    }

    protected String str(Object v) {
        return v == null ? "" : v.toString();
    }

    /** SQL 转义防注入 */
    protected String escapeSql(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("'", "''");
    }

    /** LIKE 转义（转义 % _ \） */
    protected String escapeLike(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * 解析可能是 JSON 数组字符串的值，返回第一个元素
     * 例如: ["基础角色"] -> "基础角色", ["协调员角色","其他"] -> "协调员角色"
     * 非数组原样返回
     */
    protected String parseFirstArrayItem(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        // 尝试解析 JSON 数组
        try {
            JSONArray arr = JSON.parseArray(s);
            if (arr != null && !arr.isEmpty()) {
                Object first = arr.get(0);
                return first != null ? first.toString() : "";
            }
        } catch (Exception ignored) {
            // 不是 JSON 数组，原样返回
        }
        return s;
    }

    // ===================== 以下为原分散在各子类的重复方法，统一下沉（2026-10-19）=====================

    /** null 安全 trim：null / 空串 / 纯空白 → null。（原在 AccountAccessListApi / PermissionListApi / RoleDefPermissionsApi 各一份） */
    protected String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 取 JSON 数组首元素；非数组原样 toString。（原 ApprovalDetailApi.firstItem，与 parseFirstArrayItem 合流） */
    protected String firstArrayItem(Object val) {
        if (val == null) {
            return "";
        }
        if (val instanceof JSONArray) {
            JSONArray arr = (JSONArray) val;
            return arr.isEmpty() ? "" : str(arr.get(0));
        }
        return str(val);
    }

    /**
     * 构建「带日志的成功响应」（列表类统一用：只打条数不打出参全量）。
     * <p>原 AccountAccessListApi / PermissionListApi 各有一份完全相同的实现。</p>
     */
    protected String success(JSONObject result, int count, long startTs) {
        String resp = HttpUtil.success(result).toJSONString();
        logOutList(TAG_BASE, true, count, startTs);
        return resp;
    }

    /** 构建「带日志的失败响应」。原 AccountAccessListApi / PermissionListApi 各有一份相同实现。 */
    protected String fail(String code, String msg, long startTs) {
        String resp = HttpUtil.fail(code, msg).toJSONString();
        logger.warn(TAG_BASE + " 出参: " + resp + " 耗时=" + (System.currentTimeMillis() - startTs) + "ms");
        return resp;
    }

    /**
     * 取消息中心业务类型（entityType）。
     * <p>优先读配置集 {@code entityType.MessageCenter}；取不到或读失败时回退沙盒默认值。</p>
     * <p>原 RoleDefSaveApi / PermissionSaveApi 各有一份完全相同的实现。</p>
     *
     * @param tag 调用方 TAG（用于日志）
     */
    protected static Long resolveMessageCenterEntityType(String tag) {
        try {
            Map<String, String> cfg = CustomConfigService.instance().getConfigSet("entityType");
            String v = (cfg == null) ? null : cfg.get("MessageCenter");
            if (v != null && !v.trim().isEmpty()) {
                return Long.valueOf(v.trim());
            }
        } catch (Exception e) {
            logger.warn(tag + " 读取自定义配置 entityType.MessageCenter 失败，使用内置默认值", e);
        }
        return null;
    }

    /**
     * id 集合 → 数值 IN 子句内容（不含外层括号，如 {@code 123,456}），自动跳过非数字。
     * <p>用于 reference 字段（purchaseId__c 等）——这类字段比对值不能加引号。</p>
     * <p>原 AccountAccessListApi 内filterCoordinatorAccounts / queryAccountUserCounts 各写了一份。</p>
     *
     * @return 拼好的内容；无可用数字时返回空串
     */
    protected String buildNumericIn(Collection<String> ids, String fieldLabel) {
        StringBuilder sb = new StringBuilder();
        for (String raw : ids) {
            String t = raw == null ? "" : raw.trim();
            if (!t.matches("\\d+")) {
                logger.warn(TAG_BASE + " " + fieldLabel + " 非数字，已跳过: " + t);
                continue;
            }
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(t);
        }
        return sb.toString();
    }

    /**
     * 把 id 集合拼成角色/权限名列表文案：空集合返回「无」，查不到名称时回显 id。
     *
     * @param ids     id 集合
     * @param nameMap id → 名称
     * @param sep     分隔符
     */
    protected String joinNames(Collection<String> ids, Map<String, String> nameMap, String sep) {
        if (ids == null || ids.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            String name = nameMap == null ? null : nameMap.get(id);
            sb.append((name == null || name.isEmpty()) ? id : name);
        }
        return sb.toString();
    }
}
