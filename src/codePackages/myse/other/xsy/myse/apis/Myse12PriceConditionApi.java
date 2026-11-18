package other.xsy.myse.apis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.exception.CustomConfigException;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.BatchOperateResult;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.CustomConfigService;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;
import com.rkhd.platform.sdk.data.model.ProductPrice__c;
import other.xsy.myse.utils.HttpUtil;
import static other.xsy.myse.utils.JsonValues.normalizeToArray;

import static other.xsy.myse.utils.JsonValues.trimToNull;
import static other.xsy.myse.utils.JsonValues.asLongValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/**
 * MYSE-12 价格主数据推送
 * <p>
 * 接收 SAP IDoc（Message type: ZGCOND_A）推送价格条件主数据。
 * 方向：SAP → mySE（被动接收）
 * </p>
 *
 * <p><b>销售易 API 路径</b>：/rest/data/v2.0/scripts/api/myse/master-data/price-condition</p>
 */
@RestApi(baseUrl = "/myse")
public class Myse12PriceConditionApi {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 日志标识 */
    private static final String TAG = "MYSE-12";

    /** 批量写入/IN 预查询单批大小 */
    private static final int WRITE_BATCH_SIZE = 200;
    private static final int QUERY_BATCH_SIZE = 100;

    // productPrice__c 业务类型 ID（沙盒，来自元数据 busiTypeId）
    private static final Long PRICE_BUSI_TYPE ;

    static {
        try {
            // 获取对象实例
            CustomConfigService instance = CustomConfigService.instance();
            // 获取配置集,返回此配置集中的所有配置项
            Map<String, String> configSet = instance.getConfigSet("entityType");
            PRICE_BUSI_TYPE=Long.parseLong(configSet.get("PRICE_BUSI_TYPE"));
        } catch (CustomConfigException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * condTable__c 是单选字段（itemType=3），SAP 的 COND_TABLE 传的是选项 label（如 505/521），
     * 落库需转为选项 code（505->1, 521->2）。若平台后续新增选项，需同步更新本映射。
     */
    private static final Map<String, Integer> COND_TABLE_OPTION_MAP = new HashMap<String, Integer>() {{
        put("504", 1);
        put("521", 2);
    }};

    @RestMapping(value = "/master-data/price-condition", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        try {
            if (body == null || body.isEmpty()) {
                logger.warn(TAG + " 请求体为空，忽略");
                return HttpUtil.success(null).toJSONString();
            }
            JSONObject requestBody = JSON.parseObject(body);

            // CONDITIONS 根节点兼容 单对象（历史报文）/ 数组（批量推送）
            Object conditionsNode = requestBody.get("CONDITIONS");
            JSONArray conditionsArr = normalizeToArray(conditionsNode);
            if (conditionsArr == null || conditionsArr.isEmpty()) {
                logger.warn(TAG + " CONDITIONS 根节点为空，忽略");
                return HttpUtil.success(null).toJSONString();
            }

            // 入参日志：单条打全量，批量只打条数（避免日志膨胀）
            if (conditionsNode instanceof JSONObject) {
                logger.info(TAG + " 入参(单条): " + requestBody.toJSONString());
            } else {
                logger.info(TAG + " 入参(批量): 条数=" + conditionsArr.size());
            }

            // ====== 预收集业务键 ======
            // idocs 仅用于日志/写入 idoc__c 字段（不再是唯一键），预查询靠 materialNos
            Set<String> materialNos = new HashSet<>();
            for (int i = 0; i < conditionsArr.size(); i++) {
                JSONObject c = conditionsArr.getJSONObject(i);
                if (c == null) {
                    continue;
                }
                String mn = trimToNull(c.getString("MATERIAL_NO"));
                if (mn != null) {
                    materialNos.add(mn);
                }
            }

            // ====== 一次 IN 批量预查询（循环内零反查 SQL）======
            // 唯一键 = 订货号(catelogNumber__c) + 价格表名(condTable__c)，不再用 idoc__c
            Map<String, Long> productIdMap = queryProductIds(materialNos);
            Set<Long> productIds = new LinkedHashSet<>(productIdMap.values());
            Map<String, ExistingPrice> existingPriceMap = queryExistingPrices(productIds);

            // ====== 逐条构建（不写库），失败只记 log ======
            List<ProductPrice__c> toInsert = new ArrayList<>();
            List<ProductPrice__c> toUpdate = new ArrayList<>();
            Set<String> pendingNewKeys = new HashSet<>();
            int failCount = 0;
            for (int i = 0; i < conditionsArr.size(); i++) {
                JSONObject conditions = conditionsArr.getJSONObject(i);
                String idocNumber = conditions == null ? null : conditions.getString("IDOC_NUMBER");
                try {
                    if (conditions == null) {
                        throw new RuntimeException("CONDITIONS 数组元素为空");
                    }
                    // 同批重复(订货号+价格表名)：已构建过新增的不再重复插入（防重复键）
                    String dupKey = buildPriceKey(conditions, productIdMap);
                    if (dupKey != null && !pendingNewKeys.add(dupKey)) {
                        logger.warn(TAG + " 同批重复(订货号+价格表名)，跳过: key=" + dupKey
                                + " idoc=" + idocNumber);
                        continue;
                    }
                    ProductPrice__c pp = buildPrice(conditions, existingPriceMap, productIdMap);
                    if (pp.getId() != null) {
                        toUpdate.add(pp);
                    } else {
                        toInsert.add(pp);
                    }
                } catch (Exception e) {
                    failCount++;
                    logger.error(TAG + " 价格处理失败 idoc=" + idocNumber + " err=" + e.getMessage(), e);
                }
            }

            // ====== 批量写入（先 insert 后 update，分批 200）======
            int insertFail = batchWrite(toInsert, true);
            int updateFail = batchWrite(toUpdate, false);
            failCount += insertFail + updateFail;

            logger.info(TAG + " 推送完成: total=" + conditionsArr.size()
                    + " success=" + (conditionsArr.size() - failCount) + " fail=" + failCount);
            return HttpUtil.success(null).toJSONString();

        } catch (Exception e) {
            logger.error(TAG + " 价格主数据处理异常", e);
            return HttpUtil.success(null).toJSONString();
        }
    }

    /**
     * 按单条 CONDITIONS 构建 ProductPrice__c（不写库）。
     * <p><b>唯一键 = 订货号(catelogNumber__c) + 价格表名(condTable__c)</b>：
     * 组合命中已有记录 → setId 走更新；未命中 → 新建。
     * 订货号或价格表名取不到 → 抛异常（该条跳过，不写库）。</p>
     */
    private ProductPrice__c buildPrice(JSONObject conditions, Map<String, ExistingPrice> existingPriceMap,
                                       Map<String, Long> productIdMap) {
        String idocNumber = trimToNull(conditions.getString("IDOC_NUMBER"));
        if (idocNumber == null) {
            throw new RuntimeException("IDOC_NUMBER 不能为空");
        }

        Long sysChgDate = conditions.getLong("SYS_CHG_DATE");
        Long sysChgTime = conditions.getLong("SYS_CHG_TIME");
        Integer condTable = conditions.getInteger("COND_TABLE");
        String condType = conditions.getString("COND_TYPE");
        String materialNo = trimToNull(conditions.getString("MATERIAL_NO"));
        String companyCode = conditions.getString("COMPANY_CODE");

        // ====== CONDITION_INFO 嵌套对象 ======
        JSONObject conditionInfo = conditions.getJSONObject("CONDITION_INFO");
        if (conditionInfo == null) {
            throw new RuntimeException("CONDITION_INFO 不能为空 idoc=" + idocNumber);
        }
        Long validFr = conditionInfo.getLong("VALID_FR");
        Long validTo = conditionInfo.getLong("VALID_TO");
        JSONObject condition = conditionInfo.getJSONObject("CONDITION");
        if (condition == null) {
            throw new RuntimeException("CONDITION 不能为空 idoc=" + idocNumber);
        }
        Double rate = condition.getDouble("RATE");
        String rateUnit = condition.getString("RATE_UNIT");
        Integer condPrcUnitInt = condition.getInteger("COND_PRC_UNIT");
        String condUnit = condition.getString("COND_UNIT");
        // COND_PRC_UNIT：JSON 中是整数(1)，模型类是 String
        String condPrcUnit = condPrcUnitInt != null ? String.valueOf(condPrcUnitInt) : null;

        // ====== 物料号 → 产品 id（预查询 map，不再逐条查）======
        Long productId = materialNo == null ? null : productIdMap.get(materialNo);
        if (materialNo != null && productId == null) {
            logger.warn(TAG + " 未找到物料号对应的产品: materialNo=" + materialNo + "，catelogNumber__c 将留空");
        }

        // ====== 价格表名：SAP COND_TABLE label（505/521）→ 选项 code（1/2）======
        Integer condTableCode = null;
        if (condTable != null) {
            condTableCode = COND_TABLE_OPTION_MAP.get(String.valueOf(condTable));
            if (condTableCode == null) {
                logger.warn(TAG + " 未知的 COND_TABLE 值，未映射到选项（将留空）: " + condTable);
            }
        }

        // ====== 唯一键 = 订货号(catelogNumber__c) + 价格表名(condTable__c) ======
        // 组不成唯一键 → 报错跳过（老板 2026-10-22 定：不退回 idoc 判断，也不当新增写入）
        if (productId == null) {
            throw new RuntimeException("订货号为空（物料号未匹配到产品，无法组成唯一键）materialNo=" + materialNo
                    + " idoc=" + idocNumber);
        }
        if (condTableCode == null) {
            throw new RuntimeException("价格表名为空（COND_TABLE=" + condTable
                    + " 未映射到选项，无法组成唯一键）idoc=" + idocNumber);
        }
        String key = priceKey(productId, condTableCode);
        ExistingPrice existing = existingPriceMap.get(key);
        Long existingId = existing == null ? null : existing.id;

        ProductPrice__c pp = new ProductPrice__c();
        if (existingId != null) {
            pp.setId(existingId);
        } else {
            pp.setEntityType(PRICE_BUSI_TYPE);
        }

        // ====== 赋值 ======
        pp.setIdoc__c(idocNumber);
        pp.setSysChgDate__c(sysChgDate);
        pp.setSysChgTime__c(sysChgTime);
        pp.setCondTable__c(condTableCode);
        pp.setCondType__c(condType);
        // companyCode__c / account__c 当前留空（SAP 入参未传）
        pp.setCompanyCode__c(companyCode);
        pp.setCatelogNumber__c(productId);
        pp.setValidFr__c(validFr);
        pp.setValidTo__c(validTo);
        pp.setPrice__c(rate);
        pp.setRateUnit__c(rateUnit);
        pp.setCondPrcUnit__c(condPrcUnit);
        pp.setCondUnit__c(condUnit);
        return pp;
    }

    /**
     * 批量写入（分批 200）。返回失败条数；失败单条记 log（批量结果按输入顺序一一对应）。
     */
    private int batchWrite(List<ProductPrice__c> objs, boolean isInsert) {
        String opName = isInsert ? "insert" : "update";
        int fail = 0;
        if (objs.isEmpty()) {
            return fail;
        }
        for (int from = 0; from < objs.size(); from += WRITE_BATCH_SIZE) {
            List<ProductPrice__c> part = objs.subList(from, Math.min(from + WRITE_BATCH_SIZE, objs.size()));
            BatchOperateResult br;
            try {
                br = isInsert
                        ? XObjectService.instance().insert(part, true)
                        : XObjectService.instance().update(part);
            } catch (Exception e) {
                logger.error(TAG + " 批量" + opName + "异常 from=" + from + " 条数=" + part.size() + " err=" + e.getMessage(), e);
                fail += part.size();
                continue;
            }
            boolean batchOk = br != null && Boolean.TRUE.equals(br.getSuccess());
            List<OperateResult> ops = br == null ? null : br.getOperateResults();
            int ok = 0;
            for (int k = 0; k < part.size(); k++) {
                OperateResult r = (ops != null && k < ops.size()) ? ops.get(k) : null;
                if (batchOk && r != null && Boolean.TRUE.equals(r.getSuccess())) {
                    ok++;
                } else {
                    String err = !batchOk
                            ? (br == null ? "批量" + opName + "返回 null" : br.getErrorMessage())
                            : (r == null ? "单条结果缺失" : r.getErrorMessage());
                    logger.error(TAG + " " + opName + "失败 idoc=" + part.get(k).getIdoc__c() + " err=" + err);
                }
            }
            fail += part.size() - ok;
            logger.info(TAG + " 批量" + opName + " 成功=" + ok + "/" + part.size());
        }
        return fail;
    }

    /**
     * 批量预查询已有价格记录 id（<b>按 catelogNumber__c + condTable__c 组合唯一</b>，分批 IN（100/批)）。
     *
     * <p><b>为什么不用 idoc__c 判唯一</b>（老板 2026-10-22 定）：同一条价格条件可能由多个 IDOC 下发，
     * idoc 不代表业务唯一键；真正唯一的是「订货号 + 价格表名」。</p>
     *
     * <p><b>查询方式</b>：先按 {@code catelogNumber__c IN (订货号 id 列表)} 拉候选，
     * 再在 Java 层按 (订货号, 价格表名) 组合匹配 —— 组合条件用 OR 拼会非常长。</p>
     *
     * <p><b>⚠️ 两个 XOQL 口径</b>：</p>
     * <ul>
     *   <li>{@code catelogNumber__c} 是 <b>reference</b>（→ product），过滤用<b>记录 id 且不加引号</b>；</li>
     *   <li>{@code condTable__c} 是 <b>picklist</b>，SELECT 出来是<b>选项 label</b>（如 "521"），
     *       需用 {@link #COND_TABLE_OPTION_MAP} 反查成选项 code 才能和入库值对齐。</li>
     * </ul>
     *
     * <p><b>同组合多条时取 updatedAt 最新的那条</b>（老板 2026-10-22 定），其余不动并 warn。</p>
     *
     * @return 组合键 → 已有记录信息；无记录的组合不在结果中
     */
    private Map<String, ExistingPrice> queryExistingPrices(Set<Long> productIds) {
        Map<String, ExistingPrice> map = new HashMap<>();
        if (productIds == null || productIds.isEmpty()) {
            return map;
        }
        List<Long> list = new ArrayList<>(productIds);
        for (int from = 0; from < list.size(); from += QUERY_BATCH_SIZE) {
            List<Long> part = list.subList(from, Math.min(from + QUERY_BATCH_SIZE, list.size()));
            String xoql = "SELECT id, catelogNumber__c, condTable__c, updatedAt FROM productPrice__c"
                    + " WHERE catelogNumber__c IN (" + buildInClauseIds(part) + ")";
            logger.info(TAG + " SQL: " + xoql);
            try {
                QueryResult<JSONObject> qr = XoqlService.instance().query(xoql);
                int hit = 0;
                if (qr != null && qr.getRecords() != null) {
                    hit = qr.getRecords().size();
                    for (Object o : qr.getRecords()) {
                        JSONObject j = (JSONObject) o;
                        Long cat = asLongValue(j.get("catelogNumber__c"));
                        Integer condCode = toCondTableCode(j.get("condTable__c"));
                        if (cat == null || condCode == null) {
                            // 订货号或价格表名为空 → 组不成唯一键，不参与匹配
                            continue;
                        }
                        String key = priceKey(cat, condCode);
                        Long id = asLongValue(j.get("id"));
                        if (id == null) {
                            continue;
                        }
                        Long upd = asLongValue(j.get("updatedAt"));
                        long updatedAt = upd == null ? 0L : upd;
                        ExistingPrice cur = map.get(key);
                        if (cur == null) {
                            map.put(key, new ExistingPrice(id, updatedAt, cat, condCode));
                        } else {
                            // 同组合多条：取 updatedAt 最新的（老板 2026-10-22 定），其余不动
                            cur.dupCount++;
                            if (updatedAt > cur.updatedAt) {
                                cur.id = id;
                                cur.updatedAt = updatedAt;
                            }
                            logger.warn(TAG + " 同一订货号+价格表名命中多条已有记录，取 updatedAt 最新的一条: key=" + key
                                    + ", 候选 id=" + id + " updatedAt=" + updatedAt
                                    + "（历史重复数据，共 " + cur.dupCount + " 条）");
                        }
                    }
                }
                logger.info(TAG + " 价格记录预查询(订货号+价格表名): 入参订货号=" + part.size()
                        + " 命中记录=" + hit + " 组合数=" + map.size());
            } catch (Exception e) {
                logger.error(TAG + " 预查询已有价格记录失败: " + e.getMessage(), e);
            }
        }
        return map;
    }

    /**
     * 组合键：订货号 id + 价格表名选项 code，形如 {@code 4481847821682722|2}。
     * <p>用 {@code |} 分隔，避免直接拼接产生歧义。</p>
     */
    private String priceKey(Long productId, Integer condTableCode) {
        return productId + "|" + condTableCode;
    }

    /**
     * 由报文体算出唯一键（订货号 + 价格表名）。
     * <p>与 {@link #buildPrice} 内部的算法保持一致：订货号取「物料号 → product id」，
     * 价格表名取「SAP COND_TABLE label → 选项 code」。</p>
     *
     * @return 组合键；订货号或价格表名任一取不到时返回 {@code null}
     *         （表示组不成唯一键，由 buildPrice 抛异常跳过）
     */
    private String buildPriceKey(JSONObject conditions, Map<String, Long> productIdMap) {
        if (conditions == null) {
            return null;
        }
        String materialNo = trimToNull(conditions.getString("MATERIAL_NO"));
        Long productId = materialNo == null ? null : productIdMap.get(materialNo);
        Integer condTable = conditions.getInteger("COND_TABLE");
        Integer condTableCode = condTable == null ? null
                : COND_TABLE_OPTION_MAP.get(String.valueOf(condTable));
        if (productId == null || condTableCode == null) {
            return null;
        }
        return priceKey(productId, condTableCode);
    }

    /**
     * 构建数字 IN 子句，如 {@code (1,2,3)}。
     * <p>reference 字段（catelogNumber__c）比对的是记录 id，<b>不能加引号</b>，
     * 也不能用 {@link #buildInClause(List)}（那个是给 text 字段加引号的）。</p>
     */
    private String buildInClauseIds(List<Long> values) {
        StringBuilder sb = new StringBuilder();
        for (Long v : values) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(v);
        }
        return sb.toString();
    }

    /**
     * 把 XOQL 返回的值转成 Long（数字 / 数字字符串 / {id:...} 对象），转不了返回 null。
     */

    /**
     * 把 XOQL 返回的 condTable__c 转成选项 code。
     * <p>picklist 字段 SELECT 出来是<b>选项 label</b>（"504"/"521"），
     * 用 {@link #COND_TABLE_OPTION_MAP}（label→code）反查；查不到返回 null。</p>
     */
    private Integer toCondTableCode(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            return a.isEmpty() ? null : toCondTableCode(a.get(0));
        }
        if (v instanceof Number) {
            // 已是选项 code
            return ((Number) v).intValue();
        }
        if (v instanceof JSONObject) {
            JSONObject j = (JSONObject) v;
            Object inner = j.get("value");
            if (inner == null) {
                inner = j.get("id");
            }
            return inner == null ? null : toCondTableCode(inner);
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        // 先按 label 反查（XOQL 正常返回形态）
        Integer code = COND_TABLE_OPTION_MAP.get(s);
        if (code != null) {
            return code;
        }
        // 兜底：值本身就是选项 code
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 已有价格记录的信息（用于组合键匹配后决定 update 哪一条） */
    private static class ExistingPrice {
        Long id;
        long updatedAt;
        final Long productId;
        final Integer condTableCode;
        /** 同一组合命中的记录条数（>1 说明历史数据有重复） */
        int dupCount;

        ExistingPrice(Long id, long updatedAt, Long productId, Integer condTableCode) {
            this.id = id;
            this.updatedAt = updatedAt;
            this.productId = productId;
            this.condTableCode = condTableCode;
        }
    }

    /**
     * 批量预查询产品 id（productCode__c → id），分批 IN（100/批）。
     */
    private Map<String, Long> queryProductIds(Set<String> materialNos) {
        Map<String, Long> map = new HashMap<>();
        if (materialNos.isEmpty()) {
            return map;
        }
        List<String> list = new ArrayList<>(materialNos);
        for (int from = 0; from < list.size(); from += QUERY_BATCH_SIZE) {
            List<String> part = list.subList(from, Math.min(from + QUERY_BATCH_SIZE, list.size()));
            String xoql = "SELECT id, productCode__c FROM product WHERE productCode__c IN (" + buildInClause(part) + ")";
            logger.info(TAG + " SQL: " + xoql);
            try {
                QueryResult<JSONObject> qr = XoqlService.instance().query(xoql);
                if (qr != null && qr.getRecords() != null) {
                    for (Object o : qr.getRecords()) {
                        JSONObject j = (JSONObject) o;
                        String code = j.getString("productCode__c");
                        Object id = j.get("id");
                        if (code != null && id != null) {
                            map.put(code, Long.parseLong(id.toString()));
                        }
                    }
                }
                logger.info(TAG + " 产品预查询: 入参=" + part.size() + " 命中=" + map.size());
            } catch (Exception e) {
                logger.error(TAG + " 预查询产品 id 失败: " + e.getMessage(), e);
            }
        }
        return map;
    }

    // ===================== 工具方法 =====================

    /** 构建 IN ('a','b') 子句（元素转义单引号）。 */
    private String buildInClause(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append("'").append(v.replace("'", "''")).append("'");
        }
        return sb.toString();
    }
}
