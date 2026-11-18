package other.myse.general.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.http.RkhdHttpClient;
import com.rkhd.platform.sdk.http.RkhdHttpData;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.XoqlService;
import other.myse.general.utils.HttpUtil;
import other.myse.general.utils.PermissionChangeGuard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 价格货期查询接口（P&A）
 * <p><b>销售易 API 路径</b>：POST /rest/data/v2.0/scripts/api/general/product/pa-query</p>
 */
@RestApi(baseUrl = "/general")
public class PaQueryApi {

    private static final Logger logger = LoggerFactory.getLogger();

    /** SAP 返回中用于关联 CRM 产品的字段名（值为订货号，对应 product.productName） */
    private static final String CATALOG_NUMBER_FIELD = "CATALOGNUMBER";
    /** SAP 返回中的销售单位字段名（值对应 productPack__c.name 包装类型，匹配后唯一） */
    private static final String SALES_UNIT_FIELD = "SALES_UNIT";
    /** SAP 返回中的发运地点字段名（值对应 shippingPoint__c.name，需翻译成 shipToDesc__c 发运地点描述） */
    private static final String WAREHOUSE_LOCATION_NAME_FIELD = "WAREHOUSELOCATIONNAME";
    private static final int QUERY_CHUNK_SIZE = 500;

    @RestMapping(value = "/product/pa-query", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        long startAt = System.currentTimeMillis();
        try {
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = PermissionChangeGuard.checkPermissionChanged("PaQueryApi");
            if (guard != null) {
                return guard;
            }
            if (body == null || body.trim().isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }
            JSONObject bodyJson = JSON.parseObject(body);
            logger.info("PaQuery start, requestBytes=" + bodyJson);

            long myse01StartAt = System.currentTimeMillis();
            String myse01Resp = callMyse01(body);
            long myse01Cost = System.currentTimeMillis() - myse01StartAt;
            if (myse01Resp == null || myse01Resp.trim().isEmpty()) {
                logger.error("PaQuery Myse01 no response, costMs=" + myse01Cost);
                return HttpUtil.fail("MYSE01_NO_RESPONSE", "调用 pa/query 无返回").toJSONString();
            }
            logger.info("PaQuery Myse01 done, costMs=" + myse01Cost + ", responseBytes=" + myse01Resp.length());

            JSONObject sapResult;
            try {
                sapResult = JSON.parseObject(myse01Resp);
            } catch (Exception e) {
                logger.error("PaQuery Myse01 response parse failed, responseBytes=" + myse01Resp.length(), e);
                return HttpUtil.fail("MYSE01_PARSE_ERROR", "pa/query 返回解析失败: " + e.getMessage()).toJSONString();
            }

            if (isMyse01Failed(sapResult)) {
                JSONObject result = sapResult.getJSONObject("RESULT");
                logger.error("PaQuery Myse01 business failed, returnCode=" + getStr(result, "RETURNCODE")
                        + ", message=" + getStr(result, "MESSAGE") + ", costMs=" + (System.currentTimeMillis() - startAt));
                return sapResult.toJSONString();
            }

            Set<String> catalogNumbers = new LinkedHashSet<String>();
            Set<String> salesUnits = new LinkedHashSet<String>();
            collectValues(sapResult, CATALOG_NUMBER_FIELD, catalogNumbers);
            collectValues(sapResult, SALES_UNIT_FIELD, salesUnits);
            // 发运地点：SAP 只给编码（如 B111），需翻译成发运地点描述（如 SBMV Shipping point 1）
            Set<String> shippingPointCodes = new LinkedHashSet<String>();
            collectValues(sapResult, WAREHOUSE_LOCATION_NAME_FIELD, shippingPointCodes);
            Map<String, String> shipToDescMap = queryShippingPoints(shippingPointCodes);
            injectShippingPointDesc(sapResult, shipToDescMap);

            Map<String, JSONObject> productMap = queryProducts(catalogNumbers);
            Set<String> productIds = collectProductIds(productMap);
            // 包装信息按 包装类型name = SAP SALES_UNIT 匹配，一个产品只命中一条。
            Map<String, JSONObject> packMap = queryProductPacks(productIds, salesUnits);
            // moq__c 不在 productPack__c 上，需要去产品销售组织 productOrg__c 里查。
            Map<String, JSONArray> orgMap = queryProductOrgs(productIds,bodyJson.getJSONObject("HEADER_IN").getString("COMPANYCODE"));

            // MPG（materialPriceGroup__c）来自产品销售组织，取每个产品按销售单位匹配的那条
            Map<String, String> mpgByPid = new HashMap<String, String>();
            // 产品基本信息上的 Material Group（matkl__c），作为 Family 表匹配的 Product Line
            Map<String, String> matklByPid = new HashMap<String, String>();
            for (JSONObject product : productMap.values()) {
                String pid = getStr(product, "id");
                if (pid.isEmpty()) continue;
                String unit = getStr(product, "unit");
                String mpg = findOrgField(orgMap.get(pid), unit, "materialPriceGroup__c");
                if (!mpg.isEmpty()) mpgByPid.put(pid, mpg);
                String matkl = getStr(product, "matkl__c");
                if (!matkl.isEmpty()) matklByPid.put(pid, matkl);
            }
            // Family 名称：family1__c 按 mpg__c + productLine__c 匹配
            Map<String, String> familyMap = queryFamilies(mpgByPid, matklByPid);

            InjectStat stat = new InjectStat();
            InjectCtx ctx = new InjectCtx(productMap, packMap, orgMap, mpgByPid, familyMap, stat);
            injectProductInfo(sapResult, ctx);

            logger.info("PaQuery success, catalogCount=" + catalogNumbers.size()
                    + ", productCount=" + productMap.size()
                    + ", shippingPointCodeCount=" + shippingPointCodes.size()
                    + ", shipToDescCount=" + shipToDescMap.size()
                    + ", packCount=" + packMap.size()
                    + ", orgProductCount=" + orgMap.size()
                    + ", mpgCount=" + mpgByPid.size()
                    + ", familyCount=" + familyMap.size()
                    + ", injectedCount=" + stat.injectedCount
                    + ", missingProductCount=" + stat.missingProductCount
                    + ", packMissCount=" + stat.packMissCount
                    + ", moqMissingCount=" + stat.moqMissingCount
                    + ", mpgMissingCount=" + stat.mpgMissingCount
                    + ", familyMissCount=" + stat.familyMissCount
                    + ", costMs=" + (System.currentTimeMillis() - startAt));
            return sapResult.toJSONString();
        } catch (Exception e) {
            logger.error("PaQuery execute failed, costMs=" + (System.currentTimeMillis() - startAt), e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    /**
     * 调用主包 Myse01（/myse/pa/query）内部 API。
     * 用 RkhdHttpClient 走平台内部路由，formData json=请求体。
     */
    private String callMyse01(String body) {
        try {
            RkhdHttpData httpData = RkhdHttpData.newBuilder()
                    .callString("/rest/data/v2.0/scripts/api/myse/pa/query")
                    .callType("POST")
                    .body(body)
                    .build();
            return RkhdHttpClient.instance().performRequest(httpData);
        } catch (Exception e) {
            logger.error("PaQuery 调用 Myse01 异常", e);
            return null;
        }
    }

    /**
     * 递归遍历 JSON，收集所有 key 为指定字段名的字符串值（去重、去空）。
     */
    private void collectValues(Object node, String field, Set<String> result) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            for (Map.Entry<String, Object> entry : obj.entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                if (field.equals(key) && val != null) {
                    String s = val.toString().trim();
                    if (!s.isEmpty()) {
                        result.add(s);
                    }
                }
                collectValues(val, field, result);
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.size(); i++) {
                collectValues(arr.get(i), field, result);
            }
        }
    }

    /**
     * 批量查询 CRM 产品（按 productName IN (...) 关联），返回 productName → 产品完整信息 的映射。
     * 产品字段取 id/productName/productCode__c/description/kzkfg__c/enableStatus/unit。
     */
    private Map<String, JSONObject> queryProducts(Set<String> productNames) {
        Map<String, JSONObject> map = new HashMap<String, JSONObject>();
        if (productNames == null || productNames.isEmpty()) return map;
        List<String> names = new ArrayList<String>(productNames);
        long startAt = System.currentTimeMillis();
        for (int from = 0; from < names.size(); from += QUERY_CHUNK_SIZE) {
            int to = Math.min(from + QUERY_CHUNK_SIZE, names.size());
            String xoql = "SELECT id, productName, productCode__c, description, kzkfg__c, enableStatus, unit, weightUnit__c, "
                    + "matkl__c FROM product WHERE productName IN (" + quotedIn(names, from, to) + ")";
            try {
                QueryResult<JSONObject> qr = XoqlService.instance().query(xoql);
                appendProducts(qr, map);
            } catch (Exception e) {
                logger.error("PaQuery product query failed, batch=" + (from / QUERY_CHUNK_SIZE + 1)
                        + ", inputCount=" + (to - from), e);
            }
        }
        logger.info("PaQuery product query done, inputCount=" + names.size() + ", foundCount=" + map.size()
                + ", batchCount=" + ((names.size() + QUERY_CHUNK_SIZE - 1) / QUERY_CHUNK_SIZE)
                + ", costMs=" + (System.currentTimeMillis() - startAt));
        return map;
    }

    /**
     * 批量查询产品包装信息（productPack__c，按 product__c IN (...) + name IN (SALES_UNIT集合) 关联）。
     * 包装类型 name 等于 SAP 返回的 SALES_UNIT，一个产品只会命中一条。
     * 返回 "productId|包装类型name" → 包装信息（单条 JSONObject）的映射。
     * 注意：productPack__c 上没有 moq__c（最小订货量），该字段需去 productOrg__c 查（见 queryProductOrgs）。
     */
    private Map<String, JSONObject> queryProductPacks(Set<String> productIds, Set<String> salesUnits) {
        Map<String, JSONObject> map = new HashMap<String, JSONObject>();
        if (productIds == null || productIds.isEmpty() || salesUnits == null || salesUnits.isEmpty()) return map;
        List<String> ids = new ArrayList<String>(productIds);
        List<String> units = new ArrayList<String>(salesUnits);
        String unitIn = quotedIn(units, 0, units.size());
        long startAt = System.currentTimeMillis();
        for (int from = 0; from < ids.size(); from += QUERY_CHUNK_SIZE) {
            int to = Math.min(from + QUERY_CHUNK_SIZE, ids.size());
            String xoql = "SELECT id, name, product__c, packageQuantity__c, standardPackageQuantity__c, "
                    + "packageUnit__c, packageDescription__c FROM productPack__c WHERE product__c IN ("
                    + plainIn(ids, from, to) + ") AND name IN (" + unitIn + ")";
            try {
                QueryResult<JSONObject> qr = XoqlService.instance().query(xoql);
                if (qr != null && qr.getRecords() != null) {
                    for (Object record : qr.getRecords()) {
                        JSONObject row = (JSONObject) record;
                        String pid = getStr(row, "product__c");
                        String name = getStr(row, "name");
                        if (pid.isEmpty() || name.isEmpty()) continue;
                        map.put(pid + "|" + name, row);
                    }
                }
            } catch (Exception e) {
                logger.error("PaQuery pack query failed, batch=" + (from / QUERY_CHUNK_SIZE + 1)
                        + ", inputCount=" + (to - from), e);
            }
        }
        logger.info("PaQuery pack query done, productCount=" + ids.size() + ", salesUnitCount=" + units.size()
                + ", packCount=" + map.size() + ", batchCount="
                + ((ids.size() + QUERY_CHUNK_SIZE - 1) / QUERY_CHUNK_SIZE)
                + ", costMs=" + (System.currentTimeMillis() - startAt));
        return map;
    }

    /**
     * 批量查询产品销售组织（productOrg__c，按 product__c IN (...) 关联），用于取 moq__c（最小订货量）。
     * 一个产品可能有多条销售组织记录，注入时按产品销售单位（product.unit）匹配 salesUnit__c 取对应 moq__c。
     * 返回 productId → 产品销售组织信息 JSONArray 的映射。
     */
    private Map<String, JSONArray> queryProductOrgs(Set<String> productIds,String salesOrg) {
        Map<String, JSONArray> map = new HashMap<String, JSONArray>();
        if (productIds == null || productIds.isEmpty()) return map;
        List<String> ids = new ArrayList<String>(productIds);
        int orgCount = 0;
        long startAt = System.currentTimeMillis();
        for (int from = 0; from < ids.size(); from += QUERY_CHUNK_SIZE) {
            int to = Math.min(from + QUERY_CHUNK_SIZE, ids.size());
            String xoql = "SELECT id, product__c, salesUnit__c, moq__c, materialPriceGroup__c,productCategoryGroup__c FROM productOrg__c WHERE salesOrg__c.name='"+salesOrg+"' and product__c IN ("
                    + plainIn(ids, from, to) + ")";
            try {
                logger.info("productOrg Xoql:"+xoql);
                QueryResult<JSONObject> qr = XoqlService.instance().query(xoql);
                logger.info("productOrg qr:"+JSONObject.toJSONString(qr));
                orgCount += appendGroupedRows(qr, map, "product__c");
            } catch (Exception e) {
                logger.error("PaQuery productOrg query failed, batch=" + (from / QUERY_CHUNK_SIZE + 1)
                        + ", inputCount=" + (to - from), e);
            }
        }
        logger.info("PaQuery productOrg query done, productCount=" + ids.size() + ", orgProductCount=" + map.size()
                + ", orgCount=" + orgCount);
        return map;
    }

    /**
     * 批量查询 Family 名称（family1__c）：
     * 匹配键 = MPG（family1__c.mpg__c ← 产品销售组织 materialPriceGroup__c）
     *        + Product Line（family1__c.productLine__c ← 产品基本信息 Material Group matkl__c）。
     * 返回 "mpg|productLine" → Family 名称（family1__c.name）的映射。
     */
    private Map<String, String> queryFamilies(Map<String, String> mpgByPid, Map<String, String> matklByPid) {
        Map<String, String> map = new HashMap<String, String>();
        if (mpgByPid == null || mpgByPid.isEmpty() || matklByPid == null || matklByPid.isEmpty()) return map;
        Set<String> mpgs = new LinkedHashSet<String>();
        Set<String> lines = new LinkedHashSet<String>();
        for (Map.Entry<String, String> e : mpgByPid.entrySet()) {
            String matkl = matklByPid.get(e.getKey());
            if (matkl == null || matkl.isEmpty()) continue;
            mpgs.add(e.getValue());
            lines.add(matkl);
        }
        if (mpgs.isEmpty() || lines.isEmpty()) return map;
        long startAt = System.currentTimeMillis();
        String xoql = "SELECT id, name, mpg__c, productLine__c FROM family1__c WHERE mpg__c IN ("
                + quotedIn(new ArrayList<String>(mpgs), 0, mpgs.size()) + ") AND productLine__c IN ("
                + quotedIn(new ArrayList<String>(lines), 0, lines.size()) + ") LIMIT 0, 1000";
        try {
            QueryResult<JSONObject> qr = XoqlService.instance().query(xoql);
            if (qr != null && qr.getRecords() != null) {
                for (Object record : qr.getRecords()) {
                    JSONObject row = (JSONObject) record;
                    String mpg = getStr(row, "mpg__c");
                    String line = getStr(row, "productLine__c");
                    String name = getStr(row, "name");
                    if (mpg.isEmpty() || line.isEmpty() || name.isEmpty()) continue;
                    String key = mpg + "|" + line;
                    if (!map.containsKey(key)) map.put(key, name);
                }
            }
        } catch (Exception e) {
            logger.error("PaQuery family query failed, mpgCount=" + mpgs.size() + ", lineCount=" + lines.size(), e);
        }
        logger.info("PaQuery family query done, mpgCount=" + mpgs.size() + ", lineCount=" + lines.size()
                + ", familyCount=" + map.size() + ", costMs=" + (System.currentTimeMillis() - startAt));
        return map;
    }

    /**
     * 批量查询发运地点描述：shippingPoint__c（按 name IN (...) 关联）。
     * <p>SAP 返回的 {@code WAREHOUSELOCATIONNAME} 是发运地点<b>编码</b>（如 B111），
     * 业务侧要的是<b>描述</b>（如 SBMV Shipping point 1），故按 name 反查 shipToDesc__c。</p>
     *
     * @param codes SAP 返回的发运地点编码集合
     * @return name → shipToDesc__c 映射；同名多条时首条生效（按 id 升序，不覆盖）
     */
    private Map<String, String> queryShippingPoints(Set<String> codes) {
        Map<String, String> map = new HashMap<String, String>();
        if (codes == null || codes.isEmpty()) return map;
        List<String> names = new ArrayList<String>(codes);
        long startAt = System.currentTimeMillis();
        for (int from = 0; from < names.size(); from += QUERY_CHUNK_SIZE) {
            int to = Math.min(from + QUERY_CHUNK_SIZE, names.size());
            String xoql = "SELECT id, name, shipToDesc__c FROM shippingPoint__c WHERE name IN ("
                    + quotedIn(names, from, to) + ") LIMIT 0, 5000";
            try {
                QueryResult<JSONObject> qr = XoqlService.instance().query(xoql);
                int n = 0;
                if (qr != null && qr.getRecords() != null) {
                    for (Object record : qr.getRecords()) {
                        JSONObject row = (JSONObject) record;
                        String name = getStr(row, "name");
                        if (name.isEmpty()) continue;
                        String desc = getStr(row, "shipToDesc__c");
                        if (desc.isEmpty()) continue;
                        // 同名多条：首条生效，不覆盖（保持查询结果顺序，确定性优先）
                        if (!map.containsKey(name)) {
                            map.put(name, desc);
                            n++;
                        }
                    }
                }
                if (n > 0) {
                    logger.info("PaQuery shippingPoint batch=" + (from / QUERY_CHUNK_SIZE + 1)
                            + ", inputCount=" + (to - from) + ", newMapped=" + n
                            + ", totalMapped=" + map.size());
                }
            } catch (Exception e) {
                logger.error("PaQuery shippingPoint query failed, batch=" + (from / QUERY_CHUNK_SIZE + 1)
                        + ", inputCount=" + (to - from), e);
            }
        }
        logger.info("PaQuery shippingPoint query done, codeCount=" + names.size()
                + ", batchCount=" + ((names.size() + QUERY_CHUNK_SIZE - 1) / QUERY_CHUNK_SIZE)
                + ", shipToDescCount=" + map.size()
                + ", costMs=" + (System.currentTimeMillis() - startAt));
        // 未命中的编码打出来，便于发现 SAP 侧下发了 CRM 没有的发运地点
        if (map.size() < names.size()) {
            Set<String> missed = new LinkedHashSet<String>(names);
            missed.removeAll(map.keySet());
            logger.warn("PaQuery 有 " + missed.size() + " 个发运地点编码在 shippingPoint__c 中未匹配到描述: "
                    + abbreviate(missed.toString()));
        }
        return map;
    }

    /**
     * 递归遍历 JSON，把每个 {@code WAREHOUSELOCATIONNAME} 的值替换为对应的发运地点描述（shipToDesc__c）。
     * <p>处理规则：
     * <ul>
     *   <li>查到描述 → <b>原字段原地替换</b>为描述（保持字段名不变，前端不用改）</li>
     *   <li>查不到 → <b>原值保留</b>（编码本身仍有意义，不置空、不丢数据）</li>
     *   <li>描述为空（shippingPoint__c 未维护）→ 原值保留</li>
     * </ul>
     * 同时额外注入 {@code WAREHOUSELOCATIONNAMEDESC} 字段，保留原始编码供前端追溯。</p>
     *
     * @param node         SAP 响应任意节点（递归）
     * @param shipToDescMap 发运地点编码 → 描述
     */
    private void injectShippingPointDesc(Object node, Map<String, String> shipToDescMap) {
        if (shipToDescMap == null || shipToDescMap.isEmpty()) return;
        int[] replaced = new int[1];
        int[] kept = new int[1];
        walkAndReplaceShippingPoint(node, shipToDescMap, replaced, kept);
        logger.info("PaQuery 发运地点翻译完成: 已替换 " + replaced[0] + " 处, 保留原编码 " + kept[0] + " 处");
    }

    /** injectShippingPointDesc 的递归实现（replaced/kept 用数组做计数器） */
    private void walkAndReplaceShippingPoint(Object node, Map<String, String> shipToDescMap, int[] replaced, int[] kept) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            Object rawCode = obj.get(WAREHOUSE_LOCATION_NAME_FIELD);
            if (rawCode != null) {
                String code = rawCode.toString().trim();
                if (!code.isEmpty()) {
                    String desc = shipToDescMap.get(code);
                    if (desc != null && !desc.isEmpty()) {
                        // 保留原编码到派生字段，再把原字段替换为描述
                        obj.put("WAREHOUSELOCATIONNAMEDESC", code);
                        obj.put(WAREHOUSE_LOCATION_NAME_FIELD, desc);
                        replaced[0]++;
                    } else {
                        kept[0]++;
                    }
                }
            }
            for (Map.Entry<String, Object> entry : obj.entrySet()) {
                walkAndReplaceShippingPoint(entry.getValue(), shipToDescMap, replaced, kept);
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.size(); i++) {
                walkAndReplaceShippingPoint(arr.get(i), shipToDescMap, replaced, kept);
            }
        }
    }

    /** 日志截断，避免长集合刷屏 */
    private String abbreviate(String s) {
        if (s == null) return "null";
        if (s.length() <= 500) return s;
        return s.substring(0, 500) + "...(共" + s.length() + "字符，已截断)";
    }

    /**
     * 递归遍历 JSON，给每个含 CATALOGNUMBER 字段的 JSONObject 注入 productInfo
     * （对应产品的完整信息 + 按SALES_UNIT匹配的包装信息 packInfo（单条）
     *  + 匹配销售单位的 moq + MPG + Family 名称）。
     */
    private void injectProductInfo(Object node, InjectCtx ctx) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (obj.containsKey(CATALOG_NUMBER_FIELD)) {
                String cn = getStr(obj, CATALOG_NUMBER_FIELD);
                JSONObject product = ctx.productMap.get(cn);
                if (product == null) {
                    if (!cn.isEmpty()) ctx.stat.missingProductCount++;
                } else {
                    JSONObject productInfo = new JSONObject();
                    productInfo.putAll(product);
                    String pid = getStr(productInfo, "id");
                    String unit = getStr(product, "unit");

                    // 包装信息按 包装类型name = 当前行 SALES_UNIT 匹配，只取一条；
                    // 匹配不到时不设置 packInfo 并计入统计。
                    String salesUnit = getStr(obj, SALES_UNIT_FIELD);
                    JSONObject pack = (pid.isEmpty() || salesUnit.isEmpty())
                            ? null : ctx.packMap.get(pid + "|" + salesUnit);
                    if (pack == null) {
                        ctx.stat.packMissCount++;
                    } else {
                        // pack 是 packMap 里共享的同一个对象引用，两条相同产品会命中同一实例，
                        // fastjson 序列化引用检测会让第二条输出 $ref 指向第一条，
                        // 故这里深拷贝一份再放入，避免共享引用。
                        productInfo.put("packInfo", JSON.parseObject(pack.toJSONString()));
                    }

                    // moq__c（最小订货量）不在 productPack__c 上，改为查 productOrg__c，
                    // 按销售单位（salesUnit__c 匹配 product.unit）取对应记录的 moq__c。
                    JSONArray orgs = pid.isEmpty() ? null : ctx.orgMap.get(pid);
                    String moq = findOrgField(orgs, unit, "moq__c");
                    if (moq.isEmpty()) ctx.stat.moqMissingCount++;
                    productInfo.put("moq", moq);

                    // MPG（产品销售组织 materialPriceGroup__c，按销售单位匹配）
                    String mpg = ctx.mpgByPid.get(pid);
                    if (mpg == null) {
                        mpg = "";
                        ctx.stat.mpgMissingCount++;
                    }
                    productInfo.put("mpg", mpg);

                    // Family 名称：family1__c 按 mpg__c + productLine__c(产品 matkl__c) 匹配
                    String matkl = getStr(product, "matkl__c");
                    String family = "";
                    if (!mpg.isEmpty() && !matkl.isEmpty()) {
                        family = ctx.familyMap.get(mpg + "|" + matkl);
                        if (family == null) family = "";
                    }
                    if (family.isEmpty()) ctx.stat.familyMissCount++;
                    productInfo.put("family", family);

                    //预付款属性 productCategoryGroup__c
                    String pcg = findOrgField(orgs, unit, "productCategoryGroup__c");
                    productInfo.put("productCategoryGroup__c", pcg);

                    obj.put("productInfo", productInfo);
                    ctx.stat.injectedCount++;
                }
            }
            for (Map.Entry<String, Object> entry : obj.entrySet()) {
                if (!"productInfo".equals(entry.getKey())) injectProductInfo(entry.getValue(), ctx);
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.size(); i++) injectProductInfo(arr.get(i), ctx);
        }
    }

    /**
     * 在产品销售组织记录中，按销售单位（salesUnit__c）匹配 unit，取匹配记录上指定字段的值；
     * 匹配不到时退回任意一条非空值；仍无则返回空字符串。
     */
    private String findOrgField(JSONArray orgs, String unit, String field) {
        if (orgs == null || orgs.isEmpty()) return "";
        String fallback = "";
        for (int i = 0; i < orgs.size(); i++) {
            JSONObject org = orgs.getJSONObject(i);
            String v = getStr(org, field);
            if (v.isEmpty()) continue;
            if (fallback.isEmpty()) fallback = v;
            if (!unit.isEmpty() && unit.equalsIgnoreCase(getStr(org, "salesUnit__c"))) return v;
        }
        return fallback;
    }

    private boolean isMyse01Failed(JSONObject sapResult) {
        JSONObject result = sapResult == null ? null : sapResult.getJSONObject("RESULT");
        return result != null && !"S".equals(result.getString("RETURNCODE"));
    }

    private Set<String> collectProductIds(Map<String, JSONObject> productMap) {
        Set<String> ids = new LinkedHashSet<String>();
        for (JSONObject product : productMap.values()) {
            String id = getStr(product, "id");
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    private String quotedIn(List<String> values, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (out.length() > 0) out.append(",");
            out.append("'").append(values.get(i).replace("'", "''")).append("'");
        }
        return out.toString();
    }

    private String plainIn(List<String> values, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (out.length() > 0) out.append(",");
            out.append(values.get(i));
        }
        return out.toString();
    }

    private void appendProducts(QueryResult<JSONObject> queryResult, Map<String, JSONObject> products) {
        if (queryResult == null || queryResult.getRecords() == null) return;
        for (Object record : queryResult.getRecords()) {
            JSONObject product = (JSONObject) record;
            String name = getStr(product, "productName");
            if (!name.isEmpty()) products.put(name, product);
        }
    }

    /** 通用：把查询结果按 groupField 的值分组放入 JSONArray，返回处理的记录数。 */
    private int appendGroupedRows(QueryResult<JSONObject> queryResult, Map<String, JSONArray> groups, String groupField) {
        if (queryResult == null || queryResult.getRecords() == null) return 0;
        int count = 0;
        for (Object record : queryResult.getRecords()) {
            JSONObject row = (JSONObject) record;
            String groupKey = getStr(row, groupField);
            if (groupKey.isEmpty()) continue;
            JSONArray list = groups.get(groupKey);
            if (list == null) {
                list = new JSONArray();
                groups.put(groupKey, list);
            }
            list.add(row);
            count++;
        }
        return count;
    }

    private String getStr(JSONObject r, String key) {
        Object v = r == null ? null : r.get(key);
        return v == null ? "" : v.toString().trim();
    }

    private static class InjectStat {
        private int injectedCount;
        private int missingProductCount;
        private int packMissCount;
        private int moqMissingCount;
        private int mpgMissingCount;
        private int familyMissCount;
    }

    /** 注入上下文：把遍历过程中需要的查询映射与统计打包，避免方法参数过长。 */
    private static class InjectCtx {
        private final Map<String, JSONObject> productMap;
        private final Map<String, JSONObject> packMap;
        private final Map<String, JSONArray> orgMap;
        /** 产品 id → MPG（产品销售组织 materialPriceGroup__c，按销售单位匹配） */
        private final Map<String, String> mpgByPid;
        /** "mpg|productLine" → Family 名称 */
        private final Map<String, String> familyMap;
        private final InjectStat stat;

        InjectCtx(Map<String, JSONObject> productMap, Map<String, JSONObject> packMap,
                  Map<String, JSONArray> orgMap, Map<String, String> mpgByPid,
                  Map<String, String> familyMap, InjectStat stat) {
            this.productMap = productMap;
            this.packMap = packMap;
            this.orgMap = orgMap;
            this.mpgByPid = mpgByPid;
            this.familyMap = familyMap;
            this.stat = stat;
        }
    }
}
