package other.myse.invoice.utils;

import com.rkhd.platform.sdk.context.ScriptRuntimeContext;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.service.CacheService;

/**
 * 权限变化守卫（租户级缓存令牌）。
 *
 * <p><b>机制（老板 2026-10-22 定）</b>：</p>
 * <pre>
 *   权限变更时（coordinator 包写入方：PermissionSaveApi / RoleDefSaveApi）
 *     → 往 CacheService 写一条 key = {userId} 的记录
 *   接口调用时（本类）
 *     → 用当前登录用户的 id 命中这条记录
 *     → 命中 = 权限确实变了 → 返回 PERMISSION_CHANGED 给前端，并【清空该标记】
 *     → 未命中 = 权限没变 → 放行
 * </pre>
 *
 * <p><b>为什么用 CacheService 而不是内存 Map</b>：CacheService 是<b>租户级</b>的，
 * 写权限的请求和读权限的请求即使落在不同实例上也能互相看到；进程重启也不会丢标记。</p>
 *
 * <p><b>标记是一次性的</b>：命中后立即删除。前端收到 PERMISSION_CHANGED 后重新登录/刷新，
 * 后续调用即恢复正常，不会反复报错。</p>
 *
 * <p><b>⚠️ 跨包不可见</b>：销售易各代码包独立编译、互相不可见，所以
 * {@code other.myse.coordinator} / {@code other.myse.home} / {@code other.myse.invoice} /
 * {@code other.myse.messagecenter} / {@code other.myse.general} 各有一份本类，
 * <b>逻辑保持一致，改动请同步</b>（coordinator 那份在基类 {@code UserPermissionBaseApi} 里）。</p>
 */
public class PermissionChangeGuard {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 权限变更标记的 value（固定字符串，语义是「有变更」）。 */
    private static final String PERM_CHANGED_FLAG = "1";

    /** 权限已变化的错误码：前端收到此码应提示「权限已发生变化」并引导重新登录。 */
    public static final String CODE_PERMISSION_CHANGED = "PERMISSION_CHANGED";

    /** 统一的权限失效提示语 */
    public static final String MSG_PERMISSION_CHANGED = "您的权限已发生变化，请重新登录或联系管理员申请权限";

    private PermissionChangeGuard() {
    }

    /**
     * 权限变化守卫：查租户级缓存里有没有当前用户的「权限变更」标记。
     *
     * <p><b>fail-open</b>：缓存读失败只打 error 并放行 —— 缓存是"变化检测"的加速手段，
     * 不该因为缓存服务抖动就让所有接口不可用。</p>
     *
     * <p>取不到当前登录用户 → <b>拒绝访问</b>（无法确认权限状态，保守处理）。</p>
     *
     * @param tag 调用方 TAG（用于日志）
     * @return {@code null} = 权限未变化，可继续执行业务；
     *         非 null = 失败响应 JSON，调用方应直接 return
     */
    public static String checkPermissionChanged(String tag) {
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
     * @return true = 标记成功；false = 标记失败（已打 error 日志）
     */
    public static boolean markPermissionChanged(String tag, Long userId) {
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
     * 判断当前是否运行在<b>本地调试环境</b>（IDEA 里直接跑 main）。
     *
     * <p><b>判据</b>（实测）：本地无平台上下文时
     * {@code ScriptRuntimeContext.getTenantId()} 返回 <b>-1</b>、
     * {@code getEnvInfo()} 返回 <b>null</b>；平台上两者都有效。</p>
     *
     * <p><b>为什么要区分</b>：本地跑 main 时没有登录会话，取不到 userId，
     * 权限守卫会直接拒绝 → <b>本地完全没法调试</b>。本地放行不影响平台上的安全性。</p>
     */
    public static boolean isLocalDebug() {
        try {
            Long tenantId = ScriptRuntimeContext.instance().getTenantId();
            return tenantId == null || tenantId <= 0;
        } catch (Exception e) {
            // 取不到租户 → 视为本地（保守放行，避免本地调试被阻断）
            return true;
        }
    }

/**
     * 取当前登录用户 id（服务端会话，<b>不信任入参</b>）。
     *
     * @param tag 调用方 TAG
     * @return 取不到时返回 null
     */
    public static Long currentUserId(String tag) {
        Long userId = null;
        try {
            userId = ScriptRuntimeContext.instance().getUserId();
        } catch (Exception e) {
            logger.error(tag + " 取当前登录用户失败: " + e.getMessage(), e);
        }
        // ⚠️ SDK 的默认值是 -1 不是 null，必须显式判掉
        if (userId == null || userId <= 0) {
            logger.error(tag + " 取不到当前登录用户（服务端会话为空或非法）");
            return null;
        }
        return userId;
    }

    /**
     * 权限变更标记的缓存 key：<b>直接用 userId</b>（老板 2026-10-22 定，不加额外前缀）。
     * <p>⚠️ 副作用：若租户上其他业务也用 {@code CacheService} 且 key 恰好是纯数字 userId，
     * 会互相干扰。当前项目内只有本守卫使用 CacheService，无冲突。</p>
     */
    public static String permChangedKey(Long userId) {
        return String.valueOf(userId);
    }

    /** 构造「权限已变化」的失败响应，供各端点统一返回。 */
    public static String permissionChangedResp(String tag) {
        logger.warn(tag + " 拒绝访问：检测到用户权限已发生变化, code=" + CODE_PERMISSION_CHANGED);
        return HttpUtil.fail(CODE_PERMISSION_CHANGED, MSG_PERMISSION_CHANGED).toJSONString();
    }
}