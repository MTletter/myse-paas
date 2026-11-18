package other.myse.general.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import other.myse.general.utils.HttpUtil;
import other.myse.general.utils.PermissionChangeGuard;
import other.myse.general.utils.XoqlHelper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 产品批量校验：POST /rest/data/v2.0/scripts/api/general/product/verify
 * operateType=priceQuery（默认）：①不存在 → ②02渠道非选型 → ③价格货期黑白名单 → ④客户销售组织 → ⑤DChain。
 * operateType=quoteCreate（报价单）：①不存在 → ②报价单黑白名单 → ③客户销售组织 → ④DChain → ⑤Attr6(internetSales__c)。
 * operateType=orderCreate（订单）：①不存在 → ②仅02渠道 → ③订单黑白名单 → ④客户销售组织 → ⑤DChain。
 * operateType=specialPriceQuery（特价申请单）：仅校验产品是否存在，其余（渠道/黑白名单/客户销售组织/DChain/Attr6）全部跳过。
 */
@RestApi(baseUrl = "/general")
public class ProductVerifyApi {
    private static final Logger logger = LoggerFactory.getLogger();

    /** 日志标识：本类所有日志统一前缀，便于线上按接口检索 */
    private static final String TAG = "ProductVerify";

    /** 单条日志中 SQL / 报文的最大打印长度，超出截断，避免大报文刷屏 */
    private static final int LOG_MAX_LEN = 1000;

    private static final int CHUNK = 500;

    private static final String MSG_NOT_EXIST = "订货号不存在，请核实您输入的订货号是否正确。";
    private static final String MSG_CHANNEL_02 = "该订货号不支持自助查询，请联系订单员。";
    private static final String MSG_BW = "该订货号不支持自助查询，请联系销售。";
    private static final String MSG_QUOTE_BW = "该产品暂时无法下单，请联系销售咨询。";
    private static final String MSG_ORDER_CHANNEL = "该订货号不支持直接创建订单，请先创建报价单。";
    private static final String MSG_ORDER_BW = "该订货号不支持直接创建订单，请联系销售咨询。";
    private static final String MSG_NO_SALES_ORG = "物料主数据维护不完整，请联系订单员";
    private static final String MSG_LOCK_FALLBACK = "该订货号存在采购锁定，请联系订单员（错误代码：";
    private static final String OP_PRICE = "priceQuery";
    private static final String OP_QUOTE = "quoteCreate";
    private static final String OP_ORDER = "orderCreate";
    private static final String OP_SPECIAL_PRICE = "specialPriceQuery";
    private static final String FLAG_PRICE = "priceQuery__c";
    private static final String FLAG_QUOTE = "orderCreation__c";

    private static final String CHANNEL_01 = "01";
    private static final String CHANNEL_02 = "02";
    private static final String DCHAIN_EXEMPT = "Y6";
    private static final String DICT_MATERIAL_LOCK = "物料锁采购状态表";
    private static final long DICT_CACHE_TTL = 30L * 60L * 1000L;
    private static final int SEC_ENTITY = 1;

    private static volatile Map<String, String> lockMsgCache = null;
    private static volatile long lockMsgCacheTime = 0L;

    @RestMapping(value = "/product/verify", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        long start = System.currentTimeMillis();
        logger.info(TAG + " 入参: body=" + abbreviate(body));
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
            String accountId = trim(req.getString("accountId"));
            String legalEntity = trim(req.getString("legalEntity"));
            String operateType = getOperateType(req);
            String salesOrgId=trim(req.getString("salesOrgId"));
            boolean quoteMode = OP_QUOTE.equals(operateType);
            boolean orderMode = OP_ORDER.equals(operateType);
            boolean specialPriceMode = OP_SPECIAL_PRICE.equals(operateType);
            JSONArray items = req.getJSONArray("items");
            if (legalEntity.isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "legalEntity 交易实体不能为空").toJSONString();
            }
            if (items == null || items.isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "items 不能为空").toJSONString();
            }

            List<Integer> entityCodes = parseEntityCodes(legalEntity);
            if (entityCodes.isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "legalEntity 格式非法").toJSONString();
            }

            List<JSONObject> inputList = new ArrayList<JSONObject>();
            Set<String> productNames = new HashSet<String>();
            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (item == null) continue;
                inputList.add(item);
                String name = trim(item.getString("productName"));
                if (!name.isEmpty()) productNames.add(name);
            }
            if (productNames.isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "items 中缺少 productName").toJSONString();
            }

            logger.info(TAG + " 请求解析: operateType=" + operateType + ", accountId=" + accountId
                    + ", legalEntity=" + legalEntity + ", salesOrgId=" + salesOrgId
                    + ", itemCount=" + items.size() + ", 去重订货号数=" + productNames.size());

            Map<String, JSONObject> products = queryProducts(productNames);
            EntityScope entity = queryEntityScope(entityCodes);
            List<Long> productIds = collectProductIds(products.values());
            Map<Long, List<JSONObject>> orgMap = queryProductOrgs(productIds,salesOrgId);
            Map<Long, String> priceMap = queryPrices(productIds);
            Set<Long> accountOrgs = queryAccountSalesOrgIds(accountId);
            Set<Long> custOrgs = intersect(accountOrgs, entity.saleOrgIds);
            String bwField = (quoteMode || orderMode) ? FLAG_QUOTE : FLAG_PRICE;
            List<JSONObject> bwRules = specialPriceMode ? new ArrayList<JSONObject>() : queryEnabledBwRules(bwField);

            logger.info(TAG + " 校验上下文: 命中产品=" + products.size() + ", 交易实体销售组织=" + entity.saleOrgIds.size()
                    + ", 客户销售组织=" + accountOrgs.size() + ", 客户∩实体=" + custOrgs.size()
                    + ", 黑白名单规则=" + bwRules.size() + ", 黑白名单字段=" + bwField);

            JSONArray result = new JSONArray();
            for (JSONObject item : inputList) {
                result.add(verifyOne(item, products, orgMap, priceMap, entity, accountId, accountOrgs,
                        custOrgs, bwRules, quoteMode, orderMode, specialPriceMode, bwField));
            }

            int pass = 0;
            StringBuilder blocked = new StringBuilder();
            for (int i = 0; i < result.size(); i++) {
                JSONObject r = result.getJSONObject(i);
                if (Boolean.TRUE.equals(r.getBoolean("valid"))) {
                    pass++;
                } else if (blocked.length() < LOG_MAX_LEN) {
                    blocked.append("[").append(str(r, "productName")).append(": ").append(str(r, "message")).append("]");
                }
            }
            logger.info(TAG + " 出参: 校验条数=" + result.size() + ", 通过=" + pass
                    + ", 拦截=" + (result.size() - pass) + ", 耗时=" + (System.currentTimeMillis() - start) + "ms"
                    + ", 拦截明细=" + blocked);

            return HttpUtil.success(new JSONObject().fluentPut("items", result)).toJSONString();
        } catch (Exception e) {
            logger.error(TAG + " 执行异常, body=" + abbreviate(body), e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    private JSONObject verifyOne(JSONObject item, Map<String, JSONObject> products,
                                 Map<Long, List<JSONObject>> orgMap, Map<Long, String> priceMap,
                                 EntityScope entity, String accountId, Set<Long> accountOrgs,
                                 Set<Long> custOrgs, List<JSONObject> bwRules,
                                 boolean quoteMode, boolean orderMode, boolean specialPriceMode, String bwField) {
        String name = trim(item.getString("productName"));
        JSONObject detail = name.isEmpty() ? null : products.get(name);
        if (detail == null) {
            return buildResult(item, false, MSG_NOT_EXIST, "", null);
        }

        long pid = toLong(detail.get("id"));
        List<JSONObject> orgs = orgMap.get(pid);
        if (orgs == null) orgs = new ArrayList<JSONObject>();
        List<JSONObject> entityOrgs = filterOrgs(orgs, entity.saleOrgIdSet);
        logger.info(TAG + " 明细: item=" + name + ", pid=" + pid + ", orgs=" + describeOrgs(orgs)
                + ", entityOrgs=" + describeOrgs(entityOrgs) + ", custOrgs=" + custOrgs
                + ", entitySec=" + entity.sec);

        // 特价申请单（specialPriceQuery）：仅校验产品是否存在，其余校验（渠道/黑白名单/客户销售组织/DChain/Attr6）全部跳过。
        if (specialPriceMode) {
            return buildResult(item, true, "", "", buildProductInfo(detail, orgs, priceMap.get(pid)));
        }

        // 价格货期：仅02渠道且非选型时拦截；报价单创建跳过渠道；订单创建只要仅属02渠道即拦截。
        if (orderMode && isOnly02Channel(orgs)) {
            logger.info(TAG + " 拦截[订单-仅02渠道]: item=" + name + ", pid=" + pid + ", orgs=" + describeOrgs(orgs));
            return buildResult(item, false, MSG_ORDER_CHANNEL, "", buildProductInfo(detail, orgs, priceMap.get(pid)));
        }
        if (!quoteMode && !orderMode && isOnly02NonConfigurable(orgs, detail)) {
            logger.info(TAG + " 拦截[查询-仅02渠道非选型]: item=" + name + ", pid=" + pid
                    + ", kzkfg=" + str(detail, "kzkfg__c") + ", orgs=" + describeOrgs(orgs));
            return buildResult(item, false, MSG_CHANNEL_02, "", buildProductInfo(detail, orgs, priceMap.get(pid)));
        }

        String mpg = getMpg(entityOrgs.isEmpty() ? orgs : entityOrgs);
        if (!allowBw(bwRules, entity, accountId, custOrgs, String.valueOf(pid), mpg, bwField)) {
            String message = orderMode ? MSG_ORDER_BW : (quoteMode ? MSG_QUOTE_BW : MSG_BW);
            logger.info(TAG + " 拦截[黑白名单]: item=" + name + ", pid=" + pid + ", mpg=" + mpg
                    + ", bwField=" + bwField + ", accountId=" + accountId);
            return buildResult(item, false, message, "", buildProductInfo(detail, orgs, priceMap.get(pid)));
        }

        List<JSONObject> matchedOrgs = filterOrgs(orgs, custOrgs);
        if (!accountOrgs.isEmpty() && matchedOrgs.isEmpty()) {
            logger.info(TAG + " 拦截[客户销售组织不匹配]: item=" + name + ", pid=" + pid
                    + ", accountOrgs=" + accountOrgs + ", orgs=" + describeOrgs(orgs));
            return buildResult(item, false, MSG_NO_SALES_ORG, "", buildProductInfo(detail, orgs, priceMap.get(pid)));
        }

        List<JSONObject> scope = !matchedOrgs.isEmpty() ? matchedOrgs : entityOrgs;
        String blocked = findBlockedDChain(scope);
        if (blocked != null) {
            logger.info(TAG + " 拦截[DChain全冻结]: item=" + name + ", pid=" + pid + ", code=" + blocked + ", scope=" + describeOrgs(scope));
            return buildResult(item, false, getLockMessage(blocked), blocked, buildProductInfo(detail, scope, priceMap.get(pid)));
        }

        // 报价单创建：Attr6(internetSales__c) 为空或 0 不允许下单
        if ( isAttr6Blocked(scope)) {
            logger.info(TAG + " 拦截[Attr6不可mySE下单]: item=" + name + ", pid=" + pid
                    + ", scope=" + describeOrgs(scope));
            return buildResult(item, false, "订货号" + name + "不允许在mySE下单，请联系销售咨询。", "", buildProductInfo(detail, scope, priceMap.get(pid)));
        }

        return buildResult(item, true, "", "", buildProductInfo(detail, scope, priceMap.get(pid)));
    }

    private JSONObject buildResult(JSONObject input, boolean valid, String message, String errorCode, JSONObject productInfo) {
        JSONObject out = new JSONObject();
        out.put("lineNo", input.get("lineNo"));
        out.put("productName", input.get("productName"));
        out.put("quantity", input.get("quantity"));
        out.put("description", input.get("description"));
        out.put("valid", valid);
        out.put("message", message);
        out.put("errorCode", errorCode == null ? "" : errorCode);
        if (productInfo != null) out.put("productInfo", productInfo);
        return out;
    }

    private boolean isOnly02Channel(List<JSONObject> orgs) {
        Set<String> channels = collectChannels(orgs);
        return !channels.contains(CHANNEL_01) && channels.contains(CHANNEL_02);
    }

    private boolean isOnly02NonConfigurable(List<JSONObject> orgs, JSONObject detail) {
        return isOnly02Channel(orgs) && !isTrue(detail.get("kzkfg__c"));
    }

    /** 命中 Y 放行、N 拦截；未命中 SEC 放行、其它实体拦截；当前实体无 Y/N 规则则放行。 */
    private boolean allowBw(List<JSONObject> rules, EntityScope entity,
                            String accountId, Set<Long> salesOrgIds,
                            String productId, String mpg, String flagField) {
        if (rules == null || rules.isEmpty()) return true;
        List<JSONObject> scoped = new ArrayList<JSONObject>();
        for (JSONObject rule : rules) {
            if (matchEntity(rule.get("entity__c"), entity.labels)) scoped.add(rule);
        }
        if (scoped.isEmpty()) return true;

        JSONObject hit = findRule(scoped, accountId, "", productId, "");
        if (hit == null) {
            for (Long so : salesOrgIds) {
                hit = findRule(scoped, "", String.valueOf(so), productId, "");
                if (hit != null) break;
            }
        }
        if (hit == null && !mpg.isEmpty()) {
            hit = findRule(scoped, accountId, "", "", mpg);
        }
        if (hit == null && !mpg.isEmpty()) {
            for (Long so : salesOrgIds) {
                hit = findRule(scoped, "", String.valueOf(so), "", mpg);
                if (hit != null) break;
            }
        }
        if (hit != null) {
            boolean allow = isAllowFlag(firstStr(hit, flagField));
            logger.info(TAG + " 黑白名单命中: ruleId=" + hit.get("id") + ", flagField=" + flagField
                    + ", flag=" + firstStr(hit, flagField) + ", allow=" + allow);
            return allow;
        }
        logger.info(TAG + " 黑白名单未命中, 按实体兜底放行: entitySec=" + entity.sec);
        return entity.sec;
    }

    private JSONObject findRule(List<JSONObject> rules, String accountId, String salesOrgId,
                                String productId, String mpg) {
        boolean byAccount = !accountId.isEmpty();
        boolean byProduct = !productId.isEmpty();
        for (JSONObject rule : rules) {
            String ra = str(rule, "purchaseId__c");
            String rs = str(rule, "salesOrg__c");
            String rp = str(rule, "catelogNumber__c");
            String rm = str(rule, "mpg__c");
            boolean customerOk = byAccount ? (accountId.equals(ra) && rs.isEmpty())
                    : (!salesOrgId.isEmpty() && salesOrgId.equals(rs) && ra.isEmpty());
            boolean itemOk = byProduct ? (productId.equals(rp) && rm.isEmpty())
                    : (!mpg.isEmpty() && mpg.equals(rm) && rp.isEmpty());
            if (customerOk && itemOk) return rule;
        }
        return null;
    }

    private List<JSONObject> queryEnabledBwRules(String flagField) {
        List<JSONObject> rows = queryRows("黑白名单", "SELECT id, entity__c, purchaseId__c, salesOrg__c, mpg__c, catelogNumber__c, priceQuery__c, orderCreation__c "
                + "FROM blkWhtList__c WHERE " + flagField + " IS NOT NULL "
                + "ORDER BY updatedAt DESC, createdAt DESC LIMIT 0, 1000");
        List<JSONObject> enabled = new ArrayList<JSONObject>();
        for (JSONObject row : rows) {
            String flag = firstStr(row, flagField).trim();
            if ("Y".equalsIgnoreCase(flag) || "N".equalsIgnoreCase(flag)
                    || "1".equals(flag) || "2".equals(flag)) {
                enabled.add(row);
            }
        }
        logger.info(TAG + " 黑白名单: 原始规则=" + rows.size() + ", 启用规则=" + enabled.size());
        return enabled;
    }

    private Map<String, JSONObject> queryProducts(Set<String> names) {
        Map<String, JSONObject> map = new HashMap<String, JSONObject>();
        List<JSONObject> rows = queryRows("产品", "SELECT id, productName, proDescription__c, kzkfg__c FROM product "
                + "WHERE productName IN (" + joinQuoted(names) + ") AND enableStatus = 1");
        for (JSONObject row : rows) {
            String name = trim(str(row, "productName"));
            if (!name.isEmpty()) map.put(name, row);
        }
        logger.info(TAG + " 产品: 请求订货号=" + names.size() + ", 命中产品=" + map.size()
                + (map.size() == names.size() ? "" : ", 未命中数=" + (names.size() - map.size())));
        return map;
    }

    private EntityScope queryEntityScope(List<Integer> entityCodes) {
        EntityScope scope = new EntityScope();
        scope.sec = entityCodes.contains(Integer.valueOf(SEC_ENTITY));
        List<JSONObject> rows = queryRows("交易实体销售组织", "SELECT id, Eentity__c FROM saleorg__c WHERE Eentity__c IN ("
                + joinInts(entityCodes) + ")");
        for (JSONObject row : rows) {
            long id = toLong(row.get("id"));
            if (id != 0) {
                scope.saleOrgIds.add(id);
                scope.saleOrgIdSet.add(id);
            }
            addLabels(scope.labels, row.get("Eentity__c"));
        }
        logger.info(TAG + " 交易实体销售组织: 实体码=" + entityCodes + ", 销售组织=" + scope.saleOrgIds.size()
                + ", isSec=" + scope.sec);
        return scope;
    }

    private Map<Long, List<JSONObject>> queryProductOrgs(List<Long> productIds,String salesOrgId) {
        Map<Long, List<JSONObject>> map = new HashMap<Long, List<JSONObject>>();
        if (productIds.isEmpty()) {
            logger.info(TAG + " 产品销售组织: 无产品 id，跳过查询");
            return map;
        }
        int seg = 0;
        for (int i = 0; i < productIds.size(); i += CHUNK) {
            seg++;
            List<Long> chunk = productIds.subList(i, Math.min(i + CHUNK, productIds.size()));
            List<JSONObject> rows = queryRows("产品销售组织 " + seg, "SELECT salesUnit__c, product__c, disChannel__c, salesOrg__c, distriStatus__c, materialPriceGroup__c, internetSales__c, priceRestriction__c "
                    + "FROM productOrg__c WHERE product__c IN (" + joinLongs(chunk) + ") and salesOrg__c="+salesOrgId);
            for (JSONObject r : rows) {
                long pid = toLong(r.get("product__c"));
                if (pid == 0) continue;
                List<JSONObject> list = map.get(pid);
                if (list == null) {
                    list = new ArrayList<JSONObject>();
                    map.put(pid, list);
                }
                list.add(r);
            }
        }
        logger.info(TAG + " 产品销售组织: 产品数=" + productIds.size() + ", 覆盖产品=" + map.size()
                + ", 缺失销售组织的产品数=" + (productIds.size() - map.size()));
        return map;
    }

    private Map<Long, String> queryPrices(List<Long> productIds) {
        Map<Long, String> map = new HashMap<Long, String>();
        if (productIds.isEmpty()) return map;
        List<JSONObject> rows = queryRows("价格", "SELECT catelogNumber__c, price__c FROM productPrice__c WHERE catelogNumber__c IN ("
                + joinLongs(productIds) + ")");
        for (JSONObject r : rows) {
            long pid = toLong(r.get("catelogNumber__c"));
            Object price = r.get("price__c");
            if (pid != 0 && price != null && !map.containsKey(pid)) map.put(pid, price.toString());
        }
        logger.info(TAG + " 价格: 产品数=" + productIds.size() + ", 有价产品=" + map.size());
        return map;
    }

    private Set<Long> queryAccountSalesOrgIds(String accountId) {
        Set<Long> ids = new HashSet<Long>();
        if (accountId.isEmpty()) {
            logger.info(TAG + " 客户销售组织: 未传 accountId，跳过查询");
            return ids;
        }
        List<JSONObject> rows = queryRows("客户销售组织", "SELECT salesOrg__c FROM accountSalesOrg__c WHERE purchaseId__c = '"
                + escapeSql(accountId) + "' LIMIT 0, 1000");
        for (JSONObject r : rows) {
            long id = toLong(r.get("salesOrg__c"));
            if (id != 0) ids.add(id);
        }
        logger.info(TAG + " 客户销售组织: accountId=" + accountId + ", 销售组织数=" + ids.size());
        return ids;
    }

    private Set<String> collectChannels(List<JSONObject> orgs) {
        Set<String> channels = new HashSet<String>();
        if (orgs == null) return channels;
        for (JSONObject org : orgs) addLabels(channels, org.get("disChannel__c"));
        return channels;
    }

    private List<JSONObject> filterOrgs(List<JSONObject> orgs, Set<Long> saleOrgIds) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        if (orgs == null || saleOrgIds == null || saleOrgIds.isEmpty()) return out;
        for (JSONObject org : orgs) {
            long so = toLong(org.get("salesOrg__c"));
            if (so != 0 && saleOrgIds.contains(so)) out.add(org);
        }
        return out;
    }

    private String findBlockedDChain(List<JSONObject> orgs) {
        if (orgs == null || orgs.isEmpty()) return null;
        String blockedCode = null;
        for (JSONObject org : orgs) {
            String code = trim(str(org, "distriStatus__c"));
            // 只要存在一个可售（Y6）或未维护状态，就不能判定为全部冻结。
            if (code.isEmpty() || DCHAIN_EXEMPT.equalsIgnoreCase(code)) return null;
            if (blockedCode == null) blockedCode = code;
        }
        // 只有所有销售组织的 DChain Status 都是冻结状态时才拦截。
        return blockedCode;
    }

    private String getLockMessage(String code) {
        Map<String, String> cache = lockMsgCache;
        if (cache == null || System.currentTimeMillis() - lockMsgCacheTime > DICT_CACHE_TTL) {
            cache = loadLockDict();
            lockMsgCache = cache;
            lockMsgCacheTime = System.currentTimeMillis();
            logger.info(TAG + " 物料锁采购状态表缓存刷新完成, 条目数=" + cache.size());
        }
        String msg = cache.get(code);
        return (msg == null || msg.trim().isEmpty()) ? MSG_LOCK_FALLBACK + code + "）" : msg;
    }

    private Map<String, String> loadLockDict() {
        Map<String, String> map = new HashMap<String, String>();
        try {
            List<JSONObject> dicts = queryRows("数据字典", "SELECT id, name FROM dataDictionary__c WHERE name = '"
                    + escapeSql(DICT_MATERIAL_LOCK) + "' LIMIT 0, 10");
            String dictId = null;
            for (JSONObject d : dicts) {
                if (DICT_MATERIAL_LOCK.equals(trim(str(d, "name")))) {
                    dictId = String.valueOf(d.get("id"));
                    break;
                }
            }
            if (dictId == null) return map;
            List<JSONObject> details = queryRows("物料锁状态明细", "SELECT name, description__c FROM dictDetail__c WHERE isEnable__c=1 and dataDict__c = '"
                    + escapeSql(dictId) + "' LIMIT 0, 500");
            for (JSONObject r : details) {
                String name = trim(str(r, "name"));
                if (!name.isEmpty()) map.put(name, str(r, "description__c"));
            }
            logger.info(TAG + " 物料锁采购状态表加载: 明细数=" + map.size());
        } catch (Exception e) {
            logger.error(TAG + " 加载物料锁采购状态表失败", e);
        }
        return map;
    }

    /**
     * 统一查询入口：走 XoqlHelper 内部通道（系统鉴权），打印 SQL、返回条数与耗时。
     * 与 XoqlHelper 的容错约定一致：查询异常返回空集合，不阻断主流程。
     */
    private List<JSONObject> queryRows(String desc, String sql) {
        long t = System.currentTimeMillis();
        List<JSONObject> rows = XoqlHelper.rows(sql);
        logger.info(TAG + " 查询[" + desc + "] 返回=" + rows.size() + " 行, 耗时=" + (System.currentTimeMillis() - t)
                + "ms, sql=" + abbreviate(sql));
        return rows;
    }

    /** 日志截断：超过 maxLen 时保留头部并标注总长度，避免大报文刷屏。 */
    private String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= ProductVerifyApi.LOG_MAX_LEN ? s : s.substring(0, ProductVerifyApi.LOG_MAX_LEN) + "...(总长" + s.length() + ")";
    }

    private JSONObject buildProductInfo(JSONObject detail, List<JSONObject> orgs, String price) {
        JSONObject pi = new JSONObject();
        pi.put("id", detail.get("id"));
        pi.put("productName", detail.get("productName"));
        pi.put("description", str(detail, "proDescription__c"));
        pi.put("kzkfg__c", str(detail, "kzkfg__c"));

        JSONArray disChannels = new JSONArray();
        JSONObject firstOrg = null;
        if (orgs != null) {
            for (JSONObject org : orgs) {
                String dchain = trim(str(org, "distriStatus__c"));
                // 仅返回未冻结销售组织（Y6 或未维护状态）的渠道。
                if (!dchain.isEmpty() && !DCHAIN_EXEMPT.equalsIgnoreCase(dchain)) continue;
                if (firstOrg == null) firstOrg = org;
                JSONArray dc = org.getJSONArray("disChannel__c");
                if (dc != null && !dc.isEmpty()) {
                    disChannels.add(dc.getString(0));
                }
            }
        }
        pi.put("unit", firstOrg == null ? "" : str(firstOrg, "salesUnit__c"));
        pi.put("disChannel", disChannels);
        pi.put("price", price == null ? "" : price);
        pi.put("priceRestriction__c", firstOrg == null ? "" : str(firstOrg, "priceRestriction__c"));
        return pi;
    }

    private boolean matchEntity(Object value, Set<String> labels) {
        if (value == null || labels == null || labels.isEmpty()) return false;
        Set<String> ruleLabels = new HashSet<String>();
        addLabels(ruleLabels, value);
        for (String ruleLabel : ruleLabels) {
            for (String label : labels) {
                if (ruleLabel.contains(label) || label.contains(ruleLabel)) return true;
            }
        }
        return false;
    }

    private void addLabels(Set<String> out, Object value) {
        if (value == null) return;
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.size(); i++) {
                if (arr.get(i) != null) {
                    String s = arr.get(i).toString().trim();
                    if (!s.isEmpty()) out.add(s);
                }
            }
        } else {
            String s = value.toString().trim();
            if (!s.isEmpty()) out.add(s);
        }
    }

    /** 日志用：销售组织行摘要 salesOrg:attr6/dchain/mpg。 */
    private String describeOrgs(List<JSONObject> orgs) {
        if (orgs == null || orgs.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < orgs.size(); i++) {
            JSONObject org = orgs.get(i);
            if (i > 0) sb.append("; ");
            sb.append("salesOrg=").append(str(org, "salesOrg__c"))
              .append(",attr6=").append(str(org, "internetSales__c"))
              .append(",dchain=").append(str(org, "distriStatus__c"))
              .append(",mpg=").append(str(org, "materialPriceGroup__c"))
              .append(",channel=").append(str(org, "disChannel__c"));
        }
        return sb.append(']').toString();
    }

    private List<Integer> parseEntityCodes(String s) {
        List<Integer> out = new ArrayList<Integer>();
        for (String part : s.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            try {
                out.add(Integer.valueOf(t));
            } catch (Exception e) {
                logger.warn(TAG + " 忽略非法 legalEntity: " + t);
            }
        }
        return out;
    }

    private List<Long> collectProductIds(Collection<JSONObject> products) {
        List<Long> ids = new ArrayList<Long>();
        for (JSONObject p : products) {
            long id = toLong(p.get("id"));
            if (id != 0) ids.add(id);
        }
        return ids;
    }

    private Set<Long> intersect(Set<Long> a, List<Long> b) {
        Set<Long> out = new HashSet<Long>();
        if (a == null || b == null) return out;
        for (Long id : b) if (id != null && a.contains(id)) out.add(id);
        return out;
    }

    private String getMpg(List<JSONObject> orgs) {
        if (orgs == null) return "";
        for (JSONObject org : orgs) {
            String mpg = str(org, "materialPriceGroup__c");
            if (!mpg.isEmpty()) return mpg;
        }
        return "";
    }

    private JSONObject pickOrg(List<JSONObject> orgs) {
        return (orgs == null || orgs.isEmpty()) ? null : orgs.get(0);
    }

    private String getOperateType(JSONObject req) {
        String t = trim(req.getString("operateType"));
        if (t.isEmpty()) t = trim(req.getString("operationType"));
        if (OP_QUOTE.equalsIgnoreCase(t) || "quote".equalsIgnoreCase(t) || "2".equals(t)) return OP_QUOTE;
        if (OP_ORDER.equalsIgnoreCase(t) || "order".equalsIgnoreCase(t) || "3".equals(t)) return OP_ORDER;
        if (OP_SPECIAL_PRICE.equalsIgnoreCase(t)) return OP_SPECIAL_PRICE;
        return OP_PRICE;
    }

    /** Attr6：internetSales__c 为空或 0 视为不可在 mySE 下单；X/1/Y 放行。 */
    private boolean isAttr6Blocked(List<JSONObject> orgs) {
        if (orgs == null || orgs.isEmpty()) return false;
        for (JSONObject org : orgs) {
            String v = trim(str(org, "internetSales__c"));
            String dchain = trim(str(org, "distriStatus__c"));
            if (v.isEmpty() || "0".equals(v)||"TP".equals(dchain)) return true;
        }
        return false;
    }

    private boolean isAllowFlag(String v) {
        String s = trim(v);
        return "Y".equalsIgnoreCase(s) || "1".equals(s);
    }

    private boolean isTrue(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        String s = v.toString().trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    private String firstStr(JSONObject row, String key) {
        Object v = row.get(key);
        if (v instanceof JSONArray && ((JSONArray) v).size() > 0) {
            Object first = ((JSONArray) v).get(0);
            return first == null ? "" : first.toString();
        }
        return v == null ? "" : v.toString();
    }

    private String str(JSONObject r, String key) {
        Object v = r.get(key);
        return v == null ? "" : v.toString().trim();
    }

    private String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(v.toString().trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private String escapeSql(String s) {
        return s.replace("'", "''");
    }

    private String joinInts(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (Integer id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    private String joinLongs(Collection<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    private String joinQuoted(Collection<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(',');
            sb.append('\'').append(escapeSql(v)).append('\'');
        }
        return sb.toString();
    }

    private static class EntityScope {
        private final List<Long> saleOrgIds = new ArrayList<Long>();
        private final Set<Long> saleOrgIdSet = new HashSet<Long>();
        private final Set<String> labels = new HashSet<String>();
        private boolean sec;
    }
}
