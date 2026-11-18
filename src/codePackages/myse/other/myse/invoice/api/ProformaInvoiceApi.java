package other.myse.invoice.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.exception.ApiEntityServiceException;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.service.XoqlService;
import com.rkhd.platform.sdk.model.QueryResult;
import other.myse.invoice.utils.HttpUtil;
import other.myse.invoice.utils.PermissionChangeGuard;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 形式发票相关接口（other.myse.invoice）
 * <p>
 * 1) 形式发票列表页   POST /invoice/list
 *    入参：userId（必填）、accountId（可选，按账户过滤；支持单个值或数组，如 123 / [123,456] / ["1","2"]）、
 *          invoiceNo/ deliveryNoteNo/ dateStart/ dateEnd/ orderNo/ purchaseNo/ orderItemNo（搜索条件）、
 *          sorts（排序，可选，数组 [{field:"dateCreate__c", order:"ASC"}]）、
 *          pageNum/ pageSize（分页）
 *    返回：形式发票号、日期、发货单号、含税总额、币种、付款方(账户名)、文件
 *
 * 2) 形式发票详情页   POST /invoice/detail
 *    入参：id（形式发票 id，必填）、sorts（排序，可选，作用于明细列表）
 *    返回：基础信息（发票号/付款条件/日期/付款方式/币种/收货方/地址/电话）+
 *         金额信息（未折扣总价/折扣总额/净额/不含税总额/税率/税额/含税总额）+
 *         明细列表（预计交货期/发货单号行号/订单号行号/订货号/数量/列表价/折扣/折后单价）
 *
 * 排序说明：sorts 为数组，元素 { field: 对象字段 API 名, order: "ASC"|"DESC" }；
 *          列表接口默认 createdAt DESC，明细列表默认 createdAt ASC；
 *          未传或全部无效时走默认排序。
 * </p>
 */
@RestApi(baseUrl = "/invoice")
public class ProformaInvoiceApi {

    private static final Logger logger = LoggerFactory.getLogger();



    // ===================== 1. 形式发票列表 =====================
    @RestMapping(value = "/list", method = RequestMethod.POST)
    public String list(@RestBeanParam(name = "json") String body) {
        try {
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = PermissionChangeGuard.checkPermissionChanged("ProformaInvoiceApi");
            if (guard != null) {
                return guard;
            }

            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }
            logger.info("body:"+body);
            JSONObject req = JSON.parseObject(body);
            Long userId = req.getLong("userId");
            if (userId == null) {
                return HttpUtil.fail("PARAM_ERROR", "userId 不能为空").toJSONString();
            }

            int pageNum = req.getIntValue("pageNum");
            int pageSize = req.getIntValue("pageSize");
            if (pageNum <= 0) {
                pageNum = 1;
            }
            if (pageSize <= 0 || pageSize > 200) {
                pageSize = 20;
            }

            // 搜索条件
            String invoiceNo = req.getString("invoiceNo");           // 形式发票号
            String deliveryNoteNo = req.getString("deliveryNoteNo"); // 发货单号

            // 账户过滤（可选）：支持单个值或数组，如 accountId=123 / accountId=[123,456] / accountId=["1","2"]
            List<String> accountIds = parseIdList(req.get("accountId"));

            String dateStart = req.getString("dateStart");           // 开始日期
            String dateEnd = req.getString("dateEnd");               // 结束日期
            String orderNo = req.getString("orderNo");               // 订单号（明细字段）
            String purchaseNo = req.getString("purchaseNo");         // 采购编号
            String orderItemNo = req.getString("orderItemNo");       // 订货号（明细字段）

            // 排序（可选）：[{field:"xxx__c", order:"ASC"}]
            String orderBy = buildOrderBy(req.getJSONArray("sorts"), "createdAt DESC");

            // 构建 WHERE 条件（条件为空时不加 WHERE，避免拼出 purchaserId__c = null 的无效条件）
            List<String> conditions = new ArrayList<>();
            if (!accountIds.isEmpty()) {
                conditions.add("purchaserId__c IN (" + joinIds(accountIds) + ")");
            }
            if (invoiceNo != null && !invoiceNo.trim().isEmpty()) {
                conditions.add("name LIKE '%" + escapeLike(invoiceNo.trim()) + "%'");
            }
            if (deliveryNoteNo != null && !deliveryNoteNo.trim().isEmpty()) {
                conditions.add("deliveryNoteId__c LIKE '%" + escapeLike(deliveryNoteNo.trim()) + "%'");
            }
            if (dateStart != null && !dateStart.isEmpty()) {
                conditions.add("dateCreate__c >= '" + dateStart + "'");
            }
            if (dateEnd != null && !dateEnd.isEmpty()) {
                conditions.add("dateCreate__c <= '" + dateEnd + "'");
            }

            // 明细字段筛选：EXISTS 子查询
            if (orderNo != null && !orderNo.trim().isEmpty()) {
                conditions.add("EXISTS (SELECT 1 FROM proformaInvoiceDetail__c d WHERE d.proformaInvoiceId__c = id AND d.orderNumber__c LIKE '%"
                        + escapeLike(orderNo.trim()) + "%')");
            }
            if (purchaseNo != null && !purchaseNo.trim().isEmpty()) {
                conditions.add("EXISTS (SELECT 1 FROM proformaInvoiceDetail__c d WHERE d.proformaInvoiceId__c = id AND d.delivery__c LIKE '%"
                        + escapeLike(purchaseNo.trim()) + "%')");
            }
            if (orderItemNo != null && !orderItemNo.trim().isEmpty()) {
                // 订货号：catalogNumber__c 是 product lookup，需通过产品名匹配
                conditions.add("EXISTS (SELECT 1 FROM proformaInvoiceDetail__c d WHERE d.proformaInvoiceId__c = id AND d.catalogNumber__c IN (SELECT p.id FROM product p WHERE p.productName LIKE '%"
                        + escapeLike(orderItemNo.trim()) + "%'))");
            }

            StringBuilder where = new StringBuilder();
            if (!conditions.isEmpty()) {
                where.append(" WHERE ");
                for (int i = 0; i < conditions.size(); i++) {
                    if (i > 0) {
                        where.append(" AND ");
                    }
                    where.append(conditions.get(i));
                }
            }

            // 查总数
            String countSql = "SELECT COUNT(id) AS cnt FROM proformaInvoice__c" + where.toString();
            QueryResult<JSONObject> countRt = XoqlService.instance().query(countSql);
            long total = 0;
            if (countRt != null && countRt.getRecords() != null && !countRt.getRecords().isEmpty()) {
                Object cnt = countRt.getRecords().get(0).get("cnt");
                if (cnt instanceof Number) {
                    total = ((Number) cnt).longValue();
                } else if (cnt != null) {
                    try { total = Long.parseLong(cnt.toString()); } catch (Exception ignored) {}
                }
            }

            // 查本页数据（按创建日期倒序）
            int offset = (pageNum - 1) * pageSize;
            String dataSql = "SELECT id, name, dateCreate__c, deliveryNoteId__c.name deliveryNote, tot_Pag__c, currency__c, purchaserId__c, document__c "
                    + " FROM proformaInvoice__c" + where
                    + " ORDER BY " + orderBy + " LIMIT " + offset + ", " + pageSize;

            logger.info("dataSql:"+dataSql);
            QueryResult<JSONObject> dataRt = XoqlService.instance().query(dataSql);
            logger.info("dataRt:"+dataRt.getRecords());

            JSONArray list = new JSONArray();
            if (dataRt != null && dataRt.getRecords() != null) {
                // 收集所有 purchaserID__c 用于批量查账户名
                Set<String> payerIds = new HashSet<>();
                for (Object o : dataRt.getRecords()) {
                    JSONObject r = (JSONObject) o;
                    Object pid = r.get("purchaserId__c");
                    if (pid != null) {
                        payerIds.add(pid.toString());
                    }
                }
                Map<String, String> accountNameMap = queryAccountNames(payerIds);

                for (Object o : dataRt.getRecords()) {
                    JSONObject r = (JSONObject) o;
                    JSONObject item = new JSONObject();
                    item.put("id", r.getString("id"));
                    item.put("invoiceNo", str(r.get("name")));
                    item.put("invoiceDate", formatDate(r.get("dateCreate__c")));
                    item.put("deliveryNoteNo", str(r.get("deliveryNote")));
                    item.put("totalAmount", r.get("tot_Pag__c"));
                    item.put("currency", str(r.get("currency__c")));
                    // 付款方：取关联账户名称
                    String payerId = str(r.get("purchaserId__c"));
                    item.put("payer", accountNameMap.getOrDefault(payerId, payerId));
                    // 文件
                    item.put("document", r.get("document__c"));
                    list.add(item);
                }
            }


            JSONObject result = new JSONObject();
            result.put("total", total);
            result.put("pageNum", pageNum);
            result.put("pageSize", pageSize);
            result.put("list", list);
            logger.info("result:"+result);
            return HttpUtil.success(result).toJSONString();

        } catch (Exception e) {
            logger.error("ProformaInvoice list 异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    // ===================== 2. 形式发票详情 =====================
    @RestMapping(value = "/detail", method = RequestMethod.POST)
    public String detail(@RestBeanParam(name = "json") String body) {
        try {
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = PermissionChangeGuard.checkPermissionChanged("ProformaInvoiceApi");
            if (guard != null) {
                return guard;
            }
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }
            JSONObject req = JSON.parseObject(body);
            String id = req.getString("id");
            if (id == null || id.trim().isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "id 不能为空").toJSONString();
            }
            Long userId = req.getLong("userId");  // 可选，用于数据权限

            // 查主记录（显式字段）
            String safeId = escapeSql(id.trim());
            StringBuilder where = new StringBuilder(" WHERE id = '").append(safeId).append("'");

            String sql = "SELECT id, name, payerCond__c, dateCreate__c, payCond__c, currency__c, "
                    + " recipient__c, adress1__c, telf1__c, "
                    + " tot_Bto__c, dto_Comer__c, base_Iva1__c, base_Iva2__c, pctj_Iva__c, imp_Iva__c, tot_Pag__c "
                    + " FROM proformaInvoice__c" + where.toString();
            QueryResult<JSONObject> rt = XoqlService.instance().query(sql);
            if (rt == null || rt.getRecords() == null || rt.getRecords().isEmpty()) {
                return HttpUtil.fail("NOT_FOUND", "形式发票不存在: " + id).toJSONString();
            }
            JSONObject row = rt.getRecords().get(0);

            // ---- 基础信息 ----
            JSONObject basicInfo = new JSONObject();
            basicInfo.put("id", row.getString("id"));
            basicInfo.put("invoiceNo", str(row.get("name")));
            if(row.getJSONArray("payerCond__c")!=null&& !row.getJSONArray("payerCond__c").isEmpty()){
                basicInfo.put("paymentTerms", row.getJSONArray("payerCond__c").get(0));       // 付款条件
            }

            basicInfo.put("invoiceDate", formatDate(row.get("dateCreate__c"))); // 形式发票日期
            basicInfo.put("paymentMethod", str(row.get("payCond__c")));         // 付款方式
            basicInfo.put("currency", str(row.get("currency__c")));             // 币种
            basicInfo.put("recipient", str(row.get("recipient__c")));           // 收货方（引用显示）
            basicInfo.put("address", str(row.get("adress1__c")));              // 地址
            basicInfo.put("phone", str(row.get("telf1__c")));                  // 电话

            // ---- 金额信息 ----
            JSONObject amountInfo = new JSONObject();
            amountInfo.put("totalBeforeDiscount", row.get("tot_Bto__c"));      // 未折扣总价
            amountInfo.put("discountAmount", row.get("dto_Comer__c"));         // 折扣总额
            amountInfo.put("netAmount", row.get("base_Iva1__c"));              // 净发票总额
            amountInfo.put("amountWithoutTax", row.get("base_Iva2__c"));       // 不含税发票总额
            amountInfo.put("taxRate", row.get("pctj_Iva__c"));                 // 增值税率（百分比 0~1）
            amountInfo.put("taxAmount", row.get("imp_Iva__c"));                // 增值税金额
            amountInfo.put("totalAmountWithTax", row.get("tot_Pag__c"));       // 含税发票总额

            // ---- 明细列表 ----
            JSONArray detailList = new JSONArray();
            // 排序（可选）：作用于明细列表，默认 createdAt ASC
            String detailOrderBy = buildOrderBy(req.getJSONArray("sorts"), "createdAt ASC");
            String detailSql = "SELECT promiseDate__c, delivery__c, order__c, catalogNumber__c, quantity__c, "
                    + " totalImport__c, dto__c, netImport__c "
                    + " FROM proformaInvoiceDetail__c WHERE proformaInvoiceId__c = '" + escapeSql(id.trim()) + "'"
                    + " ORDER BY " + detailOrderBy;
            QueryResult<JSONObject> detailRt = XoqlService.instance().query(detailSql);

            Set<String> productIds = new HashSet<>();
            if (detailRt != null && detailRt.getRecords() != null) {
                for (Object o : detailRt.getRecords()) {
                    JSONObject d = (JSONObject) o;
                    Object cid = d.get("catalogNumber__c");
                    if (cid != null) {
                        productIds.add(cid.toString());
                    }
                }
            }
            Map<String, String> productNameMap = queryProductNames(productIds);

            SimpleDateFormat sf = new SimpleDateFormat("yyyy-MM-dd");
            if (detailRt != null && detailRt.getRecords() != null) {
                for (Object o : detailRt.getRecords()) {
                    JSONObject d = (JSONObject) o;
                    JSONObject di = new JSONObject();
                    if(d.getLong("promiseDate__c")!=null) {
                        di.put("promiseDate",sf.format(d.getLong("promiseDate__c")));             // 预计交货期
                    }
                    di.put("deliveryNoteLine", str(d.get("delivery__c")));           // 发货单号/行号
                    di.put("orderLine", str(d.get("order__c")));                     // 订单号/行号
                    // 订货号 → 产品名
                    String prodId = str(d.get("catalogNumber__c"));
                    di.put("orderItemNo", productNameMap.getOrDefault(prodId, prodId));
                    di.put("quantity", toInt(d.get("quantity__c")));                  // 发货数量
                    di.put("listPrice", d.get("totalImport__c"));                     // 列表价
                    di.put("discount", d.get("dto__c"));                              // 折扣
                    di.put("unitPriceAfterDiscount", d.get("netImport__c"));         // 折扣后单价
                    detailList.add(di);
                }
            }

            JSONObject result = new JSONObject();
            result.put("basicInfo", basicInfo);
            result.put("amountInfo", amountInfo);
            result.put("details", detailList);
            return HttpUtil.success(result).toJSONString();

        } catch (Exception e) {
            logger.error("ProformaInvoice detail 异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    // ===================== 辅助方法 =====================

    /**
     * 构建 ORDER BY 子句
     * <p>
     * 入参格式：sorts = [ { "field": "dateCreate__c", "order": "ASC" }, ... ]
     * field 为对象字段 API 名，直接拼入 SQL（由调用方保证合法性）；
     * order 只认 ASC / DESC（忽略大小写），其它值按 ASC 处理；
     * 未传或全部无效时返回 defaultOrderBy。
     * </p>
     */
    private String buildOrderBy(JSONArray sorts, String defaultOrderBy) {
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
                String order = s.getString("order");
                String ord = "DESC".equalsIgnoreCase(order) ? "DESC" : "ASC";
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(field.trim()).append(" ").append(ord);
            }
        }
        if (sb.length() == 0) {
            return defaultOrderBy;
        }
        return sb.toString();
    }

    /**
     * 解析 id 类入参：支持单个值（数字/字符串）或数组，字符串还支持逗号/分号/空格分隔。
     * <pre>
     *   accountId = 123            → [123]
     *   accountId = [123, 456]     → [123, 456]
     *   accountId = ["1","2"]      → [1, 2]
     *   accountId = "1,2"          → [1, 2]
     * </pre>
     * 空值 / 空数组 / 全空白返回空列表（调用方据此决定是否加过滤条件）。
     */
    private List<String> parseIdList(Object v) {
        List<String> ids = new ArrayList<>();
        if (v == null) {
            return ids;
        }
        if (v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            for (int i = 0; i < arr.size(); i++) {
                addId(ids, arr.get(i));
            }
        } else if (v instanceof String) {
            String s = ((String) v).trim();
            if (!s.isEmpty()) {
                for (String part : s.split("[,;\\s]+")) {
                    addId(ids, part);
                }
            }
        } else {
            addId(ids, v);
        }
        return ids;
    }

    private void addId(List<String> ids, Object v) {
        if (v == null) {
            return;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return;
        }
        if (!ids.contains(s)) {
            ids.add(s);
        }
    }

    /** id 列表 → IN 子句内容：纯数字不加引号（与 lookup 直接比较一致），其余加引号并转义。 */
    private String joinIds(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            if (id.matches("\\d+")) {
                sb.append(id);
            } else {
                sb.append("'").append(escapeSql(id)).append("'");
            }
        }
        return sb.toString();
    }

    /**
     * 批量查询账户名称
     */
    private Map<String, String> queryAccountNames(Set<String> accountIds) {
        Map<String, String> map = new HashMap<>();
        if (accountIds.isEmpty()) {
            return map;
        }
        try {
            StringBuilder inSb = new StringBuilder();
            for (String aid : accountIds) {
                if (inSb.length() > 0) {
                    inSb.append(",");
                }
                inSb.append("'").append(escapeSql(aid)).append("'");
            }
            String sql = "SELECT id, name FROM account WHERE id IN (" + inSb.toString() + ")";
            QueryResult<JSONObject> rt = XoqlService.instance().query(sql);
            if (rt != null && rt.getRecords() != null) {
                for (Object o : rt.getRecords()) {
                    JSONObject a = (JSONObject) o;
                    map.put(a.getString("id"), str(a.get("name")));
                }
            }
        } catch (Exception e) {
            logger.warn("批量查询账户名称失败", e);
        }
        return map;
    }

    /**
     * 批量查询产品名称（productName 字段，非 name）
     */
    private Map<String, String> queryProductNames(Set<String> productIds) {
        Map<String, String> map = new HashMap<>();
        if (productIds.isEmpty()) {
            return map;
        }
        try {
            StringBuilder inSb = new StringBuilder();
            for (String pid : productIds) {
                if (inSb.length() > 0) {
                    inSb.append(",");
                }
                inSb.append("'").append(escapeSql(pid)).append("'");
            }
            String sql = "SELECT id, productName FROM product WHERE id IN (" + inSb.toString() + ")";
            QueryResult<JSONObject> rt = XoqlService.instance().query(sql);
            if (rt != null && rt.getRecords() != null) {
                for (Object o : rt.getRecords()) {
                    JSONObject p = (JSONObject) o;
                    map.put(p.getString("id"), str(p.get("productName")));
                }
            }
        } catch (Exception e) {
            logger.warn("批量查询产品名称失败", e);
        }
        return map;
    }

    /** 日期格式化（支持时间戳字符串/数字 和 yyyy-MM-dd 日期字符串） */
    private String formatDate(Object val) {
        if (val == null) {
            return "";
        }
        String s = val.toString();
        // 时间戳（毫秒级 13 位或秒级 10 位）
        try {
            long ts = Long.parseLong(s);

            SimpleDateFormat sf = new SimpleDateFormat("yyyy-MM-dd");
            return sf.format(new Date(ts));

        } catch (NumberFormatException ignored) {
        }

        return s;
    }

    private String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private Integer toInt(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** SQL 转义防注入 */
    private String escapeSql(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("'", "''");
    }

    /** LIKE 转义（转义 % _ \） */
    private String escapeLike(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public static void main(String[] args) throws ApiEntityServiceException {
        String dataSql="SELECT id, name, dateCreate__c, deliveryNoteId__c, tot_Pag__c, " +
                "currency__c, purchaserID__c, document__c FROM proformaInvoice__c " +
                "WHERE ownerId = 4433780814545882 AND purchaserID__c = 4400183696671784 ORDER BY createdAt DESC LIMIT 0, 10";

        QueryResult<JSONObject> dataRt = XoqlService.instance().query(dataSql);
        logger.info("dataRt:"+JSONObject.toJSONString(dataRt));
    }
}
