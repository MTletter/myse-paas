package other.xsy.myse.apis;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.XoqlService;
import org.apache.commons.lang.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static other.xsy.myse.utils.JsonValues.formatDateSAP;
import static other.xsy.myse.utils.JsonValues.nullSafe;
import static other.xsy.myse.utils.JsonValues.trimToNull;
import static other.xsy.myse.utils.StringUtil.padKunnr;

/**
 * 报价单出站接口（MYSE-02 模拟 / MYSE-03 创建）公共基类。
 *
 * <p>两个接口的辅助查询、VKORG 解析、选型配置块、报文构建原先各写一份（逐行相同），
 * 集中到这里；子类只保留各自的入口流程、PI 配置与差异逻辑
 * （02 的草稿模式 / 03 的落库与消息通知）。</p>
 *
 * <p><b>唯一的行为分叉</b>：Schneider 选型 XML 的获取方式两边不同
 * （02 硬编码地址/token，03 走 SystemConfig MYSE-SCHNEIDER），
 * 故 {@link #fetchSchneiderXml(String)} 留为抽象方法由子类各自实现。</p>
 *
 * <p>日志前缀通过 {@link #tag()} 取子类自己的 TAG（MYSE-02 / MYSE-03）。</p>
 */
public abstract class QuotationApiBase {

    protected static final Logger logger = LoggerFactory.getLogger();

    /**
     * 需要回传 VKORG 的销售组织编码（saleorg__c.name），按数组顺序优先命中。
     * 其余销售组织（如 N001/I001/M001…）不传 VKORG，字段留空。
     */
    protected static final String[] VKORG_ALLOWED_CODES = {"WG01", "IB01"};

    /** 子类返回自己的日志 TAG（MYSE-02 / MYSE-03） */
    protected abstract String tag();

    /**
     * 调用 Schneider 选型配置器 hive 接口取 XML，失败返回 null。
     * 两个子类实现不同（02 硬编码地址/token；03 走 SystemConfig），保持各自原有行为。
     */
    protected abstract String fetchSchneiderXml(String configId);

    // ===================== SAP 报文构建 =====================

    /**
     * 构建 SAP 报价单入参报文（02 模拟 Z_BAPI_SIMULATE_QUOTATION 与 03 创建 Z_BAPI_QUOTATION_ENTRY 结构一致）。
     *
     * <p>头字段差异由参数传入：模拟时 QUOTATIONNUMBER/QUOTATIONCREATEDATE/QO_NUMBER 传空串，
     * 创建时传报价单真实值。</p>
     *
     * @param quotationNumber    报价单号 → QUOTATIONNUMBER
     * @param quotationCreateDate 创建日期（yyyy-MM-dd）→ QUOTATIONCREATEDATE
     * @param qoNumber           采购单编号 → QO_NUMBER
     * @param details            明细列表（JSONObject，含全部字段）
     * @param productNameMap     产品 id → productName 映射
     * @param companyCode        销售组织公司代码（saleorg__c.companyCode__c）
     * @param kunnr              售达方客户代码（account.kunnr__c）→ PURCHASERID
     * @param shipToKunnr        送达方客户代码（shipToParty__c.kunnr__c）→ 头 CUSTOMERID
     * @param vkorg              销售组织编码，仅 WG01/IB01 回传，其余为空串
     */
    protected JSONObject buildPayload(String quotationNumber, String quotationCreateDate, String qoNumber,
                                      List<JSONObject> details, Map<Long, String> productNameMap,
                                      String companyCode, String kunnr, String shipToKunnr, String vkorg) {
        JSONObject root = new JSONObject();

        // ===================== HEADER =====================
        JSONObject header = new JSONObject();
        header.put("QUOTATIONNUMBER", quotationNumber);
        header.put("PURCHASERID", nullSafe(kunnr));                                        // 售达方客户代码 → account.kunnr__c（SAP CHAR10，不能放 NeoCRM 内部账户ID）
        header.put("CUSTOMERID", nullSafe(shipToKunnr));                            // 送达方客户代码 → shipToParty__c.kunnr__c（口径同 MYSE-06）
        header.put("COMPANYCODE", nullSafe(companyCode));                                  // 公司代码 → saleorg__c.companyCode__c
        header.put("REQUIREDDELIVERYDATE", "");                                            // 期望发货日期（暂空）
        header.put("QUOTATIONCREATEDATE", quotationCreateDate);
        header.put("QO_NUMBER", qoNumber);
        header.put("CARRIER", "");                                                         // 承运人（暂空）
        header.put("QUOTATIONMARKINGS", "");                                               // 行标记（暂空）
        header.put("VKORG", nullSafe(vkorg));                                              // 销售组织编码 → saleorg__c.name，仅 WG01/IB01 回传，其余留空
        root.put("HEADER", header);

        // ===================== ITEM_BOM_NO（全空）=====================
        JSONObject itemBomNoItem = new JSONObject();
        itemBomNoItem.put("CONFIG_ITEMNO", "");
        itemBomNoItem.put("PARENT_ID", "");
        itemBomNoItem.put("INSTANCE_ID", "");
        itemBomNoItem.put("PART_OF_NO", "");
        itemBomNoItem.put("SUBITEM", "");
        JSONObject itemBomNo = new JSONObject();
        itemBomNo.put("item", itemBomNoItem);
        root.put("ITEM_BOM_NO", itemBomNo);

        // ===================== ITEM_CHAR_VALUE / ITEM_INST_ID（来自 Schneider 选型 XML）=====================
        // XML 来源：① 明细带 selectionXml__c（前端草稿直传）→ 直接用不调接口；
        //          ② 否则按 selectionId__c（持久化配置ID）调 hive 接口取；
        //          ③ 两者都无 → 该行不产生配置数据（保持空数组，不污染 SAP 报文）。
        Map<String, String> configXmlCache = new HashMap<>();
        JSONArray itemCharValueItems = new JSONArray();
        JSONArray itemInstIdItems = new JSONArray();
        fillConfigBlocks(details, itemCharValueItems, itemInstIdItems, configXmlCache);
        JSONObject itemCharValue = new JSONObject();
        itemCharValue.put("item", itemCharValueItems);
        root.put("ITEM_CHAR_VALUE", itemCharValue);
        JSONObject itemInstId = new JSONObject();
        itemInstId.put("item", itemInstIdItems);
        root.put("ITEM_INST_ID", itemInstId);

        // ===================== ITEM_IN（明细行）=====================
        JSONArray itemInItems = new JSONArray();
        for (JSONObject d : details) {
            JSONObject item = new JSONObject();
            Long productId = d.getLong("catalogNumber__c");
            item.put("LINENUMBER", toInt(d.get("posicion__c")));                         // 行号 → posicion__c
            item.put("CATALOGNUMBER", productNameMap.getOrDefault(productId, ""));       // 订货号 → product.productName
            item.put("REQ_QTY", d.get("quantity__c"));                                   // 数量 → quantity__c
            item.put("DONOTSHIPBEFOREDATE", formatDateSAP(d.getLong("requestedDeliveryDate__c"))); // 期望发货日期
            if (!itemCharValueItems.isEmpty()) {
                item.put("MASTER_PACT_CONFIG_ID", StringUtils.isNotBlank(d.getString("configId")) ? d.getString("configId") : itemCharValueItems.getJSONObject(0).getString("VALUE"));
            }
            // 行级送达方客户代码：明细发货地址(shipTo__c → shipToParty__c).kunnr__c，
            // 缺失时回退到表头送达方（口径同 MYSE-06 的 resolveLineCustomerId）
            item.put("CUSTOMERID", resolveLineCustomerId(d, shipToKunnr));
            itemInItems.add(item);
        }
        JSONObject itemIn = new JSONObject();
        itemIn.put("item", itemInItems);
        root.put("ITEM_IN", itemIn);

        return root;
    }

    // ===================== 辅助查询方法 =====================

    /**
     * 批量反查产品名称：catalogNumber__c(product id) → product.productName
     */
    protected Map<Long, String> queryProductNames(List<JSONObject> details) {
        Map<Long, String> map = new HashMap<>();
        if (details == null || details.isEmpty()) {
            return map;
        }
        Set<Long> productIds = details.stream()
                .map(d -> d.getLong("catalogNumber__c"))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (productIds.isEmpty()) {
            return map;
        }
        try {
            StringBuilder inSb = new StringBuilder();
            for (Long pid : productIds) {
                if (inSb.length() > 0) inSb.append(",");
                inSb.append(pid);
            }
            String sql = "SELECT id, productName FROM product WHERE id IN (" + inSb.toString() + ")";
            QueryResult<JSONObject> res = XoqlService.instance().query(sql);
            if (res != null && res.getRecords() != null) {
                for (Object o : res.getRecords()) {
                    if (!(o instanceof JSONObject)) continue;
                    JSONObject p = (JSONObject) o;
                    map.put(p.getLong("id"), p.getString("productName"));
                }
            }
        } catch (Exception e) {
            logger.warn(tag() + " 反查产品名称失败", e);
        }
        return map;
    }

    /**
     * 解析 VKORG：明细产品 → 产品销售组织（productOrg__c.salesOrg__c）→ saleorg__c.name。
     * <p>
     * 仅当销售组织编码命中 {@link #VKORG_ALLOWED_CODES}（WG01 / IB01）时回传该编码，
     * 命中多个时按数组顺序优先（WG01 优先于 IB01）；其余销售组织、查不到或异常一律返回空串。
     * </p>
     */
    protected String resolveVkorg(List<JSONObject> details) {
        try {
            Set<Long> productIds = new LinkedHashSet<>();
            if (details != null) {
                for (JSONObject d : details) {
                    Long pid = d == null ? null : d.getLong("catalogNumber__c");
                    if (pid != null) {
                        productIds.add(pid);
                    }
                }
            }
            if (productIds.isEmpty()) {
                return "";
            }
            Set<Long> salesOrgIds = queryProductSalesOrgIds(productIds);
            if (salesOrgIds.isEmpty()) {
                logger.info(tag() + " 未查到产品销售组织，VKORG 留空 productIds=" + productIds);
                return "";
            }
            // 只关心 WG01/IB01 两个编码对应的销售组织 id
            StringBuilder in = new StringBuilder();
            for (String code : VKORG_ALLOWED_CODES) {
                if (in.length() > 0) {
                    in.append(",");
                }
                in.append("'").append(code.replace("'", "''")).append("'");
            }
            QueryResult<JSONObject> res = XoqlService.instance().query(
                    "SELECT id, name FROM saleorg__c WHERE name IN (" + in + ") LIMIT 0, 50");
            Map<String, Long> codeToId = new HashMap<>();
            if (res != null && res.getRecords() != null) {
                for (Object o : res.getRecords()) {
                    if (!(o instanceof JSONObject)) {
                        continue;
                    }
                    JSONObject row = (JSONObject) o;
                    Long id = readId(row.get("id"));
                    String name = trimToNull(row.getString("name"));
                    if (id != null && id != 0L && name != null) {
                        codeToId.put(name, id);
                    }
                }
            }
            // 按 VKORG_ALLOWED_CODES 顺序检查，保证多命中时结果确定
            for (String code : VKORG_ALLOWED_CODES) {
                Long id = codeToId.get(code);
                if (id != null && salesOrgIds.contains(id)) {
                    logger.info(tag() + " VKORG 命中销售组织编码=" + code + " salesOrgId=" + id);
                    return code;
                }
            }
            logger.info(tag() + " 产品销售组织编码不在 WG01/IB01 内，VKORG 留空 salesOrgIds=" + salesOrgIds);
        } catch (Exception e) {
            logger.warn(tag() + " 解析 VKORG 失败，留空", e);
        }
        return "";
    }

    /**
     * 查明细产品对应的销售组织 id 集合：productOrg__c.product__c → salesOrg__c（去重）。
     * 一个产品通常有多条产品组织行（对应多个销售组织），此处全量收集后由调用方判定。
     */
    protected Set<Long> queryProductSalesOrgIds(Set<Long> productIds) {
        Set<Long> ids = new LinkedHashSet<>();
        if (productIds == null || productIds.isEmpty()) {
            return ids;
        }
        try {
            StringBuilder inSb = new StringBuilder();
            for (Long pid : productIds) {
                if (inSb.length() > 0) {
                    inSb.append(",");
                }
                inSb.append(pid);
            }
            String sql = "SELECT product__c, salesOrg__c FROM productOrg__c WHERE product__c IN ("
                    + inSb + ") LIMIT 0, 5000";
            logger.info(tag() + " queryProductSalesOrgIds sql:" + sql);
            QueryResult<JSONObject> res = XoqlService.instance().query(sql);
            if (res != null && res.getRecords() != null) {
                for (Object o : res.getRecords()) {
                    if (!(o instanceof JSONObject)) {
                        continue;
                    }
                    Long so = readId(((JSONObject) o).get("salesOrg__c"));
                    if (so != null && so != 0L) {
                        ids.add(so);
                    }
                }
            }
            logger.info(tag() + " 产品命中销售组织: " + ids);
        } catch (Exception e) {
            logger.warn(tag() + " 查产品销售组织失败", e);
        }
        return ids;
    }

    /**
     * 查客户代码：account.id → kunnr__c（SAP 客户编号）
     */
    protected String queryKunnr(Long accountId) {
        if (accountId == null) return "";
        try {
            String sql = "SELECT id, kunnr__c FROM account WHERE id = " + accountId + " LIMIT 1";
            QueryResult<JSONObject> res = XoqlService.instance().query(sql);
            if (res != null && res.getRecords() != null && !res.getRecords().isEmpty()) {
                JSONObject row = res.getRecords().get(0);
                return row.getString("kunnr__c");
            }
        } catch (Exception e) {
            logger.warn(tag() + " 查客户代码失败 accountId=" + accountId, e);
        }
        return "";
    }

    /**
     * 反查发货地址的客户代码：shipToParty__c.id → kunnr__c，并补零到 10 位。
     * <p>报价单的送达方字段是 {@code shipTo__c}（reference → shipToParty__c）；
     * {@code shipToCode__c} 只是 shipToID__c 的 join 展示字段，不可写，不作为数据源。</p>
     *
     * @param shipToId shipToParty__c 主键（quote__c.shipTo__c）
     * @return 补零后的 kunnr；无值/查不到返回空串
     */
    protected String queryShipToKunnr(Long shipToId) {
        if (shipToId == null) {
            logger.warn(tag() + " 报价单未维护 shipTo__c（发货地址），CUSTOMERID 下发空串");
            return "";
        }
        try {
            String sql = "SELECT id, kunnr__c FROM shipToParty__c WHERE id = " + shipToId + " LIMIT 1";
            QueryResult<JSONObject> res = XoqlService.instance().query(sql);
            if (res != null && res.getRecords() != null && !res.getRecords().isEmpty()) {
                String k = res.getRecords().get(0).getString("kunnr__c");
                return k == null ? "" : padKunnr(k);
            }
            logger.warn(tag() + " 未找到发货地址 shipToParty__c: id=" + shipToId);
        } catch (Exception e) {
            logger.warn(tag() + " 查发货地址客户代码失败 shipToId=" + shipToId, e);
        }
        return "";
    }

    /**
     * 取行级送达方客户代码（明细 CUSTOMERID）。
     * <p>取值优先级：行上 {@code kunnr}（库内查询已 join {@code shipTo__c.kunnr__c kunnr}，
     * 或前端草稿直接传）→ 行上 {@code shipTo__c.kunnr__c}（lookup 对象）→
     * 回退表头送达方 → 都没有则空串并告警。</p>
     *
     * @param line              报价单明细行
     * @param headerShipToKunnr 表头送达方客户代码（行级缺失时的回退值）
     */
    protected String resolveLineCustomerId(JSONObject line, String headerShipToKunnr) {
        if (line == null) {
            return "";
        }
        String kunnr = trimToNull(line.getString("kunnr"));
        if (kunnr == null) {
            Object shipToObj = line.get("shipTo__c");
            if (shipToObj instanceof JSONObject) {
                kunnr = trimToNull(((JSONObject) shipToObj).getString("kunnr__c"));
            }
        }
        if (kunnr != null) {
            return padKunnr(kunnr);
        }
        if (headerShipToKunnr != null && !headerShipToKunnr.isEmpty()) {
            logger.info(tag() + " 明细行缺发货地址，回退表头 CUSTOMERID: 行号=" + line.get("posicion__c")
                    + " → " + headerShipToKunnr);
            return headerShipToKunnr;
        }
        logger.warn(tag() + " 明细行缺客户代码(CUSTOMERID 将下发空串): 行号=" + line.get("posicion__c"));
        return "";
    }

    // ===================== 选型配置块（ITEM_CHAR_VALUE / ITEM_INST_ID）=====================

    /**
     * 解析各明细的选型配置，填充 ITEM_CHAR_VALUE 与 ITEM_INST_ID。
     *
     * <p><b>XML 来源优先级（2026-10-17）</b>：</p>
     * <ol>
     *   <li>明细带 {@code selectionXml__c}（前端草稿直接传 XML）→ <b>直接用，不调hive 接口</b>；</li>
     *   <li>否则明细带 {@code selectionId__c}（持久化配置ID）→ 调 hive 接口取 XML；</li>
     *   <li>两者都没有 → 该行不产生配置数据（保持空数组，不污染 SAP 报文）。</li>
     * </ol>
     *
     * @param details        报价单明细
     * @param charValueItems 输出：ITEM_CHAR_VALUE 的 item 数组
     * @param instIdItems    输出：ITEM_INST_ID 的 item 数组
     * @param xmlCache       同一请求内按 configId 缓存 hive 接口返回的 XML，避免重复调用
     */
    protected void fillConfigBlocks(List<JSONObject> details,
                                    JSONArray charValueItems, JSONArray instIdItems,
                                    Map<String, String> xmlCache) {
        if (details == null) return;
        int fromFrontend = 0;
        int fromApi = 0;
        int none = 0;
        for (JSONObject d : details) {
            // ① 优先用前端草稿直传的 XML（无需调 hive 接口）
            Object rawXmlObj = d.get("selectionXml__c");
            String xml = rawXmlObj == null ? null : trimToNull(String.valueOf(rawXmlObj));
            if (xml != null) {
                fromFrontend++;
            } else {
                // ② 回退：按 selectionId__c 调 hive 接口取 XML
                Object rawIdObj = d.get("selectionId__c");
                String configId = rawIdObj == null ? null : trimToNull(String.valueOf(rawIdObj));
                if (configId == null) {
                    none++;
                    continue;
                }
                xml = xmlCache.get(configId);
                if (xml == null) {
                    xml = fetchSchneiderXml(configId);
                    xmlCache.put(configId, xml); // 即使为 null 也缓存，避免对坏配置反复调用
                }
                fromApi++;
            }
            if (StringUtils.isBlank(xml)) {
                continue;
            }
            List<ConfigInstance> instances = parseConfigXml(xml);
            if (instances.isEmpty()) {
                continue;
            }
            // CONFIG_ITEMNO = 明细行号（posicion__c）补零6位，将特性/实例绑定到报价单行
            String itemNo = pad6(d.get("posicion__c"));
            for (ConfigInstance inst : instances) {
                String instId = pad8(inst.id);
                // ITEM_CHAR_VALUE：每个 cstic → 一行
                if (inst.cstics != null) {
                    for (Cstic c : inst.cstics) {
                        JSONObject cv = new JSONObject();
                        cv.put("CONFIG_ITEMNO", itemNo);
                        cv.put("INSTANCE_ID", instId);
                        cv.put("CHARC", nullSafe(c.csticId));
                        cv.put("VALUE", nullSafe(c.valueId));
                        charValueItems.add(cv);
                    }
                }
                // ITEM_INST_ID：每个 instance → 一行
                JSONObject ii = new JSONObject();
                ii.put("CONFIG_ITEMNO", itemNo);
                ii.put("INSTANCE_ID", instId);
                ii.put("MAIN_SUB_ITEM", nullSafe(inst.matnr));   // 实例物料号（MAIN_SUB_ITEM 语义待与 SAP 确认）
                ii.put("QUANTITY", nullSafe(inst.qty));          // 实例数量
                instIdItems.add(ii);
            }
        }
        logger.info(tag() + " 选型配置来源统计: 前端直传 XML(selectionXml__c)=" + fromFrontend
                + " 行, 按 selectionId__c 调接口=" + fromApi + " 行, 两者都无=" + none + " 行"
                + ", hive 接口缓存=" + xmlCache.size() + " 条,已经配置XML的：" + charValueItems.size());
    }

    /**
     * 解析选型 XML：&lt;config&gt;&lt;instance ...&gt;&lt;cstic .../&gt; [嵌套 &lt;instance&gt;...]&lt;/instance&gt;&lt;/config&gt;
     * 支持 instance 内再嵌套 instance（递归收集到 ITEM_INST_ID），且每个 instance 仅取「直接子 cstic」
     * 避免把子 instance 的特性值重复算到父 instance 上。
     */
    protected List<ConfigInstance> parseConfigXml(String xml) {
        List<ConfigInstance> result = new ArrayList<>();
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
            doc.getDocumentElement().normalize();
            Element root = doc.getDocumentElement();
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE && "instance".equals(n.getNodeName())) {
                    walkInstance((Element) n, result);
                }
            }
        } catch (Exception e) {
            String snippet = (xml == null ? "null" : xml.substring(0, Math.min(500, xml.length())));
            logger.error(tag() + " 解析选型XML失败, 原始内容前500字符=[" + snippet + "]", e);
        }
        return result;
    }

    /** 递归处理单个 instance：仅收集直接子 &lt;cstic&gt;，再递归收集嵌套 &lt;instance&gt; */
    protected void walkInstance(Element ie, List<ConfigInstance> result) {
        ConfigInstance ci = new ConfigInstance();
        ci.id = ie.getAttribute("id");
        ci.matnr = ie.getAttribute("matnr");
        ci.parentId = ie.getAttribute("parent_Id");
        ci.posnr = ie.getAttribute("posnr");
        ci.qty = ie.getAttribute("qty");
        ci.cstics = new ArrayList<>();
        NodeList children = ie.getChildNodes();
        // 第一遍：直接子 cstic（不含嵌套 instance 内部的）
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c.getNodeType() != Node.ELEMENT_NODE) continue;
            if (!"cstic".equals(c.getNodeName())) continue;
            Element ce = (Element) c;
            Cstic cs = new Cstic();
            cs.csticId = ce.getAttribute("cstic_id");
            cs.valueId = ce.getAttribute("value_id");
            ci.cstics.add(cs);
        }
        result.add(ci);
        // 第二遍：递归收集嵌套 instance（保证嵌套实例也进入 ITEM_INST_ID）
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c.getNodeType() == Node.ELEMENT_NODE && "instance".equals(c.getNodeName())) {
                walkInstance((Element) c, result);
            }
        }
    }

    protected String pad6(Object posicion) {
        Integer n = toInt(posicion);
        return n == null ? "" : String.format("%06d", n);
    }

    protected String pad8(String id) {
        if (StringUtils.isBlank(id)) return "";
        try {
            return String.format("%08d", Long.parseLong(id.trim()));
        } catch (Exception e) {
            return id;
        }
    }

    /** 选型实例（含其特性值列表） */
    protected static class ConfigInstance {
        String id;
        String matnr;
        String parentId;
        String posnr;
        String qty;
        List<Cstic> cstics;
    }

    /** 特性值 */
    protected static class Cstic {
        String csticId;
        String valueId;
    }

    // ===================== 工具方法 =====================

    /** 读取字段 id：lookup 字段可能是 JSONObject{id} 或标量。 */
    protected Long readId(Object v) {
        if (v == null) return null;
        if (v instanceof JSONObject) {
            v = ((JSONObject) v).get("id");
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        String s = trimToNull(v.toString());
        if (s == null) return null;
        try {
            return Long.valueOf(s);
        } catch (Exception e) {
            return null;
        }
    }

    protected Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }
}