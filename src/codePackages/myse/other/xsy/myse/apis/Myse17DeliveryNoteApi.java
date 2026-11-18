package other.xsy.myse.apis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.data.model.Delivery__c;
import com.rkhd.platform.sdk.data.model.DeliveryDetail__c;
import com.rkhd.platform.sdk.data.model.OrderDetail__c;
import com.rkhd.platform.sdk.data.model.PackingDetail__c;
import com.rkhd.platform.sdk.exception.ApiEntityServiceException;
import com.rkhd.platform.sdk.exception.CustomConfigException;
import com.rkhd.platform.sdk.http.RkhdHttpClient;
import com.rkhd.platform.sdk.http.RkhdHttpData;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.BatchOperateResult;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.model.XObject;
import com.rkhd.platform.sdk.service.CustomConfigService;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import other.xsy.myse.utils.BatchOperateUtils;
import other.xsy.myse.utils.HttpUtil;
import other.xsy.myse.utils.JsonValues;
import static other.xsy.myse.utils.JsonValues.getItems;
import other.xsy.myse.utils.QueryUtil;

/**
 * Myse17 —— SAP 交货单（发货单）入站接口。
 *
 * <p><b>接口</b>：{@code POST /rest/data/v2.0/scripts/api/myse/document/delivery-note}</p>
 *
 * <p><b>落库三个对象</b>（delivery__c 为父，另两个为其子明细）：</p>
 * <ul>
 *   <li>{@code delivery__c}       发货单</li>
 *   <li>{@code deliveryDetail__c} 发货单明细</li>
 *   <li>{@code packingDetail__c}  装箱单明细</li>
 * </ul>
 *
 * <p><b>处理流程</b>（四阶段，查询与写入次数都与单据数/行数解耦）：</p>
 * <pre>
 *   阶段 0  拆单据容器      兼容老格式（root 自身）与批量格式（T_OUTPUT.item[]）
 *   阶段 A  批量预取        prefetch() → RefCache（整批只查一次库）
 *   阶段 B  解析            prepare() → PreparedDelivery（只构造对象，不落库）
 *   阶段 C  批量落库        batchUpsertMain / batchSyncDetails / batchDeleteInvoices
 *   阶段 D  组装出参        顶层保留首条字段（兼容老前端）+ results[] + errors[]
 * </pre>
 *
 * <p>本类为单文件实现，所有 Myse17 专属逻辑（查询反查、批量预取、报文解析、
 * 批量落库）全部收拢在此，内部静态嵌套类只承载<b>纯数据/配置</b>（缓存、解析产物、
 * 业务类型），通用工具（批量写分组、JSON 取值、选项集描述）仍复用 utils 包。</p>
 */
@RestApi(baseUrl = "/myse")
public class Myse17DeliveryNoteApi {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 平台单次响应硬上限 300，超出会静默截断 → 查询必须分页 */
    private static final int PAGE_LIMIT = 300;
    /** 分页取全的最大总行数保护 */
    private static final int MAX_FETCH_ROWS = 500000;
    /** 批量 IN 查询 / 批量写入的分批大小 */
    private static final int BATCH_SIZE = 200;

    // ======================================================================
    // 入口
    // ======================================================================

    @RestMapping(value = "/document/delivery-note", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        try {
            // ===== 阶段 0：拆单据容器（兼容两种报文格式）=====
            JSONObject root = JSON.parseObject(body);
            List<JSONObject> containers = splitContainers(root);
            if (containers.isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "交货单号(E_DOCNUMBER)不能为空").toJSONString();
            }

            // ===== 阶段 A：批量预取（整批只查一次库）=====
            RefCache cache = prefetch(containers);

            // ===== 阶段 B：解析（只构造对象，不落库）=====
            // 选项集描述整批只查一次（原来每单调一次）
            Map<String, Map<String, Integer>> picklistMap =
                    QueryUtil.getPicklistDescriptionApiKeyToValue("delivery__c");
            List<PreparedDelivery> prepared = new ArrayList<>();
            for (JSONObject container : containers) {
                PreparedDelivery one = prepareDelivery(container, cache, picklistMap);
                if (one != null) {
                    prepared.add(one);
                }
            }

            // ===== 阶段 C：批量落库 =====
            List<String> errors = new ArrayList<>();
            batchUpsertMain(prepared, cache);
            batchSyncDetails(prepared, cache, errors);
            batchDeleteInvoices(prepared, cache, errors);
            // 批次末尾统一批量重算受影响的订单明细「已发货数量」
            refreshOrderShippedQuantity(cache.affectedOrderDetailIds);

            // ===== 阶段 D：组装结果 =====
            JSONArray results = new JSONArray();
            JSONObject firstResult = null;
            for (PreparedDelivery p : prepared) {
                results.add(p.result);
                if (firstResult == null) {
                    firstResult = p.result;
                }
            }
            if (results.isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "交货单号(E_DOCNUMBER)不能为空").toJSONString();
            }

            JSONObject data = new JSONObject();
            if (firstResult != null) {
                // 顶层保留第一条的结果字段（向后兼容：老前端单条场景直接读顶层）
                for (String key : firstResult.keySet()) {
                    data.put(key, firstResult.get(key));
                }
            }
            data.put("total", results.size());
            data.put("results", results);
            // 批量落库的失败原因随出参返回（之前只能翻平台日志才知道明细没进去）
            if (!errors.isEmpty()) {
                data.put("errors", errors);
                logger.error("MYSE-17 批量落库存在失败 " + errors.size() + " 条: " + errors);
            }
            logger.info("MYSE-17 批量处理完成: 单据数=" + results.size() + ", 失败=" + errors.size());
            return HttpUtil.success(data).toJSONString();

        } catch (Exception e) {
            logger.error("MYSE-17 推送交货单处理异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    /**
     * 拆出单据容器。
     *
     * <ul>
     *   <li>老格式：{@code root} 自身就是一张单据；</li>
     *   <li>批量格式：{@code root.T_OUTPUT.item[]} 每个元素是一张完整单据，自带控制字段。</li>
     * </ul>
     */
    private List<JSONObject> splitContainers(JSONObject root) {
        List<JSONObject> containers = new ArrayList<>();
        JSONArray outputItems = getItems(root, "T_OUTPUT");
        if (outputItems != null && !outputItems.isEmpty()) {
            for (int i = 0; i < outputItems.size(); i++) {
                JSONObject one = outputItems.getJSONObject(i);
                if (one == null) {
                    logger.warn("MYSE-17 T_OUTPUT 第 " + i + " 条为空，跳过");
                    continue;
                }
                containers.add(one);
            }
            logger.info("MYSE-17 报文格式: T_OUTPUT 批量包装, 单据数=" + containers.size());
        } else {
            containers.add(root);
        }
        return containers;
    }

    // ======================================================================
    // 阶段 A：批量预取
    // ======================================================================

    /**
     * 预取整批报文所需的全部关联 id（每类对象一次 IN 查询，分批执行）。
     *
     * <p>失败时置 {@code cache.disabled=true}，调用方自动退化为逐条实时查询，功能不挂。</p>
     */
    private RefCache prefetch(List<JSONObject> containers) {
        RefCache cache = new RefCache();
        Set<String> dnNumbers = new LinkedHashSet<>();
        Set<String> vkorgs = new LinkedHashSet<>();
        Set<String> kunrgs = new LinkedHashSet<>();
        List<String> regionCodes = new ArrayList<>();
        Set<String> matNos = new LinkedHashSet<>();
        Set<String> orderNos = new LinkedHashSet<>();
        Set<String> packLineNos = new LinkedHashSet<>();

        // ===== 1. 扫描整批报文，收集所有待反查的 key =====
        for (JSONObject container : containers) {
            String dn = JsonValues.trimToNull(JsonValues.asString(container, "E_DOCNUMBER"));
            if (dn != null) {
                dnNumbers.add(dn.trim());
            }
            JSONObject hdr = container.getJSONObject("T_DELIVERY_HDR");
            if (hdr != null) {
                String v = JsonValues.trimToNull(JsonValues.asString(hdr, "VKORG"));
                if (v != null) {
                    vkorgs.add(v.trim());
                }
                // 客户号：原值 + 去前导零两种形式都查
                String kunnr = JsonValues.trimToNull(JsonValues.asString(hdr, "KUNNR"));
                if (kunnr != null) {
                    kunrgs.add(kunnr.trim());
                    String stripped = kunnr.trim().replaceFirst("^0+", "");
                    if (!stripped.isEmpty() && !stripped.equals(kunnr.trim())) {
                        kunrgs.add(stripped);
                    }
                }
                // 省份代码：原值 / 去零 / 左补零 3 位都查
                String region = JsonValues.trimToNull(JsonValues.asString(hdr, "REGION"));
                if (region != null) {
                    addCandidate(regionCodes, region.trim());
                    String stripped = region.trim().replaceFirst("^0+", "");
                    addCandidate(regionCodes, stripped);
                    addCandidate(regionCodes, padProvinceCode(region.trim()));
                    addCandidate(regionCodes, padProvinceCode(stripped));
                }
            }
            JSONArray lines = getItems(container, "T_DELIVERY_LINE");
            if (lines != null) {
                for (int i = 0; i < lines.size(); i++) {
                    JSONObject item = lines.getJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String m = JsonValues.trimToNull(JsonValues.asString(item, "CATALOGNUMBER"));
                    if (m != null) {
                        matNos.add(m.trim());
                    }
                    String o = JsonValues.trimToNull(JsonValues.asString(item, "ORDERNUMBER"));
                    if (o != null) {
                        orderNos.add(o.trim());
                    }
                }
            }
            JSONArray packs = getItems(container, "T_PACKINGLIST");
            if (packs != null) {
                for (int i = 0; i < packs.size(); i++) {
                    JSONObject item = packs.getJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String m = JsonValues.trimToNull(JsonValues.asString(item, "CATALOGNUMBER"));
                    if (m != null) {
                        matNos.add(m.trim());
                    }
                    String ln = JsonValues.trimToNull(JsonValues.asString(item, "LINENUMBER"));
                    if (ln != null) {
                        packLineNos.add(ln.trim());
                    }
                }
            }
        }

        // ===== 2. 各类对象批量反查（每类独立降级：某类失败只影响该类）=====
        try {
            cache.deliveryByNo = batchResolveByField("delivery__c", "delivery__c", dnNumbers);
        } catch (Exception e) {
            logger.error("MYSE-17 预取DN号失败，该类退回实时查询", e);
            cache.markFailed("delivery__c", "delivery__c");
        }
        try {
            cache.saleorgByName = batchResolveByField("saleorg__c", "name", vkorgs);
        } catch (Exception e) {
            logger.error("MYSE-17 预取销售组织失败，该类退回实时查询", e);
            cache.markFailed("saleorg__c", "name");
        }
        try {
            cache.accountByKunnr = batchResolveByField("account", "kunnr__c", kunrgs);
        } catch (Exception e) {
            logger.error("MYSE-17 预取客户号失败，该类退回实时查询", e);
            cache.markFailed("account", "kunnr__c");
        }
        try {
            cache.productByCode = batchResolveByField("product", "productCode__c", matNos);
        } catch (Exception e) {
            logger.error("MYSE-17 预取货号失败，该类退回实时查询", e);
            cache.markFailed("product", "productCode__c");
        }
        try {
            cache.orderByNo = batchResolveByField("order__c", "orderNumberAutoGenerate__c", orderNos);
        } catch (Exception e) {
            logger.error("MYSE-17 预取订单号失败，该类退回实时查询", e);
            cache.markFailed("order__c", "orderNumberAutoGenerate__c");
        }
        try {
            cache.deliveryDetailByLine = batchResolveByField("deliveryDetail__c", "lineNumber__c", packLineNos);
        } catch (Exception e) {
            logger.error("MYSE-17 预取装箱行号失败，该类退回实时查询", e);
            cache.markFailed("deliveryDetail__c", "lineNumber__c");
        }
        try {
            cache.provinceByCode = batchLoadProvinceNames(regionCodes);
        } catch (Exception e) {
            logger.error("MYSE-17 预取省份失败，该类退回实时查询", e);
            cache.markFailed("region__c", "provinceCode__c");
        }
        // 二次批量：依赖上面查到的订单 id / 发货单 id
        try {
            cache.orderPosByOrder = batchLoadOrderPositions(cache.orderByNo.values());
        } catch (Exception e) {
            logger.error("MYSE-17 预取订单明细行失败，该类退回按订单整单查询", e);
            cache.markFailed("orderDetail__c", "posicion__c");
        }
        try {
            batchLoadDeliveryChildren(cache);
        } catch (Exception e) {
            logger.error("MYSE-17 预取发货子表明细失败，该类退回实时查询", e);
            cache.markFailed("deliveryDetail__c", "delivery__c");
        }
        try {
            batchLoadDeleteSnapshots(cache);
        } catch (Exception e) {
            logger.error("MYSE-17 预取删除留存快照失败，退回逐单查询", e);
            cache.markFailed("delivery__c", "snapshot");
        }

        logger.info("MYSE-17 批量预取完成: DN号=" + dnNumbers.size() + " 销售组织=" + vkorgs.size()
                + " 客户号=" + kunrgs.size() + " 省份代码=" + regionCodes.size()
                + " 货号=" + matNos.size() + " 订单号=" + orderNos.size()
                + " 装箱行号=" + packLineNos.size() + " 降级实时查询=" + cache.disabled);
        return cache;
    }

    /**
     * 按字段批量反查 id：{@code SELECT id, <field> FROM <entity> WHERE <field> IN ('a','b',...)}
     *
     * @return 值 → id（重复值取第一条）
     */
    private Map<String, Long> batchResolveByField(String entity, String field, Collection<String> values) {
        Map<String, Long> out = new HashMap<>();
        if (values == null || values.isEmpty()) {
            return out;
        }
        List<String> list = new ArrayList<>(values);
        for (int i = 0; i < list.size(); i += BATCH_SIZE) {
            List<String> part = list.subList(i, Math.min(i + BATCH_SIZE, list.size()));
            StringBuilder sb = new StringBuilder();
            for (String v : part) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append("'").append(JsonValues.escape(v)).append("'");
            }
            String sql = "SELECT id, " + field + " FROM " + entity
                    + " WHERE " + field + " IN (" + sb + ")";
            for (JSONObject rec : queryAll(sql)) {
                if (rec == null) {
                    continue;
                }
                Long id = JsonValues.readLookupId(rec, "id");
                Object val = rec.get(field);
                if (id != null && val != null) {
                    String key = val.toString().trim();
                    if (!key.isEmpty() && !out.containsKey(key)) {
                        out.put(key, id);
                    }
                }
            }
        }
        return out;
    }

    /** 批量查省份代码 → 省份名称 */
    private Map<String, String> batchLoadProvinceNames(Collection<String> codes) {
        Map<String, String> out = new HashMap<>();
        if (codes == null || codes.isEmpty()) {
            return out;
        }
        List<String> list = new ArrayList<>(codes);
        for (int i = 0; i < list.size(); i += BATCH_SIZE) {
            List<String> part = list.subList(i, Math.min(i + BATCH_SIZE, list.size()));
            StringBuilder sb = new StringBuilder();
            for (String v : part) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append("'").append(JsonValues.escape(v)).append("'");
            }
            String sql = "SELECT provinceCode__c, provinceName__c FROM region__c"
                    + " WHERE provinceCode__c IN (" + sb + ")";
            for (JSONObject rec : queryAll(sql)) {
                if (rec == null) {
                    continue;
                }
                Object code = rec.get("provinceCode__c");
                Object name = rec.get("provinceName__c");
                if (code != null && name != null) {
                    String k = code.toString().trim();
                    if (!k.isEmpty() && !out.containsKey(k)) {
                        out.put(k, name.toString());
                    }
                }
            }
        }
        return out;
    }

    /** 批量加载订单明细行：{@code SELECT id, order__c, posicion__c FROM orderDetail__c WHERE order__c IN (...)} */
    private Map<Long, Map<String, Long>> batchLoadOrderPositions(Collection<Long> orderIds) {
        Map<Long, Map<String, Long>> out = new HashMap<>();
        if (orderIds == null || orderIds.isEmpty()) {
            return out;
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(orderIds));
        for (int i = 0; i < ids.size(); i += BATCH_SIZE) {
            List<Long> part = ids.subList(i, Math.min(i + BATCH_SIZE, ids.size()));
            String inIds = joinIds(part);
            String sql = "SELECT id, order__c, posicion__c FROM orderDetail__c WHERE order__c IN (" + inIds + ")";
            for (JSONObject rec : queryAll(sql)) {
                if (rec == null) {
                    continue;
                }
                Long id = JsonValues.readLookupId(rec, "id");
                Long orderId = JsonValues.readLookupId(rec, "order__c");
                Object pos = rec.get("posicion__c");
                if (id == null || orderId == null || pos == null) {
                    continue;
                }
                out.computeIfAbsent(orderId, k -> new HashMap<>())
                        .putIfAbsent(pos.toString().trim(), id);
            }
        }
        return out;
    }

    /** 批量加载发货单下的子表明细（删除用 + 关联订单明细）。 */
    private void batchLoadDeliveryChildren(RefCache cache) {
        List<Long> deliveryIds = new ArrayList<>(new LinkedHashSet<>(cache.deliveryByNo.values()));
        if (deliveryIds.isEmpty()) {
            return;
        }
        for (int i = 0; i < deliveryIds.size(); i += BATCH_SIZE) {
            List<Long> part = deliveryIds.subList(i, Math.min(i + BATCH_SIZE, deliveryIds.size()));
            String inIds = joinIds(part);

            // 明细 id + 关联的订单明细 id
            String detailSql = "SELECT id, delivery__c, orderLineNumber__c FROM deliveryDetail__c"
                    + " WHERE delivery__c IN (" + inIds + ")";
            for (JSONObject rec : queryAll(detailSql)) {
                if (rec == null) {
                    continue;
                }
                Long id = JsonValues.readLookupId(rec, "id");
                Long deliveryId = JsonValues.readLookupId(rec, "delivery__c");
                if (id == null || deliveryId == null) {
                    continue;
                }
                cache.detailIdsByDelivery.computeIfAbsent(deliveryId, k -> new ArrayList<>()).add(id);
                Long odId = JsonValues.readLookupId(rec, "orderLineNumber__c");
                if (odId != null) {
                    cache.linkedOrderDetailByDelivery
                            .computeIfAbsent(deliveryId, k -> new LinkedHashSet<>()).add(odId);
                }
            }

            // 装箱明细 id
            String packingSql = "SELECT id, delivery__c FROM packingDetail__c WHERE delivery__c IN (" + inIds + ")";
            for (JSONObject rec : queryAll(packingSql)) {
                if (rec == null) {
                    continue;
                }
                Long id = JsonValues.readLookupId(rec, "id");
                Long deliveryId = JsonValues.readLookupId(rec, "delivery__c");
                if (id == null || deliveryId == null) {
                    continue;
                }
                cache.packingIdsByDelivery.computeIfAbsent(deliveryId, k -> new ArrayList<>()).add(id);
            }
        }
    }

    /**
     * 批量预取「删除留存」快照（发货单主记录 + 两个子明细的全字段 JSON）。
     *
     * <p>删除场景原本每单 3 次 {@code SELECT *}，批量单多时查询数爆炸 → 这里一次性查完并按发货单 id 分组。</p>
     */
    private void batchLoadDeleteSnapshots(RefCache cache) {
        List<Long> deliveryIds = new ArrayList<>(new LinkedHashSet<>(cache.deliveryByNo.values()));
        if (deliveryIds.isEmpty()) {
            return;
        }
        for (int i = 0; i < deliveryIds.size(); i += BATCH_SIZE) {
            List<Long> part = deliveryIds.subList(i, Math.min(i + BATCH_SIZE, deliveryIds.size()));
            String inIds = joinIds(part);

            // 主记录快照（每单一条）
            for (JSONObject rec : queryAll("SELECT * FROM delivery__c WHERE id IN (" + inIds + ")")) {
                if (rec == null) {
                    continue;
                }
                Long id = JsonValues.readLookupId(rec, "id");
                if (id != null) {
                    cache.deliverySnapshotJson.put(id, JSON.toJSONString(rec));
                }
            }
            // 明细快照（按发货单分组）
            Map<Long, List<JSONObject>> detailMap = new LinkedHashMap<>();
            for (JSONObject rec : queryAll("SELECT * FROM deliveryDetail__c WHERE delivery__c IN (" + inIds + ")")) {
                if (rec == null) {
                    continue;
                }
                Long deliveryId = JsonValues.readLookupId(rec, "delivery__c");
                if (deliveryId != null) {
                    detailMap.computeIfAbsent(deliveryId, k -> new ArrayList<>()).add(rec);
                }
            }
            for (Map.Entry<Long, List<JSONObject>> en : detailMap.entrySet()) {
                cache.detailSnapshotJson.put(en.getKey(), JSON.toJSONString(en.getValue()));
            }
            // 装箱明细快照（按发货单分组）
            Map<Long, List<JSONObject>> packingMap = new LinkedHashMap<>();
            for (JSONObject rec : queryAll("SELECT * FROM packingDetail__c WHERE delivery__c IN (" + inIds + ")")) {
                if (rec == null) {
                    continue;
                }
                Long deliveryId = JsonValues.readLookupId(rec, "delivery__c");
                if (deliveryId != null) {
                    packingMap.computeIfAbsent(deliveryId, k -> new ArrayList<>()).add(rec);
                }
            }
            for (Map.Entry<Long, List<JSONObject>> en : packingMap.entrySet()) {
                cache.packingSnapshotJson.put(en.getKey(), JSON.toJSONString(en.getValue()));
            }
        }
    }

    // ======================================================================
    // 阶段 B：报文解析
    // ======================================================================

    /**
     * 处理单张交货单。
     *
     * <p>控制字段（E_ACTCODE / E_DOCNUMBER / E_RES_ORDERS_LINE / E_WAERS）都在自己的容器里取：
     * 新批量格式为 T_OUTPUT.item 元素级，老格式为根级。</p>
     *
     * @return 本张单据的处理结果（失败时带 error 字段，不抛异常）
     */
    private PreparedDelivery prepareDelivery(JSONObject container,
                                             RefCache cache, Map<String, Map<String, Integer>> deliveryPicklistMap) {
        PreparedDelivery prep = new PreparedDelivery();
        JSONObject result = prep.result;
        try {
            // ====== 控制字段 ======
            String actionCode = JsonValues.trimToNull(JsonValues.asString(container, "E_ACTCODE"));
            if (actionCode == null) {
                actionCode = "A";
            }
            actionCode = actionCode.toUpperCase(Locale.ROOT);

            // 交货单号（DN 号）：E_DOCNUMBER，作为 delivery__c(发货单号字段) 的 upsert 键
            String dnNumber = JsonValues.trimToNull(JsonValues.asString(container, "E_DOCNUMBER"));
            if (dnNumber == null) {
                logger.error("MYSE-17 交货单号(E_DOCNUMBER)为空，跳过该单据");
                result.put("action", "skip");
                result.put("error", "交货单号(E_DOCNUMBER)不能为空");
                return prep;
            }
            result.put("dnNumber", dnNumber);

            // 返回信息（E_RES_ORDERS_LINE）
            JSONObject resLine = container.getJSONObject("E_RES_ORDERS_LINE");
            if (resLine != null) {
                logger.info("MYSE-17 E_RES_ORDERS_LINE.RETURNCODE=" + JsonValues.asString(resLine, "RETURNCODE"));
            }
            // 货币码（E_WAERS）：delivery__c 无对应字段，仅日志
            String waers = JsonValues.asString(container, "E_WAERS");
            if (waers != null) {
                logger.info("MYSE-17 E_WAERS=" + waers + "（CRM 无对应字段，仅日志）");
            }

            prep.actionCode = actionCode;
            prep.dnNumber = dnNumber;

            // ====== D=删除（阶段 C 批量执行）=====
            if ("D".equals(actionCode)) {
                prep.isDelete = true;
                prep.deliveryId = findDeliveryIdByNumber(dnNumber, cache);
                return prep;
            }

            // ====== A / C / U = upsert ======
            if (!"A".equals(actionCode) && !"C".equals(actionCode) && !"U".equals(actionCode)) {
                logger.warn("MYSE-17 未知操作码 E_ACTCODE=" + actionCode + "，按 upsert 处理");
            }
            Long existingId = findDeliveryIdByNumber(dnNumber, cache);
            prep.isNew = (existingId == null);
            prep.delivery = buildDelivery(container, dnNumber, cache, existingId, deliveryPicklistMap);

            JSONArray lines = getItems(container, "T_DELIVERY_LINE");
            JSONArray packs = getItems(container, "T_PACKINGLIST");
            prep.lines = buildDeliveryDetails(lines, cache);
            prep.packs = buildPackingDetails(dnNumber, packs, cache, prep.lines, prep.packLineNos);

            result.put("action", "upsert");
            return prep;
        } catch (Exception e) {
            logger.error("MYSE-17 单据解析异常 dnNumber=" + result.getString("dnNumber")
                    + ": " + e.getMessage(), e);
            result.put("action", "error");
            result.put("error", e.getMessage());
            prep.failed = true;
            return prep;
        }
    }

    private Delivery__c buildDelivery(JSONObject root, String dnNumber,
                                      RefCache cache, Long existingId,
                                      Map<String, Map<String, Integer>> deliveryMap) {
        Delivery__c d = new Delivery__c();
        if (existingId != null) {
            d.setId(existingId);
        } else {
            d.setEntityType(BusinessTypes.DELIVERY);
        }
        d.setName(dnNumber);
        d.setDelivery__c(dnNumber);

        JSONObject hdr = root.getJSONObject("T_DELIVERY_HDR");
        // 日期字段优先取 HDR；HDR 无则回退首个发货行
        JSONArray linesForDate = getItems(root, "T_DELIVERY_LINE");
        JSONObject dateSrc = (hdr != null) ? hdr
                : ((linesForDate != null && linesForDate.size() > 0) ? linesForDate.getJSONObject(0) : null);

        if (hdr != null) {
            // —— 纯字段映射（SAP 字段 → CRM 字段 + 类型）——
            mapFields(d, hdr, HEADER_TEXT_MAP);
            mapFields(d, hdr, HEADER_DOUBLE_MAP);
            // 名称2 回退：NAME2 缺则取 NAME3
            if (JsonValues.asString(hdr, "NAME2") == null) {
                setStr(d, "name2__c", JsonValues.asString(hdr, "NAME3"));
            }
            // 发货地址复用街道
            String street = JsonValues.asString(hdr, "STREET");
            if (street != null) d.setShipToAddress__c(street);
            // 发货单状态：A→1，其余→2
            String status = JsonValues.asString(hdr, "STATUS");
            if (status != null) d.setDeliveryStatus__c("A".equals(status) ? 1 : 2);
            // 账户关联（KUNNR → purchaseId__c lookup，按 kunnr__c 反查，带0/去0 两种形式）
            String kunnr = JsonValues.asString(hdr, "KUNNR");
            if (kunnr != null) {
                Long accountId = findAccountIdByKunnr(kunnr.trim(), cache);
                if (accountId != null) d.setPurchaseId__c(accountId);
                else logger.warn("MYSE-17 未找到账户 account: kunnr__c IN (" + kunnr.trim() + ")（purchaseId__c 不赋值）");
            }
            // 销售组织（VKORG → vkorg__c lookup，按 name 反查）
            String vkorg = JsonValues.asString(hdr, "VKORG");
            if (vkorg != null) d.setVkorg__c(vkorgIdByNumber(vkorg, cache));
            // 信用状态（CMGST → cmgst__c 选项值，SAP 未传/选项缺失/值不在选项内均不赋值）
            String CMGST = JsonValues.trimToNull(JsonValues.asString(hdr, "CMGST"));
            if (CMGST != null) {
                Map<String, Integer> cmgstOpts = deliveryMap == null ? null : deliveryMap.get("cmgst__c");
                Integer cmgstVal = cmgstOpts == null ? null : cmgstOpts.get(CMGST);
                if (cmgstVal != null) d.setCmgst__c(cmgstVal);
                else logger.warn("MYSE-17 信用状态未匹配 CMGST=" + CMGST + "（cmgst__c 不赋值）");
            }
            // 省份（REGION 代码 → region__c 名称，按 provinceCode__c 反查）
            String REGION = JsonValues.trimToNull(JsonValues.asString(hdr, "REGION"));
            if (REGION != null) {
                String provinceName = findProvinceNameByCode(REGION, cache);
                if (provinceName != null) d.setRegion__c(provinceName);
                else logger.warn("MYSE-17 未匹配到省份 region__c: provinceCode__c=" + REGION + "（region__c 不赋值）");
            }
            // DN 创建时间（ERDAT + ERZET 拼接）
            setStr(d, "createdAt__c", mergeDateTime(hdr));
        }

        // 日期字段（优先 HDR，回退首个发货行）
        mapDateFields(d, dateSrc, HEADER_DATE);
        // 发货单创建时间（E_TIMESTAMP 文本，取根级）
        setStr(d, "e_timeStamp__c", JsonValues.asString(root, "E_TIMESTAMP"));
        return d;
    }

    // —— 字段映射配置（SAP 字段 → CRM 字段），类型见各 Map ——

    /** 表头纯文本字段：{ SAP字段 → CRM字段 } */
    private static final String[][] HEADER_TEXT = {
            {"NAME1", "name1__c"}, {"NAME2", "name2__c"}, {"STREET", "street__c"},
            {"PHONE", "phone__c"}, {"FAX", "fax__c"}, {"SHIPPT", "shippingPoint__c"},
            {"CARRIER", "travelNumber__c"}, {"UNIT", "unit__c"}, {"UNITV", "unitv__c"},
            {"VENDORCARRIER", "vendorCarrier__c"}, {"PHONEVENDOR", "phoneVendor__c"},
            {"CITY", "city__c"},
    };
    /** 表头数值字段（Double）：{ SAP字段 → CRM字段 } */
    private static final String[][] HEADER_DOUBLE = {
            {"WEIGHT", "weight__c"}, {"VOL", "vol__c"},
    };
    /** 表头/行 日期字段（parseDate）：{ SAP字段 → CRM字段 } */
    private static final String[][] HEADER_DATE = {
            {"KODAT", "kodat__c"}, {"WADAT", "wadat__c"}, {"PACKDATE", "packDate__c"},
    };

    private static final Map<String, String> HEADER_TEXT_MAP = toMap(HEADER_TEXT);
    private static final Map<String, String> HEADER_DOUBLE_MAP = toMap(HEADER_DOUBLE);

    private static Map<String, String> toMap(String[][] pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String[] p : pairs) m.put(p[0], p[1]);
        return m;
    }

    /** 文本字段映射：源值非空才写 */
    private static void mapFields(XObject obj, JSONObject src, Map<String, String> textMap) {
        if (src == null) return;
        for (Map.Entry<String, String> e : textMap.entrySet()) {
            setStr(obj, e.getValue(), JsonValues.asString(src, e.getKey()));
        }
    }

    /** Double 字段映射 */
    private static void mapFields(XObject obj, JSONObject src, String[][] doubleMap) {
        if (src == null) return;
        for (String[] p : doubleMap) {
            Double v = JsonValues.parseDouble(JsonValues.asString(src, p[0]));
            if (v != null) obj.setAttribute(p[1], v);
        }
    }

    /** 日期字段映射（parseDate → Long 时间戳） */
    private static void mapDateFields(XObject obj, JSONObject src, String[][] dateMap) {
        if (src == null) return;
        for (String[] p : dateMap) {
            Long v = JsonValues.parseDate(JsonValues.asString(src, p[0]));
            if (v != null) obj.setAttribute(p[1], v);
        }
    }

    /** 写字符串字段（非空才写） */
    private static void setStr(XObject obj, String field, String v) {
        if (v != null) obj.setAttribute(field, v);
    }

    /** 写整型字段（Double → long，非空才写） */
    private static void setLong(XObject obj, String field, Double v) {
        if (v != null) obj.setAttribute(field, v.longValue());
    }

    /** ERDAT + ERZET → 时间戳文本（拼成 yyyyMMdd HHmmss 后解析） */
    private static String mergeDateTime(JSONObject hdr) {
        String erdat = JsonValues.trimToNull(JsonValues.asString(hdr, "ERDAT"));
        String erzet = JsonValues.trimToNull(JsonValues.asString(hdr, "ERZET"));
        if (erdat == null) return null;
        Long t = erzet != null ? JsonValues.parseDateTime(erdat + " " + erzet) : JsonValues.parseDate(erdat);
        return t == null ? null : String.valueOf(t);
    }

    private List<DeliveryDetail__c> buildDeliveryDetails(JSONArray lines, RefCache cache) {
        List<DeliveryDetail__c> details = new ArrayList<>();
        int resolvedProduct = 0, resolvedOrder = 0;
        if (lines != null) {
            for (int i = 0; i < lines.size(); i++) {
                JSONObject item = lines.getJSONObject(i);
                if (item == null) continue;
                DeliveryDetail__c det = new DeliveryDetail__c();
                det.setEntityType(BusinessTypes.resolve("deliveryDetail__c", BusinessTypes.DELIVERY_DETAIL));

                String lineNo = JsonValues.asString(item, "LINENUMBER");
                det.setName(lineNo != null ? lineNo : ("line" + i));
                setStr(det, "lineNumber__c", lineNo);
                // 订货号（CATALOGNUMBER → catelogNumber__c lookup，按 productCode__c 反查）
                String matNo = JsonValues.asString(item, "CATALOGNUMBER");
                if (matNo != null) {
                    Long productId = resolveByField("product", "productCode__c", matNo, cache);
                    if (productId != null) { det.setCatelogNumber__c(productId); resolvedProduct++; }
                    else logger.warn("MYSE-17 未找到产品 product: productCode__c=" + matNo);
                }
                // 采购编号 / 数量
                setStr(det, "puechaseOrder__c", JsonValues.asString(item, "PURCHASEORDERNUMBER"));
                setLong(det, "quantityOrdered__c", JsonValues.parseDouble(JsonValues.asString(item, "QUANTITYORDERED")));
                setLong(det, "quantityShipped__c", JsonValues.parseDouble(JsonValues.asString(item, "QUANTITYSHIPPED")));
                // 销售订单号（ORDERNUMBER → order__c lookup + 文本兜底）
                // ⚠️ 期初数据可能无订单 → 先存 orderText__c / orderLineText__c 文本，后续刷数据回填
                String orderNo = JsonValues.asString(item, "ORDERNUMBER");
                if (orderNo != null) {
                    det.setAttribute("orderText__c", orderNo);
                    Long orderId = resolveByField("order__c", "orderNumberAutoGenerate__c", orderNo, cache);
                    if (orderId != null) { det.setOrder__c(orderId); resolvedOrder++; }
                    else logger.info("MYSE-17 未找到销售订单 order__c: orderNumberAutoGenerate__c=" + orderNo + "（仅存文本）");
                }
                // 订单行号（ORDERLINENUMBER → orderLineNumber__c lookup + 文本兜底）
                String orderLineNo = JsonValues.asString(item, "ORDERLINENUMBER");
                if (orderLineNo != null) {
                    det.setAttribute("orderLineText__c", orderLineNo);
                    Long orderId = det.getOrder__c();
                    if (orderId != null) {
                        Long odId = resolveOrderDetailByLine(orderId, Integer.parseInt(orderLineNo) + "", cache);
                        if (odId != null) det.setOrderLineNumber__c(odId);
                        else logger.info("MYSE-17 未找到订单明细 orderDetail__c(行号=" + orderLineNo + ")，仅存文本");
                    }
                }
                details.add(det);
            }
        }
        logger.info("MYSE-17 发货单明细构造完成: 条数=" + details.size()
                + ", 解析产品=" + resolvedProduct + ", 解析订单=" + resolvedOrder);
        return details;
    }

    private List<PackingDetail__c> buildPackingDetails(String dnNumber, JSONArray packs,
                                                       RefCache cache, List<DeliveryDetail__c> deliveryLines,
                                                       Map<PackingDetail__c, String> packLineNos) {
        List<PackingDetail__c> packList = new ArrayList<>();
        int resolvedProduct = 0;
        if (packs != null) {
            for (int i = 0; i < packs.size(); i++) {
                JSONObject item = packs.getJSONObject(i);
                if (item == null) continue;
                PackingDetail__c pk = new PackingDetail__c();
                pk.setEntityType(BusinessTypes.resolve("packingDetail__c", BusinessTypes.PACKING_DETAIL));

                String box = JsonValues.asString(item, "BOXNUMBER");
                pk.setName(box != null ? box : ("box" + i));
                setStr(pk, "boxNumber__c", box);
                // 货号（CATALOGNUMBER → catelogNumber__c lookup）
                String matNo = JsonValues.asString(item, "CATALOGNUMBER");
                if (matNo != null) {
                    Long productId = resolveByField("product", "productCode__c", matNo, cache);
                    if (productId != null) { pk.setCatelogNumber__c(productId); resolvedProduct++; }
                    else logger.warn("MYSE-17 未找到产品 product: productCode__c=" + matNo);
                }
                // 数量 / 托盘号（注意平台字段名 palletNumbe__c 缺 'r'）
                setLong(pk, "quantity__c", JsonValues.parseDouble(JsonValues.asString(item, "QUANTITY")));
                setStr(pk, "palletNumbe__c", JsonValues.asString(item, "PALLETNUMBER"));
                // 发货单行号（LINENUMBER → lineNumber__c lookup）
                // 期初已存在的发货单明细：先查库；新建场景则等阶段 C 发货单明细落库后再查库回填。
                String packLineNo = JsonValues.asString(item, "LINENUMBER");
                if (packLineNo != null) {
                    Long ddId = resolveByField("deliveryDetail__c", "lineNumber__c", packLineNo, cache);
                    if (ddId != null) {
                        pk.setLineNumber__c(ddId);
                    } else {
                        // 记下 LINENUMBER 原文，阶段 C 发货单明细落库后再查库回填 lineNumber__c
                        packLineNos.put(pk, packLineNo.trim());
                    }
                }
                pk.setDeliveryNo__c(dnNumber);
                packList.add(pk);
            }
        }
        logger.info("MYSE-17 装箱单明细构造完成: 条数=" + packList.size() + ", 解析产品=" + resolvedProduct
                + ", 待落库后回填行号=" + packLineNos.size());
        return packList;
    }

    // ======================================================================
    // 阶段 C：批量落库
    // ======================================================================

    /**
     * 阶段 C-1：批量落库发货单主记录（新增 insert，更新 update）。
     */
    private void batchUpsertMain(List<PreparedDelivery> prepared, RefCache cache) {
        List<PreparedDelivery> ins = new ArrayList<>(), upd = new ArrayList<>();
        for (PreparedDelivery p : prepared) {
            if (p.failed || p.isDelete || p.delivery == null) continue;
            (p.isNew ? ins : upd).add(p);
        }
        upsertBatch(upd, false);
        upsertBatch(ins, true);
        logger.info("MYSE-17 主记录批量落库完成: 新增=" + ins.size() + ", 更新=" + upd.size());
    }

    /** 批量 insert/update 发货单主记录，逐条回填 id 或错误。 */
    private void upsertBatch(List<PreparedDelivery> targets, boolean insert) {
        for (int i = 0; i < targets.size(); i += BATCH_SIZE) {
            List<PreparedDelivery> part = targets.subList(i, Math.min(i + BATCH_SIZE, targets.size()));
            List<Delivery__c> objs = new ArrayList<>();
            for (PreparedDelivery p : part) objs.add(p.delivery);
            try {
                BatchOperateResult br = insert
                        ? XObjectService.instance().insert(objs, true, true)
                        : XObjectService.instance().update(objs, true, true);
                List<OperateResult> ops = br == null ? null : br.getOperateResults();
                for (int k = 0; k < part.size(); k++) {
                    OperateResult op = (ops != null && k < ops.size()) ? ops.get(k) : null;
                    if (op != null && Boolean.TRUE.equals(op.getSuccess()) && (!insert || op.getDataId() != null)) {
                        if (insert) part.get(k).delivery.setId(op.getDataId());
                        part.get(k).deliveryId = insert ? op.getDataId() : part.get(k).delivery.getId();
                        part.get(k).result.put("deliveryId", String.valueOf(part.get(k).deliveryId));
                    } else {
                        String err = op == null ? "无返回" : op.getErrorMessage();
                        part.get(k).result.put("action", "error");
                        part.get(k).result.put("error", (insert ? "保存" : "更新") + "发货单失败: " + err);
                        logger.error("MYSE-17 批量" + (insert ? "插入" : "更新") + "发货单失败 dnNumber="
                                + part.get(k).dnNumber + " err=" + err);
                    }
                }
            } catch (Exception e) {
                logger.error("MYSE-17 批量" + (insert ? "插入" : "更新") + "发货单异常", e);
                for (PreparedDelivery p : part) {
                    p.result.put("action", "error");
                    p.result.put("error", "批量" + (insert ? "插入" : "更新") + "异常: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 阶段 C-2：批量同步明细（发货明细 + 装箱明细，整组替换：先批量删旧，再批量插新）。
     */
    private void batchSyncDetails(List<PreparedDelivery> prepared, RefCache cache, List<String> errors) {
        // ===== 1) 批量删除旧明细 =====
        List<XObject> toDelete = new ArrayList<>();
        int before = 0;
        for (PreparedDelivery p : prepared) {
            if (p.failed || p.isDelete || p.deliveryId == null) {
                continue;
            }
            for (Long id : childIdsOf(p.deliveryId, "deliveryDetail__c", cache, p.isNew)) {
                DeliveryDetail__c d = new DeliveryDetail__c();
                d.setId(id);
                toDelete.add(d);
            }
            for (Long id : childIdsOf(p.deliveryId, "packingDetail__c", cache, p.isNew)) {
                PackingDetail__c pk = new PackingDetail__c();
                pk.setId(id);
                toDelete.add(pk);
            }
            // 本单删除的明细数（不是累计值）
            p.deletedItemCount = toDelete.size() - before;
            before = toDelete.size();
        }
        int deleted = BatchOperateUtils.batchDelete(toDelete, "发货单明细", errors);

        // ===== 2) 批量插入新明细 =====
        // 2-a) 先插发货单明细，并回填 id（装箱单 lineNumber__c 依赖发货单明细 id）
        List<DeliveryDetail__c> allLines = new ArrayList<>();
        for (PreparedDelivery p : prepared) {
            if (p.failed || p.isDelete || p.deliveryId == null) {
                continue;
            }
            for (DeliveryDetail__c det : p.lines) {
                det.setDelivery__c(p.deliveryId);
                allLines.add(det);
                if (det.getOrderLineNumber__c() != null) {
                    p.affectedOrderDetailIds.add(det.getOrderLineNumber__c());
                }
            }
            p.insertedLineCount = p.lines.size();
        }
        int insertedLines = insertDeliveryDetailsBackfillId(allLines, "发货单明细", errors);

        // 2-b) 用 XObjectService 实时查询刚插入的发货单明细 id，回填装箱单 lineNumber__c
        backfillPackLineRefs(prepared, errors);

        // 2-c) 再插装箱单明细
        List<XObject> packInsert = new ArrayList<>();
        for (PreparedDelivery p : prepared) {
            if (p.failed || p.isDelete || p.deliveryId == null) {
                continue;
            }
            for (PackingDetail__c pk : p.packs) {
                pk.setDelivery__c(p.deliveryId);
                packInsert.add(pk);
            }
            p.insertedPackCount = p.packs.size();
        }
        int insertedPacks = BatchOperateUtils.batchInsert(packInsert, "装箱单明细", errors);

        // 回填出参
        for (PreparedDelivery p : prepared) {
            if (p.failed || p.isDelete || p.deliveryId == null) {
                continue;
            }
            p.result.put("deliveryId", String.valueOf(p.deliveryId));
            p.result.put("deliveryLineCount", p.insertedLineCount);
            p.result.put("packingLineCount", p.insertedPackCount);
            p.result.put("deletedItemCount", p.deletedItemCount);
            cache.affectedOrderDetailIds.addAll(p.affectedOrderDetailIds);
        }
        logger.info("MYSE-17 明细批量落库完成: 插入发货明细=" + insertedLines
                + ", 插入装箱明细=" + insertedPacks + ", 删除=" + deleted);
    }

    /**
     * 批量插入发货单明细。发货单明细 apiKey 一致（deliveryDetail__c），可整批提交。
     * <p>装箱单 lineNumber__c 的回填不依赖这里的 id（由 {@link #backfillPackLineRefs} 用
     * XObjectService 实时查询），故本方法不再回填 id，仅统计成功条数。</p>
     */
    private int insertDeliveryDetailsBackfillId(List<DeliveryDetail__c> lines, String label, List<String> errors) {
        if (lines == null || lines.isEmpty()) {
            return 0;
        }
        int success = 0;
        for (int i = 0; i < lines.size(); i += BATCH_SIZE) {
            List<DeliveryDetail__c> part = lines.subList(i, Math.min(i + BATCH_SIZE, lines.size()));
            try {
                BatchOperateResult br = XObjectService.instance().insert(part, true, true);
                if (br == null || !Boolean.TRUE.equals(br.getSuccess())) {
                    BatchOperateUtils.addError(errors, "批量插入" + label + "整批失败: "
                            + (br == null ? "无返回" : br.getErrorMessage()));
                    continue;
                }
                List<OperateResult> ops = br.getOperateResults();
                for (int k = 0; k < part.size(); k++) {
                    OperateResult op = (ops != null && k < ops.size()) ? ops.get(k) : null;
                    if (op != null && Boolean.TRUE.equals(op.getSuccess()) && op.getDataId() != null) {
                        success++;
                    } else {
                        BatchOperateUtils.addError(errors, "批量插入" + label + "失败: "
                                + (op == null ? "无返回" : op.getErrorMessage()));
                    }
                }
            } catch (Exception e) {
                BatchOperateUtils.addError(errors, "批量插入" + label + "异常: " + e.getMessage());
                logger.error("批量插入" + label + "异常", e);
            }
        }
        return success;
    }

    /**
     * 装箱单 lineNumber__c 回填：发货单明细已落库后，用 XObjectService 实时查询
     * {@code deliveryDetail__c}（按 delivery__c + lineNumber__c 匹配）拿到 id，回填给装箱单。
     *
     * <p>与 XOQL 通道（有 1-2 秒延迟）不同，XObjectService 查的是实时数据，能拿到刚插入的明细。</p>
     */
    private void backfillPackLineRefs(List<PreparedDelivery> prepared, List<String> errors) {
        for (PreparedDelivery p : prepared) {
            if (p.failed || p.isDelete || p.deliveryId == null || p.packLineNos.isEmpty()) {
                continue;
            }
            // 按行号收集本单所有待回填的装箱单，一次查询把该单发货单明细全量取出，避免逐条查
            for (Map.Entry<PackingDetail__c, String> en : p.packLineNos.entrySet()) {
                String lineNo = en.getValue();
                Long ddId = queryDeliveryDetailIdByLine(p.deliveryId, lineNo);
                if (ddId != null) {
                    en.getKey().setLineNumber__c(ddId);
                } else {
                    logger.info("MYSE-17 装箱单行号未匹配发货单明细 lineNumber__c=" + lineNo + "（仅存 deliveryNo__c 追溯）");
                }
            }
        }
    }

    /**
     * 用 XObjectService 实时查询发货单明细 id：delivery__c + lineNumber__c 匹配。
     * 行号做「原值 + 补零到 4 位」候选，兼容 SAP 10 / 0010 位宽差异。
     */
    private Long queryDeliveryDetailIdByLine(Long deliveryId, String lineNo) {
        if (deliveryId == null || lineNo == null) {
            return null;
        }
        String trimmed = lineNo.trim();
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, trimmed);
        if (trimmed.matches("\\d+") && trimmed.length() < 4) {
            addCandidate(candidates, String.format("%04d", Integer.parseInt(trimmed)));
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                in.append(",");
            }
            in.append("'").append(JsonValues.escape(candidates.get(i))).append("'");
        }
        try {
            String sql = "SELECT id FROM deliveryDetail__c WHERE delivery__c = " + deliveryId
                    + " AND lineNumber__c IN (" + in + ")";
            QueryResult<DeliveryDetail__c> rt = XObjectService.instance().query(sql, true);
            if (rt != null && rt.getRecords() != null && !rt.getRecords().isEmpty()) {
                return rt.getRecords().get(0).getId();
            }
        } catch (Exception e) {
            logger.warn("MYSE-17 XObjectService 查询发货单明细失败 deliveryId=" + deliveryId
                    + " lineNumber__c=" + lineNo, e);
        }
        return null;
    }

    /**
     * 阶段 C-3：批量删除（D 类单据）——先批量删子明细与留存日志，再批量删主记录。
     */
    private void batchDeleteInvoices(List<PreparedDelivery> prepared, RefCache cache, List<String> errors) {
        List<PreparedDelivery> deletes = new ArrayList<>();
        for (PreparedDelivery p : prepared) {
            if (p.isDelete && !p.failed) {
                deletes.add(p);
            }
        }
        if (deletes.isEmpty()) {
            return;
        }
        // 1) 批量删子明细
        List<XObject> detailStubs = new ArrayList<>();
        int before = 0;
        for (PreparedDelivery p : deletes) {
            if (p.deliveryId == null) {
                p.deletedItemCount = 0;
                continue;
            }
            p.affectedOrderDetailIds.addAll(queryLinkedOrderDetailIds(p.deliveryId, cache));
            for (Long id : childIdsOf(p.deliveryId, "deliveryDetail__c", cache, false)) {
                DeliveryDetail__c d = new DeliveryDetail__c();
                d.setId(id);
                detailStubs.add(d);
            }
            for (Long id : childIdsOf(p.deliveryId, "packingDetail__c", cache, false)) {
                PackingDetail__c pk = new PackingDetail__c();
                pk.setId(id);
                detailStubs.add(pk);
            }
            // 本单删除的明细数（不是累计值）
            p.deletedItemCount = detailStubs.size() - before;
            before = detailStubs.size();
        }
        BatchOperateUtils.batchDelete(detailStubs, "发货单明细", errors);

        // 2) 删除留存快照（批量写 deliveryDeleteLog__c）
        batchArchiveBeforeDelete(deletes, cache, errors);

        // 3) 批量删主记录
        List<XObject> mainStubs = new ArrayList<>();
        for (PreparedDelivery p : deletes) {
            if (p.deliveryId == null) {
                continue;
            }
            Delivery__c stub = new Delivery__c();
            stub.setId(p.deliveryId);
            mainStubs.add(stub);
        }
        BatchOperateUtils.batchDelete(mainStubs, "发货单", errors);

        for (PreparedDelivery p : deletes) {
            p.result.put("action", "delete");
            p.result.put("deletedItemCount", p.deletedItemCount);
            cache.affectedOrderDetailIds.addAll(p.affectedOrderDetailIds);
            if (p.deliveryId == null) {
                logger.info("MYSE-17 删除：未找到发货单 dnNumber=" + p.dnNumber);
            } else {
                logger.info("MYSE-17 删除发货单成功 dnNumber=" + p.dnNumber + ", 子明细=" + p.deletedItemCount);
            }
        }
    }

    /**
     * 批量写「删除留存」日志（deliveryDeleteLog__c）。
     *
     * <p>原来每删一单 insert 一条留存记录 → 批量删多单时写入次数 = 单数；
     * 这里改为一次批量 insert。留存失败只记日志，不阻断删除主流程。</p>
     */
    private void batchArchiveBeforeDelete(List<PreparedDelivery> deletes, RefCache cache, List<String> errors) {
        if (BusinessTypes.DELETE_LOG == null) {
            logger.warn("MYSE-17 未配置 deliveryDeleteLog__c 业务类型，跳过删除留存（不影响删除）");
            return;
        }
        List<XObject> logs = new ArrayList<>();
        for (PreparedDelivery p : deletes) {
            if (p.deliveryId == null) {
                continue;
            }
            try {
                // 1) 发货单主记录全字段快照（批量预取优先，避免每单 3 次查询）
                String deliveryJson = null;
                String detailJson = null;
                String packingJson = null;
                if (cache != null && !cache.disabled) {
                    deliveryJson = cache.deliverySnapshotJson.get(p.deliveryId);
                    detailJson = cache.detailSnapshotJson.get(p.deliveryId);
                    packingJson = cache.packingSnapshotJson.get(p.deliveryId);
                }
                if (deliveryJson == null) {
                    deliveryJson = queryRowsToJson("SELECT * FROM delivery__c WHERE id = " + p.deliveryId);
                }
                if (detailJson == null) {
                    detailJson = queryRowsToJson("SELECT * FROM deliveryDetail__c WHERE delivery__c = " + p.deliveryId);
                }
                if (packingJson == null) {
                    packingJson = queryRowsToJson("SELECT * FROM packingDetail__c WHERE delivery__c = " + p.deliveryId);
                }
                int detailCount = countJsonRows(detailJson) + countJsonRows(packingJson);

                // 2) 组装留存记录
                // model.jar 尚无 deliveryDeleteLog__c 实体类，且 SDK 的 XObject 没有公开构造器，
                // 故反射 new + setAttribute 逐字段写入，字段名带 __c 后缀。
                XObject logObj = newXObjectInstance();
                if (logObj == null) {
                    logger.warn("MYSE-17 无法实例化 XObject，跳过删除留存 dnNumber=" + p.dnNumber);
                    continue;
                }
                Map<String, Object> attrs = new LinkedHashMap<>();
                attrs.put("entityType", BusinessTypes.DELETE_LOG);
                attrs.put("name", p.dnNumber + "-删除-" + System.currentTimeMillis());
                attrs.put("dnNumber__c", p.dnNumber);
                attrs.put("deliveryId__c", String.valueOf(p.deliveryId));
                attrs.put("actionCode__c", "D");
                attrs.put("deleteTime__c", System.currentTimeMillis());
                attrs.put("detailCount__c", detailCount);
                attrs.put("deliveryJson__c", deliveryJson);
                attrs.put("detailJson__c", detailJson);
                attrs.put("packingJson__c", packingJson);
                JSONArray affected = new JSONArray();
                for (Long id : p.affectedOrderDetailIds) {
                    affected.add(id);
                }
                JSONObject affectedWrap = new JSONObject();
                affectedWrap.put("orderDetailIds", affected);
                attrs.put("rawRequestJson__c", affectedWrap.toJSONString());
                for (Map.Entry<String, Object> en : attrs.entrySet()) {
                    logObj.setAttribute(en.getKey(), en.getValue());
                }
                logs.add(logObj);
            } catch (Exception e) {
                // 留存失败不阻断删除
                logger.error("MYSE-17 删除留存组装异常（不影响删除主流程） dnNumber=" + p.dnNumber, e);
            }
        }
        int written = BatchOperateUtils.batchInsert(logs, "删除留存日志", errors);
        logger.info("MYSE-17 删除留存批量写入完成: 写入=" + written + "/" + logs.size());
    }

    /**
     * 汇总发货数量回写订单明细：对受影响的每个订单明细，重新累计所有发货单明细的 quantityShipped__c
     * （跨发货单全量重算，避免多次发货只覆盖最后一次的数量），结果写入 orderDetail__c.shippedQuantity__c。
     */
    private void refreshOrderShippedQuantity(Set<Long> orderDetailIds) {
        if (orderDetailIds == null || orderDetailIds.isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>();
        for (Long id : orderDetailIds) {
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        try {
            // ===== 一次查全部受影响订单明细的发货数量，按 orderLineNumber__c 分组汇总 =====
            // （原来每个订单明细单独查一次 → 批次大时查询数爆炸）
            Map<Long, Long> shippedMap = new HashMap<>();
            for (int i = 0; i < ids.size(); i += BATCH_SIZE) {
                List<Long> part = ids.subList(i, Math.min(i + BATCH_SIZE, ids.size()));
                String inIds = joinIds(part);
                String sql = "SELECT orderLineNumber__c, quantityShipped__c FROM deliveryDetail__c"
                        + " WHERE orderLineNumber__c IN (" + inIds + ")";
                for (JSONObject rec : queryAll(sql)) {
                    if (rec == null) {
                        continue;
                    }
                    Long odId = JsonValues.readLookupId(rec, "orderLineNumber__c");
                    if (odId == null) {
                        continue;
                    }
                    long qty = 0L;
                    Object v = rec.get("quantityShipped__c");
                    if (v instanceof Number) {
                        qty = ((Number) v).longValue();
                    } else if (v != null) {
                        Double d = JsonValues.parseDouble(v);
                        if (d != null) {
                            qty = d.longValue();
                        }
                    }
                    Long cur = shippedMap.get(odId);
                    shippedMap.put(odId, (cur == null ? 0L : cur) + qty);
                }
            }

            // ===== 批量回写 =====
            List<OrderDetail__c> toUpdate = new ArrayList<>();
            for (Long odId : ids) {
                OrderDetail__c od = new OrderDetail__c();
                od.setId(odId);
                Long total = shippedMap.get(odId);
                od.setShippedQuantity__c(total == null ? Long.valueOf(0L) : total);
                toUpdate.add(od);
            }
            BatchOperateResult r = XObjectService.instance().update(toUpdate, true, true);
            boolean ok = r != null && Boolean.TRUE.equals(r.getSuccess());
            logger.info("MYSE-17 订单明细已发货数量汇总完成: 影响=" + ids.size()
                    + ", 批量回写=" + (ok ? "成功" : "失败 err=" + (r == null ? "null" : r.getErrorMessage())));
        } catch (Exception e) {
            logger.error("MYSE-17 汇总订单明细发货数量异常 影响=" + ids.size(), e);
        }
    }

    /**
     * 创建用于承载留存记录的 XObject 实例。
     * <p>优先用平台实体类（model.jar 刷新后会生成 DeliveryDeleteLog__c，setter 更规范）；
     * 当前 model.jar 尚无该类，退化用反射创建 {@link XObject} + {@code setAttribute} 逐字段写入。</p>
     *
     * @return 实例；两条路都失败返回 null（调用方负责降级，不阻断删除主流程）
     */
    private XObject newXObjectInstance() {
        // 路线1：平台实体类（model.jar 刷新后会生成 DeliveryDeleteLog__c）
        try {
            Class<?> entityClass = Class.forName("com.rkhd.platform.sdk.data.model.DeliveryDeleteLog__c");
            Object entity = entityClass.getDeclaredConstructor().newInstance();
            return (XObject) entity;
        } catch (ClassNotFoundException e) {
            logger.info("MYSE-17 model.jar 暂无 DeliveryDeleteLog__c 实体类，退化用 XObject 反射写入");
        } catch (Exception e) {
            logger.warn("MYSE-17 实例化 DeliveryDeleteLog__c 失败，退化用 XObject: " + e.getMessage());
        }
        // 路线2：反射创建 XObject（SDK 未暴露公开无参构造器）
        try {
            java.lang.reflect.Constructor<?> c;
            try {
                c = XObject.class.getDeclaredConstructor();
            } catch (NoSuchMethodException e) {
                c = null;
                for (java.lang.reflect.Constructor<?> cand : XObject.class.getDeclaredConstructors()) {
                    if (cand.getParameterTypes().length == 0) {
                        c = cand;
                        break;
                    }
                }
                if (c == null) {
                    logger.warn("MYSE-17 XObject 无可用无参构造器，无法创建留存记录");
                    return null;
                }
            }
            c.setAccessible(true);
            return (XObject) c.newInstance();
        } catch (Exception e) {
            logger.warn("MYSE-17 反射创建 XObject 失败: " + e.getMessage());
            return null;
        }
    }

    /** 查若干行并序列化成 JSON 数组字符串（失败返回 "[]"）。 */
    private String queryRowsToJson(String xoql) {
        try {
            JSONArray records = new JSONArray();
            records.addAll(queryAll(xoql));
            return records.toJSONString();
        } catch (Exception e) {
            logger.warn("MYSE-17 删除留存查询失败: " + xoql, e);
        }
        return "[]";
    }

    /** 数一下 JSON 数组字符串里有几行（用于统计明细条数）。 */
    private int countJsonRows(String json) {
        try {
            if (json == null || json.trim().isEmpty()) {
                return 0;
            }
            JSONArray arr = JSON.parseArray(json);
            return arr == null ? 0 : arr.size();
        } catch (Exception e) {
            return 0;
        }
    }

    // ======================================================================
    // 查询与反查（预取缓存优先，缓存没有就是没有）
    // ======================================================================

    /**
     * 分页取全：反复 {@code LIMIT offset, 300} 直到取完。
     *
     * <p><b>为什么必须分页</b>：平台单次响应条数有硬上限（实测：不带 LIMIT 只返 100 条，
     * 写 {@code LIMIT 0,500} 也只返 300 条），超出会<b>静默截断</b>且不报错。</p>
     */
    private List<JSONObject> queryAll(String baseSql) {
        List<JSONObject> all = new ArrayList<>();
        int offset = 0;
        while (offset < MAX_FETCH_ROWS) {
            String sql = baseSql + " ORDER BY id LIMIT " + offset + ", " + PAGE_LIMIT;
            QueryResult<JSONObject> rt;
            try {
                rt = XoqlService.instance().query(sql);
            } catch (Exception e) {
                logger.error("MYSE-17 分页查询失败 sql=" + sql, e);
                throw new RuntimeException(e);
            }
            List<JSONObject> recs = rt == null ? null : rt.getRecords();
            int n = recs == null ? 0 : recs.size();
            if (n == 0) {
                break;
            }
            all.addAll(recs);
            offset += n;
            if (n < PAGE_LIMIT) {
                break;      // 不足一页 → 已取完
            }
        }
        if (offset >= MAX_FETCH_ROWS) {
            logger.warn("MYSE-17 分页查询达到上限 " + MAX_FETCH_ROWS + " 行，结果可能不完整 sql=" + baseSql);
        }
        return all;
    }

    /** 用平台内部 XOQL 查询并返回 id 列表。 */
    private List<Long> queryIds(String xoql) {
        List<Long> ids = new ArrayList<>();
        try {
            for (JSONObject rec : queryAll(xoql)) {
                if (rec == null) {
                    continue;
                }
                Long id = JsonValues.readLookupId(rec, "id");
                if (id != null) {
                    ids.add(id);
                }
            }
        } catch (Exception e) {
            logger.warn("MYSE-17 内部XOQL查询失败: " + xoql, e);
        }
        return ids;
    }

    /** 内部 XOQL 查询，返回 records 数组（查询失败或无数据时返回 null）。 */
    private JSONArray queryRecords(String xoql) {
        try {
            JSONArray records = new JSONArray();
            records.addAll(queryAll(xoql));
            return records;
        } catch (Exception e) {
            logger.warn("MYSE-17 内部XOQL查询失败: " + xoql, e);
        }
        return null;
    }

    /** 通用反查：SELECT id FROM entity WHERE field = 'value'（缓存优先）。 */
    private Long resolveByField(String entity, String field, String value, RefCache cache) {
        if (value == null) {
            return null;
        }
        if (cache != null && !cache.disabled) {
            Map<String, Long> map = cache.mapFor(entity, field);
            if (map != null) {
                // 批量查过没有就是没有 → 直接返回，不再二次查询
                return map.get(value.trim());
            }
        }
        try {
            String xoql = "SELECT id FROM " + entity + " WHERE " + field + " = '" + JsonValues.escape(value) + "'";
            List<Long> ids = queryIds(xoql);
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
        } catch (Exception e) {
            logger.warn("MYSE-17 反查失败 entity=" + entity + " " + field + "=" + value, e);
        }
        return null;
    }

    private Long findDeliveryIdByNumber(String dnNumber, RefCache cache) throws ApiEntityServiceException {
        if (cache != null && !cache.disabled) {
            // 批量查过没有就是没有 → 不再二次查询
            return cache.deliveryByNo.get(dnNumber == null ? null : dnNumber.trim());
        }
        String sql="SELECT id FROM delivery__c WHERE delivery__c = '" + JsonValues.escape(dnNumber) + "'";
        QueryResult<XObject> res=XObjectService.instance().query(sql,true);
        List<Long> ids = new ArrayList<>();
        for (XObject record : res.getRecords()) {
            ids.add(record.getId());
        }
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Long vkorgIdByNumber(String vkorg, RefCache cache) {
        if (cache != null && !cache.disabled) {
            // 批量查过没有就是没有 → 不再二次查询
            return cache.saleorgByName.get(vkorg == null ? null : vkorg.trim());
        }
        List<Long> ids = queryIds("SELECT id FROM saleorg__c WHERE name = '" + JsonValues.escape(vkorg) + "'");
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 按 SAP 客户号查账户：kunnr__c IN (原值, 去前导零值)。
     * SAP 传 KUNNR 常带前导零（如 0000160652），CRM kunnr__c 可能存去零形式（160652），两种形式都查。
     */
    private Long findAccountIdByKunnr(String kunnr, RefCache cache) {
        if (kunnr == null || kunnr.isEmpty()) {
            return null;
        }
        if (cache != null && !cache.disabled) {
            // 预取时已把「原值 + 去前导零」两种形式都放进缓存
            Long cached = cache.accountByKunnr.get(kunnr.trim());
            if (cached != null) {
                return cached;
            }
            String stripped = kunnr.trim().replaceFirst("^0+", "");
            if (!stripped.isEmpty()) {
                cached = cache.accountByKunnr.get(stripped);
            }
            return cached;
        }
        StringBuilder in = new StringBuilder("'").append(JsonValues.escape(kunnr)).append("'");
        String stripped = kunnr.replaceFirst("^0+", "");
        if (!stripped.isEmpty() && !stripped.equals(kunnr)) {
            in.append(",'").append(JsonValues.escape(stripped)).append("'");
        }
        try {
            List<Long> ids = queryIds("SELECT id FROM account WHERE kunnr__c IN (" + in + ")");
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
        } catch (Exception e) {
            logger.warn("MYSE-17 按客户号反查账户失败 KUNNR=" + kunnr, e);
        }
        return null;
    }

    /**
     * 按 SAP 省份代码取省份名称：region__c.provinceCode__c → provinceName__c。
     * <p>SAP 的 REGION 可能带/不带前导零（如 010 / 10），先精确匹配，再按去前导零 / 左补零到 3 位依次兜底。</p>
     */
    private String findProvinceNameByCode(String code, RefCache cache) {
        if (code == null) {
            return null;
        }
        if (cache != null && !cache.disabled) {
            // 预取时已把「原值 / 去零 / 左补零3位」四种候选都放进缓存
            for (String c : provinceCandidates(code)) {
                String name = cache.provinceByCode.get(c);
                if (name != null && !name.trim().isEmpty()) {
                    return name;
                }
            }
            // 批量查过没有就是没有 → 不再二次查询
            return null;
        }
        // 无预取上下文（不按批处理时的单张调用）→ 实时查一次
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, code);
        String stripped = code.replaceFirst("^0+", "");
        addCandidate(candidates, stripped);
        addCandidate(candidates, padProvinceCode(code));
        addCandidate(candidates, padProvinceCode(stripped));
        for (String c : candidates) {
            JSONArray records = queryRecords("SELECT provinceName__c FROM region__c"
                    + " WHERE provinceCode__c = '" + JsonValues.escape(c) + "'");
            if (records != null && records.size() > 0) {
                String name = JsonValues.trimToNull(JsonValues.asString(records.getJSONObject(0).get("provinceName__c")));
                if (name != null) {
                    return name;
                }
            }
        }
        return null;
    }

    /** 反查订单明细：按 order__c + 行号（尝试原值与补零到 5 位，兼容 SAP 0010 / 00010 差异）。 */
    private Long resolveOrderDetailByLine(Long orderId, String lineNo, RefCache cache) {
        if (lineNo == null) {
            return null;
        }
        if (cache != null && !cache.disabled) {
            Map<String, Long> posMap = cache.orderPosByOrder.get(orderId);
            if (posMap == null) {
                return null;   // 该订单下没有明细行（预取已覆盖全批）
            }
            for (String c : orderLineCandidates(lineNo)) {
                Long id = posMap.get(c);
                if (id != null) {
                    return id;
                }
            }
            // 批量查过没有就是没有 → 不再二次查询
            return null;
        }
        // 无预取上下文（不按批处理时的单张调用）→ 实时查一次
        StringBuilder in = new StringBuilder("'").append(JsonValues.escape(lineNo)).append("'");
        if (lineNo.matches("\\d+") && lineNo.length() < 5) {
            in.append(",'").append(String.format("%05d", Integer.parseInt(lineNo))).append("'");
        }
        String xoql = "SELECT id FROM orderDetail__c WHERE order__c = " + orderId
                + " AND posicion__c IN (" + in + ")";
        List<Long> ids = queryIds(xoql);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 取某发货单下子明细 id。
     *
     * @param isNew 是否新建单据（新建的 id 刚生成，库里不可能有旧明细 → 直接返回空，不查询）
     */
    private List<Long> childIdsOf(Long deliveryId, String childEntity, RefCache cache, boolean isNew) {
        if (isNew) {
            return Collections.emptyList();
        }
        if (cache != null && !cache.disabled) {
            List<Long> cached = "packingDetail__c".equals(childEntity)
                    ? cache.packingIdsByDelivery.get(deliveryId)
                    : cache.detailIdsByDelivery.get(deliveryId);
            if (cached != null) {
                return cached;
            }
        }
        return queryIds("SELECT id FROM " + childEntity + " WHERE delivery__c = " + deliveryId);
    }

    /** 取某发货单全部明细所关联的订单明细 id（用于在删除旧明细前记录影响范围）。 */
    private Set<Long> queryLinkedOrderDetailIds(Long deliveryId, RefCache cache) {
        Set<Long> ids = new LinkedHashSet<>();
        if (cache != null && !cache.disabled) {
            Set<Long> cached = cache.linkedOrderDetailByDelivery.get(deliveryId);
            if (cached != null) {
                return cached;
            }
            return ids;
        }
        JSONArray records = queryRecords("SELECT orderLineNumber__c FROM deliveryDetail__c WHERE delivery__c = " + deliveryId);
        if (records == null) {
            return ids;
        }
        for (int i = 0; i < records.size(); i++) {
            Long odId = JsonValues.readLookupId(records.getJSONObject(i), "orderLineNumber__c");
            if (odId != null) {
                ids.add(odId);
            }
        }
        return ids;
    }

    // ======================================================================
    // 候选值归一 / 工具
    // ======================================================================

    /** 省份代码候选：原值 / 去前导零 / 左补零 3 位 */
    private List<String> provinceCandidates(String code) {
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, code);
        String stripped = code == null ? null : code.replaceFirst("^0+", "");
        addCandidate(candidates, stripped);
        addCandidate(candidates, padProvinceCode(code));
        addCandidate(candidates, padProvinceCode(stripped));
        return candidates;
    }

    /** 订单行号候选：原值 + 补零到 5 位（SAP 0010 / 00010 位宽差异） */
    private List<String> orderLineCandidates(String lineNo) {
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, lineNo);
        if (lineNo != null && lineNo.matches("\\d+") && lineNo.length() < 5) {
            addCandidate(candidates, String.format("%05d", Integer.parseInt(lineNo)));
        }
        return candidates;
    }

    /** 省份代码左补零到 3 位（如 10 → 010），非数字或已够长则原样返回。 */
    private String padProvinceCode(String s) {
        if (s == null || s.isEmpty() || s.length() >= 3 || !s.matches("\\d+")) {
            return s;
        }
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 3) {
            b.insert(0, '0');
        }
        return b.toString();
    }

    private void addCandidate(List<String> list, String s) {
        if (s != null && !s.isEmpty() && !list.contains(s)) {
            list.add(s);
        }
    }

    /** 把 id 列表拼成 SQL IN 子句的逗号串。 */
    private static String joinIds(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(id);
        }
        return sb.toString();
    }

    // ======================================================================
    // 内部静态嵌套类（纯数据 / 配置）
    // ======================================================================

    /**
     * 预取缓存：整批报文只查一次库，把所有需要的关联 id 装进内存字典，
     * 循环内零查询。每类反查<b>独立降级</b>：某类失败只标记该类，其它类继续用缓存。
     */
    private static class RefCache {
        /** delivery__c.delivery__c（DN 号） → id */
        Map<String, Long> deliveryByNo = new HashMap<>();
        /** saleorg__c.name（销售组织编号） → id */
        Map<String, Long> saleorgByName = new HashMap<>();
        /** account.kunnr__c（客户号，含去零/带零两种形式） → id */
        Map<String, Long> accountByKunnr = new HashMap<>();
        /** product.productCode__c（货号） → id */
        Map<String, Long> productByCode = new HashMap<>();
        /** order__c.orderNumberAutoGenerate__c（订单号） → id */
        Map<String, Long> orderByNo = new HashMap<>();
        /** deliveryDetail__c.lineNumber__c（发货行号） → id */
        Map<String, Long> deliveryDetailByLine = new HashMap<>();
        /** region__c.provinceCode__c（省份代码） → provinceName__c */
        Map<String, String> provinceByCode = new HashMap<>();
        /** orderId → (posicion__c → orderDetail__c.id) */
        Map<Long, Map<String, Long>> orderPosByOrder = new HashMap<>();
        /** 发货单 id → deliveryDetail__c 明细 id 列表 */
        Map<Long, List<Long>> detailIdsByDelivery = new HashMap<>();
        /** 发货单 id → packingDetail__c 明细 id 列表 */
        Map<Long, List<Long>> packingIdsByDelivery = new HashMap<>();
        /** 发货单 id → 其明细关联的订单明细 id 集合 */
        Map<Long, Set<Long>> linkedOrderDetailByDelivery = new HashMap<>();
        /** 删除留存快照：发货单 id → 主记录 JSON */
        Map<Long, String> deliverySnapshotJson = new HashMap<>();
        /** 删除留存快照：发货单 id → 明细 JSON 数组 */
        Map<Long, String> detailSnapshotJson = new HashMap<>();
        /** 删除留存快照：发货单 id → 装箱明细 JSON 数组 */
        Map<Long, String> packingSnapshotJson = new HashMap<>();
        /** 预取失败时置 true → 全部退化为逐条实时查询（保证功能不挂） */
        boolean disabled = false;
        /** 预取失败的「对象+字段」组合（key = entity#field） */
        Set<String> failedLookups = new HashSet<>();
        /** 本批受影响的订单明细 id（批次末尾统一批量重算已发货数量） */
        Set<Long> affectedOrderDetailIds = new LinkedHashSet<>();

        void markFailed(String entity, String field) {
            failedLookups.add(entity + "#" + field);
        }

        /** 按对象+字段取对应的缓存 map；无对应缓存返回 null（调用方转实时查询）。 */
        Map<String, Long> mapFor(String entity, String field) {
            if ("product".equals(entity) && "productCode__c".equals(field)) {
                return productByCode;
            }
            if ("order__c".equals(entity) && "orderNumberAutoGenerate__c".equals(field)) {
                return orderByNo;
            }
            if ("deliveryDetail__c".equals(entity) && "lineNumber__c".equals(field)) {
                return deliveryDetailByLine;
            }
            if ("delivery__c".equals(entity) && "delivery__c".equals(field)) {
                return deliveryByNo;
            }
            if ("saleorg__c".equals(entity) && "name".equals(field)) {
                return saleorgByName;
            }
            if ("account".equals(entity) && "kunnr__c".equals(field)) {
                return accountByKunnr;
            }
            return null;
        }
    }

    /** 单据准备结果（阶段 B 解析产出 → 阶段 C 批量落库消费）。 */
    private static class PreparedDelivery {
        /** 处理结果（对外出参） */
        JSONObject result = new JSONObject();
        /** 操作码：A/C/U = upsert，D = 删除 */
        String actionCode = "A";
        /** 交货单号 */
        String dnNumber;
        /** 是否删除类单据 */
        boolean isDelete = false;
        /** 是否新增（false = 更新） */
        boolean isNew = true;
        /** 解析失败（不参与落库） */
        boolean failed = false;
        /** 主记录对象（未落库） */
        Delivery__c delivery;
        /** 发货明细（未落库，未设 delivery__c） */
        List<DeliveryDetail__c> lines = new ArrayList<>();
        /** 装箱明细（未落库，未设 delivery__c） */
        List<PackingDetail__c> packs = new ArrayList<>();
        /** 装箱明细 → LINENUMBER 原文（发货单明细落库后，按行号查库回填 lineNumber__c） */
        Map<PackingDetail__c, String> packLineNos = new HashMap<>();
        /** 发货单 id（新增成功后回填） */
        Long deliveryId;
        /** 删除的子明细条数 */
        int deletedItemCount;
        /** 插入的发货明细条数 */
        int insertedLineCount;
        /** 插入的装箱明细条数 */
        int insertedPackCount;
        /** 本单受影响的订单明细 id（批次末尾统一重算已发货数量） */
        Set<Long> affectedOrderDetailIds = new LinkedHashSet<>();
    }

    /**
     * Myse17 用到的三个自定义对象的<b>业务类型（entityType）</b>。
     *
     * <p><b>为什么不能写死</b>：业务类型 id 是<b>租户级</b>的 —— 同一份配置集里的值
     * 在 A 租户有效、换到 B 租户就是无效值（表现为子表批量插入整批失败）。</p>
     *
     * <p>因此这里做两级处理：</p>
     * <ol>
     *   <li><b>运行时按当前租户元数据解析</b>（{@code /rest/metadata/v3.1/xobjects/{apiKey}/busiTypes}），
     *       结果缓存，同一对象只查一次；</li>
     *   <li>解析不到时<b>回退配置集常量</b>（{@code CustomConfigService.getConfigSet("entityType")}）。</li>
     * </ol>
     */
    private static final class BusinessTypes {
        /** 发货单 delivery__c */
        static final Long DELIVERY;
        /** 发货单明细 deliveryDetail__c */
        static final Long DELIVERY_DETAIL;
        /** 装箱单明细 packingDetail__c */
        static final Long PACKING_DETAIL;
        /**
         * 删除留存对象 deliveryDeleteLog__c 的业务类型。
         * 为 null 表示配置集里没配该项 → 删除留存自动降级为「只删不记」，不阻断删除主流程。
         */
        static final Long DELETE_LOG;

        /** 运行时解析结果缓存（对象 apiKey → busiType id） */
        private static final Map<String, Long> CACHE = new HashMap<>();

        static {
            Long delivery = null, detail = null, packing = null, deleteLog = null;
            try {
                Map<String, String> configSet = CustomConfigService.instance().getConfigSet("entityType");
                delivery = parseLong(configSet.get("DELIVERY_BUSI_TYPE"));
                detail = parseLong(configSet.get("DELIVERY_DETAIL_BUSI_TYPE"));
                packing = parseLong(configSet.get("PACKING_DETAIL_BUSI_TYPE"));
                deleteLog = parseLong(configSet.get("DELIVERY_DELETE_LOG_BUSI_TYPE"));
            } catch (CustomConfigException e) {
                throw new RuntimeException(e);
            }
            DELIVERY = delivery;
            DELIVERY_DETAIL = detail;
            PACKING_DETAIL = packing;
            DELETE_LOG = deleteLog;
            if (DELETE_LOG == null) {
                logger.warn("MYSE-17 配置集 entityType 缺少 DELIVERY_DELETE_LOG_BUSI_TYPE，"
                        + "发货单删除留存功能将降级为不记录（不影响删除主流程）");
            }
        }

        private BusinessTypes() {
        }

        /**
         * 解析对象的业务类型（当前租户自适应）。
         *
         * @param entityApiName 对象 apiKey，如 deliveryDetail__c
         * @param fallback      解析不到时的兜底值（配置集常量，可为 null）
         * @return 业务类型 id；两者都拿不到时返回 null（交由平台用默认业务类型）
         */
        static Long resolve(String entityApiName, Long fallback) {
            if (CACHE.containsKey(entityApiName)) {
                Long cached = CACHE.get(entityApiName);
                return cached != null ? cached : fallback;
            }
            Long id = null;
            try {
                RkhdHttpData httpData = RkhdHttpData.newBuilder()
                        .callString("/rest/metadata/v3.1/xobjects/" + entityApiName + "/busiTypes")
                        .callType("GET")
                        .build();
                String respStr = RkhdHttpClient.instance().performRequest(httpData);
                if (respStr != null && !respStr.trim().isEmpty()) {
                    JSONArray arr = extractBusiTypes(JSON.parseObject(respStr));
                    if (arr != null && !arr.isEmpty()) {
                        Object bid = arr.getJSONObject(0).get("id");
                        if (bid != null) {
                            id = Long.parseLong(bid.toString());
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("MYSE-17 解析业务类型失败 entity=" + entityApiName + " : " + e.getMessage());
            }
            CACHE.put(entityApiName, id);
            if (id == null) {
                logger.warn("MYSE-17 未解析到 " + entityApiName + " 的业务类型，回退配置集值 " + fallback);
            }
            return id != null ? id : fallback;
        }

        /** 从元数据响应里取出业务类型数组（兼容多种返回结构） */
        private static JSONArray extractBusiTypes(JSONObject resp) {
            if (resp == null) {
                return null;
            }
            if (resp.get("data") instanceof JSONArray) {
                return resp.getJSONArray("data");
            }
            if (resp.get("records") instanceof JSONArray) {
                return resp.getJSONArray("records");
            }
            if (resp.get("data") instanceof JSONObject) {
                JSONObject d = resp.getJSONObject("data");
                if (d.get("records") instanceof JSONArray) {
                    return d.getJSONArray("records");
                }
            }
            return null;
        }

        /** 安全转 Long：空值/非数字返回 null（不抛 NumberFormatException，避免类加载失败整个接口挂掉） */
        private static Long parseLong(String s) {
            if (s == null || s.trim().isEmpty()) {
                return null;
            }
            try {
                return Long.parseLong(s.trim());
            } catch (Exception e) {
                logger.warn("MYSE-17 业务类型配置值非法: " + s);
                return null;
            }
        }
    }
}
