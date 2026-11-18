package other.myse.home.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.exception.ApiEntityServiceException;
import com.rkhd.platform.sdk.http.RkhdHttpClient;
import com.rkhd.platform.sdk.http.RkhdHttpData;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.XoqlService;
import other.myse.home.utils.HttpUtil;
import other.myse.home.utils.PermissionChangeGuard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 首页数据概览接口
 *
 * <p><b>入参</b></p>
 * <pre>
 * {
 *   "userId": 4433780814545882,               // 必填
 *   "quote":        { "accounts": [...] },    // 报价单维度（各维度独立）
 *   "specialOrder": { "accounts": [...] },    // 特价申请维度
 *   "order":        { "accounts": [...] },    // 订单维度
 *   "delivery":     { "accounts": [...] }     // 发货单维度
 * }
 * </pre>
 * 每个维度的 accounts 元素：{@code { "accountId": 4488926381951014, "entity": ["1","3","4"] }}。
 *
 * <p><b>兼容旧结构</b>：顶层 {@code accounts}（所有维度共用一套范围）、{@code accountIds}/{@code accountId}、
 * {@code saleOrgId}（销售组织 id，仍会反查成交易实体，作为所有维度的附加过滤）。</p>
 *
 * <p><b>某维度没传 accounts</b> → 该维度按 userId 作为所有人的全部客户统计，且不限交易实体。</p>
 *
 * <p><b>交易实体选项值</b>（saleorg__c.Eentity__c 的 picklist 选项，<b>整数、不加引号</b>）：
 * <b>1</b>=SEC 施耐德电气(中国) / <b>2</b>=SEIT 施耐德电气信息技术 / <b>3</b>=SWEEC 施耐德万高(天津) /
 * <b>4</b>=ASCO 上海高双。前端<b>直接传选项值本身</b>，后端不需要反查销售组织。</p>
 *
 * <p><b>四类待办</b>：待审批特价申请 / 待确认报价单 / 未完成订单 / 未发货发货单。</p>
 *
 * <p><b>统计口径：各单据 {@code entity__c IN (该维度该账户的交易实体)}</b>。
 * 四类单据的 {@code entity__c} 字段一致（quote/order/delivery 是 join，
 * specialOrderApplication 是 picklist，<b>四类都能用 {@code IN (选项value)} 过滤</b>，已实测）。</p>
 *
 * <p><b>⚠️ 两条 XOQL 口径（改代码前务必看）</b>：</p>
 * <ul>
 *   <li><b>逐账户拼条件</b>：不同账户的交易实体不同（例：A=entity[1,3,4]，B=entity[2]），
 *       写成一次 {@code 账户 IN (...) AND 交易实体 IN (...)} 会把 A 的限制套到 B 上。</li>
 *   <li><b>picklist 过滤必须用选项 value（整数、不加引号）</b>：用中文 label 报
 *       {@code must be option code}，用 apiKey 报 {@code no such column}，用 {@code '3'}（带引号）同样报错。</li>
 * </ul>
 *
 * <p><b>容错</b>：某账户 entity 为空数组 → 该账户不拼 {@code entity__c} 条件（不限交易实体）；
 * 传了非数字的脏值 → 忽略该值并 warn。</p>
 *
 * <p>销售易 API 路径：/rest/data/v2.0/scripts/api/home/overview</p>
 */
@RestApi(baseUrl = "/home")
public class HomeDataOverviewApi {

    private static final Logger logger = LoggerFactory.getLogger();

    private static final String TAG = "HomeDataOverview";

    /**
     * 四类待办的统计定义（配置驱动，避免四段几乎一样的代码）。
     *
     * <p><b>数组含义</b>：{@code [对象apiKey, 账户字段, 状态条件, 要排除的业务类型apiKey]}。
     * 第 4 个元素为 {@code null} 表示该对象只有一个业务类型、无需排除。</p>
     *
     * <p><b>为什么要排除业务类型</b>（老板 2026-10-22 明确）：草稿类单据还没正式创建，
     * 不该计入首页待办。但草稿单据的<b>状态值仍在待办状态列表里</b>
     * （如草稿订单 status=订单已创建/订单创建中，对应 value 1/16，都在待办条件里），
     * 所以光看状态排除不掉，必须额外按 {@code entityType} 排除。</p>
     *
     * <p>业务类型<b>不硬编码 id</b>，按 apiKey 运行时查元数据拿（换环境不用改代码）。</p>
     */
    private static final String[] OBJ_SPECIAL_ORDER = {
            "specialOrderApplication__c", "purchaserId__c", " AND state__c = 2", "businessType2__c"};
    private static final String[] OBJ_QUOTE = {
            "quote__c", "purchaserId__c", " AND quoteStatus__c = 2", "businessType2__c"};
    private static final String[] OBJ_ORDER = {
            "order__c", "purchaseId__c", " AND orderStatus__c IN (1,2,5,6,7,8,11,12,16)", "draftOrder__c"};
    private static final String[] OBJ_DELIVERY = {
            "delivery__c", "purchaseId__c", " AND deliveryStatus__c = 1", null};

    /** 出参 key，与上面四个统计定义顺序一致。 */
    private static final String[] RESULT_KEYS = {
            "specialOrderPending", "quoteTo", "orderUnfinished", "deliveryUnshipped"};

    /**
     * 四个维度在<b>入参</b>里的 key（各自带独立的 accounts + entity 范围）。
     * <p>顺序与 {@link #DIM_SPECS} / {@link #RESULT_KEYS} 一致。</p>
     */
    private static final String[] DIM_KEYS = {"specialOrder", "quote", "order", "delivery"};

    /** 四个维度各自要统计的单据定义，与 {@link #DIM_KEYS} 同序。 */
    private static final String[][] DIM_SPECS = {
            OBJ_SPECIAL_ORDER, OBJ_QUOTE, OBJ_ORDER, OBJ_DELIVERY};

    /**
     * 四类单据上的交易实体字段，四者一致，故用同一个常量。
     * <p>quote/order/delivery 是 join → saleorg__c.Eentity__c（不可创建更新）；
     * specialOrderApplication 是 picklist（可写）。<b>四类都能用 {@code entity__c IN (选项value)} 过滤</b>。</p>
     */
    private static final String ENTITY_FIELD = "entity__c";

    /** 销售组织反查时的单次上限。 */
    private static final int MAX_SALEORGS = 2000;

    // ==================== 业务类型 id 缓存（排除草稿单据用） ====================

    /** key = 对象apiKey#业务类型apiKey → 业务类型 id */
    private static final Map<String, Long> BUSI_TYPE_ID_CACHE = new ConcurrentHashMap<>();

    /** key 同上 → 加载时间戳（毫秒） */
    private static final Map<String, Long> BUSI_TYPE_LOADED_AT = new ConcurrentHashMap<>();

    /** 业务类型缓存有效期（30 分钟） */
    private static final long BUSI_TYPE_TTL = 30L * 60L * 1000L;

    /**
     * 交易实体「中文 label → 选项 value」映射（saleorg__c.Eentity__c 的选项集固定 4 条）。
     * <p><b>为什么要这张表</b>：XOQL 对 picklist 过滤<b>必须用选项 value（整数、不加引号）</b>，
     * 但 SELECT 出来的却是中文 label —— 两边对不上，必须自己转换。
     * （用 label 报 {@code must be option code}，用 apiKey 报 {@code no such column}）</p>
     */
    private static final Map<String, Integer> ENTITY_LABEL_TO_CODE = new HashMap<String, Integer>();
    static {
        ENTITY_LABEL_TO_CODE.put("SEC施耐德电气(中国)有限公司", 1);
        ENTITY_LABEL_TO_CODE.put("SEIT施耐德电气信息技术(中国)有限公司", 2);
        ENTITY_LABEL_TO_CODE.put("SWEEC施耐德万高(天津)电气设备有限公司", 3);
        ENTITY_LABEL_TO_CODE.put("SASS(ASCO)上海高双电气科技有限公司", 4);
    }

    @RestMapping(value = "/overview", method = RequestMethod.POST)
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
            Long userId = req.getLong("userId");
            if (userId == null) {
                return HttpUtil.fail("PARAM_MISSING", "userId 不能为空").toJSONString();
            }

            // 兼容旧单值入参 saleOrgId（销售组织 id）：仍需反查成交易实体，
            // 作为【所有维度】的附加过滤（逐维度的 entity 优先，附加的是并集）
            Set<Integer> globalEntityCodes = new LinkedHashSet<>();
            Long saleOrgId = toLong(req.get("saleOrgId"));
            if (saleOrgId != null) {
                Set<Integer> fromOrg = resolveEntityCodes(
                        Collections.singleton(saleOrgId));
                globalEntityCodes.addAll(fromOrg);
                logger.info(TAG + " 旧入参 saleOrgId=" + saleOrgId
                        + " → 交易实体选项值 " + fromOrg);
            }

            // 旧结构：顶层 accounts（所有维度共用一套范围）
            List<Scope> legacyScopes = parseScopes(req);
            boolean hasLegacy = !legacyScopes.isEmpty();

            JSONObject data = new JSONObject();

            // 客户数 = 四个维度里出现过的账户并集
            Set<Long> allAccounts = new LinkedHashSet<>();

            for (int i = 0; i < DIM_KEYS.length; i++) {
                String dimKey = DIM_KEYS[i];
                // 该维度的范围：优先取入参 dimKey.accounts；没有则回退顶层 accounts（旧结构）
                List<Scope> dimScopes = parseScopes(getJsonObjectSafely(req, dimKey));
                if (dimScopes.isEmpty() && hasLegacy) {
                    dimScopes = copyScopes(legacyScopes);
                }
                if (dimScopes.isEmpty()) {
                    // 该维度完全没给范围 → 按所有人查全部客户，且不限交易实体
                    for (Long accId : queryAccountsByOwner(userId)) {
                        dimScopes.add(new Scope(accId, new LinkedHashSet<Integer>()));
                    }
                    logger.info(TAG + " 维度 " + dimKey + " 未传 accounts，按所有人查全部客户（不限交易实体）");
                }
                for (Scope sc : dimScopes) {
                    allAccounts.add(sc.accountId);
                }
                long cnt = countPending(dimScopes, DIM_SPECS[i], globalEntityCodes);
                data.put(RESULT_KEYS[i], cnt);
                logger.info(TAG + " 维度 " + dimKey + " → " + RESULT_KEYS[i] + " = " + cnt
                        + ", 范围=" + dimScopes);
            }

            data.put("clientCount", allAccounts.size());

            logger.info(TAG + " 完成, userId=" + userId + ", 客户数=" + allAccounts.size()
                    + ", 全局交易实体=" + globalEntityCodes + ", data=" + data);
            return HttpUtil.success(data).toJSONString();

        } catch (Exception e) {
            logger.error(TAG + " 执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    /** 复制一份 Scope 列表（各维度的范围要独立，避免互相污染）。 */
    private List<Scope> copyScopes(List<Scope> src) {
        List<Scope> copy = new ArrayList<>();
        for (Scope sc : src) {
            copy.add(new Scope(sc.accountId, new LinkedHashSet<>(sc.entities)));
        }
        return copy;
    }

    /** 安全取 JSON 对象：key 不存在或值不是对象时返回 null（不抛异常）。 */
    private JSONObject getJsonObjectSafely(JSONObject req, String key) {
        if (req == null) {
            return null;
        }
        Object v = req.get(key);
        return (v instanceof JSONObject) ? (JSONObject) v : null;
    }

    /**
     * 把<b>销售组织 id</b> 解析为交易实体选项值集合。
     *
     * <p><b>当前只用于兼容旧单值入参 {@code saleOrgId}</b>——
     * 新结构 {@code accounts[].entity} 前端已直接给交易实体选项值，不需要反查。</p>
     *
     * <p>销售组织 → 交易实体是多对一（沙盒实测 SEC 一个交易实体下有 7 个销售组织），
     * 故传 1 个销售组织也能覆盖到同实体下所有组织的单据。</p>
     *
     * @param inputOrgs 销售组织 id 集合
     * @return 交易实体选项值并集；<b>空集合表示不按交易实体过滤</b>
     */
    private Set<Integer> resolveEntityCodes(Set<Long> inputOrgs) {
        Set<Integer> result = new LinkedHashSet<>();
        if (inputOrgs.isEmpty()) {
            return result;
        }
        try {
            // ① 入参销售组织 → 交易实体 label
            String sql1 = "SELECT id, Eentity__c FROM saleorg__c WHERE id IN ("
                    + buildIn(inputOrgs) + ") LIMIT 0, " + MAX_SALEORGS;
            Set<String> labels = new LinkedHashSet<>();
            for (JSONObject o : queryRows(sql1)) {
                collectLabels(labels, o.get("Eentity__c"));
            }
            if (labels.isEmpty()) {
                logger.warn(TAG + " 入参销售组织未查到交易实体，本次不按交易实体过滤: " + inputOrgs);
                return result;
            }

            // ② label → 选项 value（picklist 过滤必须用选项 value，不加引号）
            for (String label : labels) {
                Integer code = ENTITY_LABEL_TO_CODE.get(label);
                if (code != null) {
                    result.add(code);
                } else {
                    logger.warn(TAG + " 交易实体 label 未匹配到选项值: " + label);
                }
            }
            if (result.isEmpty()) {
                logger.warn(TAG + " 交易实体选项值解析为空，本次不按交易实体过滤: " + inputOrgs);
            }
            return result;
        } catch (Exception e) {
            logger.error(TAG + " 交易实体解析失败，本次不按交易实体过滤, orgs=" + inputOrgs, e);
            return new LinkedHashSet<Integer>();
        }
    }

    /** 查若干行 JSON（失败返回空列表）。 */
    private List<JSONObject> queryRows(String sql) {
        try {
            QueryResult<JSONObject> rt = XoqlService.instance().query(sql);
            if (rt != null && rt.getRecords() != null) {
                return rt.getRecords();
            }
        } catch (Exception e) {
            logger.error(TAG + " 查询失败: " + sql, e);
        }
        return new ArrayList<>();
    }

    /** 收集 picklist 值里的中文 label（XOQL 返回 label 数组，也可能是标量）。 */
    private void collectLabels(Set<String> out, Object raw) {
        if (raw == null) {
            return;
        }
        if (raw instanceof JSONArray) {
            for (Object o : (JSONArray) raw) {
                collectLabels(out, o);
            }
            return;
        }
        String s = raw.toString().trim();
        if (!s.isEmpty()) {
            out.add(s);
        }
    }

    /** 统计范围：一个账户 + 它允许的交易实体选项值（为空 = 不限）。 */
    private static class Scope {
        final Long accountId;
        /** 该账户允许的交易实体选项值（SEC=1 / SEIT=2 / SWEEC=3 / ASCO=4）；空 = 不限 */
        final Set<Integer> entities;

        Scope(Long accountId, Set<Integer> entities) {
            this.accountId = accountId;
            this.entities = entities;
        }

        @Override
        public String toString() {
            return accountId + "→" + (entities.isEmpty() ? "不限交易实体" : entities);
        }
    }

    /**
     * 逐账户统计并累加。
     *
     * <p><b>必须逐账户拼条件</b>：不同账户允许的交易实体不同
     * （例：账户A=entity[1,3,4]，账户B=entity[2]），
     * 写成一次 {@code 账户 IN (...) AND 交易实体 IN (...)} 会把 A 的限制套到 B 上。</p>
     *
     * @param spec             [对象apiKey, 账户字段, 状态条件]
     * @param globalEntityCodes 全局附加交易实体（旧入参 saleOrgId 用）；逐账户为空时才会用到
     */
    private long countPending(List<Scope> scopes, String[] spec, Set<Integer> globalEntityCodes) {
        String obj = spec[0];
        String accField = spec[1];
        String statusCond = spec[2];
        // 要排除的业务类型 apiKey（草稿单据），第 4 个元素，null = 不排除
        String excludeBusiTypeKey = spec.length > 3 ? spec[3] : null;

        // 草稿业务类型 id 在循环外解析一次（内部有静态缓存），查不到就只记 warn、不拼条件
        Long draftBusiTypeId = null;
        if (excludeBusiTypeKey != null && !excludeBusiTypeKey.trim().isEmpty()) {
            draftBusiTypeId = resolveBusiTypeId(obj, excludeBusiTypeKey);
            if (draftBusiTypeId == null) {
                logger.warn(TAG + " " + obj + " 取不到业务类型 apiKey=" + excludeBusiTypeKey
                        + " 的 id，本次不排除该业务类型（统计结果可能包含草稿）");
            }
        }

        long total = 0;
        for (Scope sc : scopes) {
            // 逐账户过滤：账户自己的交易实体 ∪ 全局附加（兼容旧 saleOrgId 入参）
            Set<Integer> codes = new LinkedHashSet<>();
            if (globalEntityCodes != null) {
                codes.addAll(globalEntityCodes);
            }
            codes.addAll(sc.entities);

            StringBuilder sql = new StringBuilder("SELECT count(1) FROM ").append(obj)
                    .append(" WHERE ").append(accField).append(" = ").append(sc.accountId)
                    .append(statusCond);
            // entity__c IN (选项value)；选项值为空则不拼条件（不限交易实体）
            if (!codes.isEmpty()) {
                sql.append(" AND ").append(ENTITY_FIELD).append(" IN ").append(buildIntIn(codes));
            }
            // 排除草稿业务类型。用 (IS NULL OR !=) 而不是裸 !=：
            // SQL 里 NULL != x 结果是 UNKNOWN，会把 entityType 为空的历史数据一起误伤。
            if (draftBusiTypeId != null) {
                sql.append(" AND (entityType IS NULL OR entityType != ").append(draftBusiTypeId).append(")");
            }

            long cnt = countBySql(sql.toString());
            total += cnt;
            logger.info(TAG + " " + obj + " 账户=" + sc.accountId
                    + " 交易实体=" + (codes.isEmpty() ? "不限" : codes)
                    + " 排除业务类型=" + (draftBusiTypeId == null ? "无" : excludeBusiTypeKey + "#" + draftBusiTypeId)
                    + " → " + cnt + " | " + sql);
        }
        return total;
    }

    /**
     * 按业务类型 apiKey 查它的 id（运行时读元数据，不硬编码）。
     *
     * <p>接口：{@code GET /rest/metadata/v3.1/xobjects/{对象apiKey}/busiTypes}，
     * 返回 {@code data:[{id, apiKey}, ...]}，按 apiKey 精确匹配。</p>
     *
     * <p><b>为什么不写死 id</b>：busiTypeId 是租户环境相关的，沙盒与生产值不同
     * （此前已因硬编码 entityType 踩过坑）。apiKey 才是跨环境稳定的语义标识。</p>
     *
     * <p><b>缓存</b>：静态 Map + 30 分钟 TTL，避免每次统计都发 HTTP。</p>
     *
     * @return 业务类型 id；查不到返回 null（调用方据此不拼排除条件，不影响接口可用性）
     */
    private Long resolveBusiTypeId(String objApiKey, String busiTypeApiKey) {
        String ck = objApiKey + "#" + busiTypeApiKey;
        Long cached = BUSI_TYPE_ID_CACHE.get(ck);
        Long cachedAt = BUSI_TYPE_LOADED_AT.get(ck);
        if (cached != null && cachedAt != null
                && (System.currentTimeMillis() - cachedAt) < BUSI_TYPE_TTL) {
            return cached;
        }
        Long id = null;
        try {
            RkhdHttpData httpData = RkhdHttpData.newBuilder()
                    .callString("/rest/metadata/v3.1/xobjects/" + objApiKey + "/busiTypes")
                    .callType("GET")
                    .build();
            String respStr = RkhdHttpClient.instance().performRequest(httpData);
            if (respStr != null && !respStr.trim().isEmpty()) {
                JSONObject resp = JSON.parseObject(respStr);
                JSONArray arr = resp.getJSONArray("data");
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        JSONObject bt = arr.getJSONObject(i);
                        if (bt == null) {
                            continue;
                        }
                        if (busiTypeApiKey.equals(bt.getString("apiKey"))) {
                            id = bt.getLong("id");
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error(TAG + " 查询业务类型失败 obj=" + objApiKey + " apiKey=" + busiTypeApiKey
                    + ": " + e.getMessage(), e);
        }
        if (id != null) {
            BUSI_TYPE_ID_CACHE.put(ck, id);
            BUSI_TYPE_LOADED_AT.put(ck, System.currentTimeMillis());
            logger.info(TAG + " 业务类型解析: " + ck + " → id=" + id);
        } else {
            logger.warn(TAG + " 业务类型解析失败: " + ck + "（该对象可能没有此业务类型）");
        }
        return id;
    }

    /**
     * 解析统计范围入参。
     *
     * <p><b>新结构（每个维度独立）</b>：入参形如
     * <pre>
     * { "quote": { "accounts": [ {accountId, entity:[选项值...]}, ... ] },
     *   "specialOrder": { "accounts": [...] }, "order": {...}, "delivery": {...} }
     * </pre>
     * 传<b>维度对象</b>进来，返回该维度的范围。</p>
     *
     * <p>同一 accountId 重复出现时合并交易实体。</p>
     *
     * <p><b>旧结构（所有维度共用）</b>：传<b>整个请求体</b>进来，解析顶层 {@code accounts}；
     * 兼容更老的 {@code accountIds} / {@code accountId}（纯账户列表 = 都不限交易实体）。</p>
     */
    private List<Scope> parseScopes(JSONObject req) {
        List<Scope> scopes = new ArrayList<>();
        if (req == null) {
            return scopes;
        }
        Object raw = req.get("accounts");
        if (raw instanceof JSONArray) {
            for (Object item : (JSONArray) raw) {
                if (!(item instanceof JSONObject)) {
                    continue;
                }
                JSONObject o = (JSONObject) item;
                Long accId = toLong(o.get("accountId"));
                if (accId == null) {
                    logger.warn(TAG + " accounts 中有元素缺 accountId，已跳过");
                    continue;
                }
                Set<Integer> entities = new LinkedHashSet<>();
                addEntityCodesTo(entities, o.get("entity"));
                mergeInto(scopes, accId, entities);
            }
            logger.info(TAG + " accounts 入参解析结果: " + scopes);
            return scopes;
        }
        // 旧结构
        Set<Long> ids = new LinkedHashSet<>();
        addAllTo(ids, req.containsKey("accountIds") ? req.get("accountIds") : req.get("accountId"));
        for (Long id : ids) {
            scopes.add(new Scope(id, new LinkedHashSet<Integer>()));
        }
        return scopes;
    }

    /** 找到同 accountId 的 Scope 合并交易实体；找不到则新建。 */
    private void mergeInto(List<Scope> scopes, Long accId, Set<Integer> entities) {
        for (Scope sc : scopes) {
            if (sc.accountId.equals(accId)) {
                sc.entities.addAll(entities);
                return;
            }
        }
        scopes.add(new Scope(accId, entities));
    }

    /**
     * 收集交易实体选项值到集合：支持数组 / 逗号串 / JSON 串 / 单值（数字或字符串）。
     * <p><b>前端传的是选项值本身</b>（如 {@code ["1","3","4"]}），不是销售组织 id，
     * 所以这里<b>不需要</b>再反查 saleorg__c。</p>
     * <p>非数字 / 非法值忽略并 warn，避免把脏值拼进 SQL。</p>
     */
    private void addEntityCodesTo(Set<Integer> target, Object raw) {
        if (raw == null) {
            return;
        }
        if (raw instanceof JSONArray) {
            for (Object o : (JSONArray) raw) {
                addEntityCodesTo(target, o);
            }
            return;
        }
        String s = raw.toString().trim();
        if (s.isEmpty()) {
            return;
        }
        if (s.startsWith("[")) {
            try {
                JSONArray arr = JSON.parseArray(s);
                if (arr != null && !arr.isEmpty()) {
                    addEntityCodesTo(target, arr);
                    return;
                }
            } catch (Exception e) {
                logger.warn(TAG + " entity 数组解析失败: " + s);
            }
            return;
        }
        for (String part : s.split(",")) {
            String one = part.trim();
            if (one.isEmpty()) {
                continue;
            }
            try {
                target.add(Integer.parseInt(one));
            } catch (NumberFormatException e) {
                logger.warn(TAG + " entity 含非法交易实体选项值，已忽略: " + one
                        + "（应为选项 value：SEC=1 / SEIT=2 / SWEEC=3 / ASCO=4）");
            }
        }
    }

    /** 收集 id 到集合：支持数组 / 逗号串 / JSON 串 / 单值，非法值忽略。 */
    private void addAllTo(Set<Long> target, Object raw) {
        if (raw == null) {
            return;
        }
        if (raw instanceof JSONArray) {
            for (Object o : (JSONArray) raw) {
                addAllTo(target, o);
            }
            return;
        }
        String s = raw.toString().trim();
        if (s.isEmpty()) {
            return;
        }
        if (s.startsWith("[")) {
            try {
                JSONArray arr = JSON.parseArray(s);
                if (arr != null) {
                    addAllTo(target, arr);
                }
            } catch (Exception e) {
                logger.warn(TAG + " id 数组解析失败: " + s);
            }
            return;
        }
        for (String part : s.split(",")) {
            Long id = toLong(part);
            if (id != null) {
                target.add(id);
            }
        }
    }

    /** 查 userId 作为所有人的所有客户 id。 */
    private List<Long> queryAccountsByOwner(Long userId) {
        List<Long> ids = new ArrayList<>();
        try {
            QueryResult<JSONObject> rt = XoqlService.instance().query(
                    "SELECT id FROM userRole__c WHERE user__c = " + userId + " LIMIT 0, 1000");
            if (rt != null && rt.getRecords() != null) {
                for (JSONObject r : rt.getRecords()) {
                    Long id = toLong(r.get("id"));
                    if (id != null && !ids.contains(id)) {
                        ids.add(id);
                    }
                }
            }
        } catch (Exception e) {
            logger.error(TAG + " 查询客户列表异常, userId=" + userId, e);
        }
        return ids;
    }

    /**
     * 取满足条件的记录总数。
     *
     * <p><b>用 XOQL {@code SELECT count(1)}，不用 XObject 查询</b>（老板 2026-10-21 明确：
     * XObject 查询不支持取总数）。传进来的 SQL 已是 {@code SELECT count(1) FROM ... WHERE ...}。</p>
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

    /** 构建 IN 子句，如 (1,3,4)。reference 字段比对值是记录 id，不加引号。 */
    private String buildIn(Set<Long> ids) {
        StringBuilder sb = new StringBuilder("(");
        for (Long id : ids) {
            if (sb.length() > 1) {
                sb.append(",");
            }
            sb.append(id);
        }
        return sb.append(")").toString();
    }

    /**
     * 构建整数 IN 子句，如 {@code (1,3,4)}。
     * <p><b>不加引号</b>：XOQL 对 picklist 字段过滤必须用选项 value（整数），
     * 加引号会报 {@code checkbox or select value must be option code}。</p>
     */
    private String buildIntIn(Set<Integer> values) {
        StringBuilder sb = new StringBuilder("(");
        for (Integer v : values) {
            if (sb.length() > 1) {
                sb.append(",");
            }
            sb.append(v);
        }
        return sb.append(")").toString();
    }

    /** null 安全转正整数：Number / 字符串均可，非法或非正数返回 null。 */
    private Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        try {
            long l = (v instanceof Number) ? ((Number) v).longValue() : Long.parseLong(v.toString().trim());
            return l > 0 ? l : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void main(String[] args) throws ApiEntityServiceException {
        QueryResult<JSONObject> res=XoqlService.instance().query("SELECT id FROM quote__c WHERE purchaserId__c = 4488926381951014 AND quoteStatus__c = 2 AND entity__c IN (1,3,4) LIMIT 1");
        logger.info("res:"+JSON.toJSONString(res));
    }
}