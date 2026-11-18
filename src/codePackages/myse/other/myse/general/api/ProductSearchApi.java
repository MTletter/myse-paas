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
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.XoqlService;
import other.myse.general.utils.HttpUtil;
import other.myse.general.utils.PermissionChangeGuard;
import other.myse.general.utils.XoqlHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 产品高级搜索接口（精简版：只返回订货号 + 描述）
 * <p><b>销售易 API 路径</b>：POST /rest/data/v2.0/scripts/api/general/product/search</p>
 * <p><b>入参</b>：</p>
 * <pre>{
 *   keyword: "A9F",           // 搜索关键字（必填，模糊匹配）
 *   searchType: 1,            // 搜索范围：1=订货号或描述(默认) / 2=仅订货号 / 3=仅描述
 *   prefixOnly: false,        // 是否仅搜索打头物料（默认 false，即包含即可）
 *   salesOrgId: 4430944998491115, // 销售组织过滤（选填）：直接按销售组织 id 过滤产品归属
 *   pageNum: 1,               // 页码（默认 1）
 *   pageSize: 20              // 每页条数（默认 20，最大 200）
 * }</pre>
 * <p><b>出参</b>：</p>
 * <pre>{
 *   success: true,
 *   data: {
 *     total: 150,
 *     pageNum: 1,
 *     pageSize: 20,
 *     list: [
 *       { productName: "A9F1031", description: "Acti9 iC60L..." },
 *       ...
 *     ]
 *   }
 * }</pre>
 * <p><b>查询策略</b>：带 salesOrgId 时先按关键字模糊查询 product 锁定候选范围
 * （扫描上限 {@link #MAX_SCAN} 保护），再分批校验候选产品是否归属该销售组织
 * （productOrg__c 按 product__c IN 小批量反查，只取 product__c 判归属）。</p>
 * <p><b>查询通道</b>：product 走 XoqlService；productOrg__c 等只读受限对象
 * 统一走 {@link XoqlHelper}（RkhdHttpClient 内部 XOQL）。</p>
 */
@RestApi(baseUrl = "/general")
public class ProductSearchApi {

    private static final Logger logger = LoggerFactory.getLogger();

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 关键字扫描分批大小（每批 LIMIT 条数） */
    private static final int SCAN_BATCH = 500;
    /** 关键字扫描总量上限：防止超宽关键字（如 "A9"）命中过多导致扫描失控 */
    private static final int MAX_SCAN = 5000;
    /** productOrg__c 反查分批大小（product id 批大小） */
    private static final int ORG_QUERY_CHUNK = 500;

    /** 搜索范围：订货号或描述 */
    private static final int SEARCH_TYPE_ALL = 1;
    /** 搜索范围：仅订货号 */
    private static final int SEARCH_TYPE_ORDER_NUMBER = 2;
    /** 搜索范围：仅描述 */
    private static final int SEARCH_TYPE_DESCRIPTION = 3;

    @RestMapping(value = "/product/search", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        try {
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = PermissionChangeGuard.checkPermissionChanged("ProductSearchApi");
            if (guard != null) {
                return guard;
            }
            logger.info("body:" + body);
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            JSONObject req = JSON.parseObject(body);
            String keyword = req.getString("keyword");
            if (keyword == null || keyword.trim().isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "keyword 不能为空").toJSONString();
            }
            String kw = keyword.trim();

            int searchType = req.getIntValue("searchType");
            if (searchType != SEARCH_TYPE_ORDER_NUMBER && searchType != SEARCH_TYPE_DESCRIPTION) {
                searchType = SEARCH_TYPE_ALL; // 默认两者都搜
            }
            int pageNum = req.getIntValue("pageNum");
            int pageSize = req.getIntValue("pageSize");
            if (pageNum <= 0) pageNum = 1;
            if (pageSize <= 0) pageSize = DEFAULT_PAGE_SIZE;
            if (pageSize > MAX_PAGE_SIZE) pageSize = MAX_PAGE_SIZE;

            // 是否仅搜索打头物料：true=前缀匹配(LIKE 'kw%')，false/默认=包含匹配(LIKE '%kw%')
            boolean prefixOnly = req.getBooleanValue("prefixOnly");

            // ===== 1) 销售组织过滤：直接用入参 salesOrgId =====
            List<Long> entitySaleorgIds = null;
            Long salesOrgId = req.getLong("salesOrgId");
            if (salesOrgId != null) {
                entitySaleorgIds = new ArrayList<>();
                entitySaleorgIds.add(salesOrgId);
            }

            logger.info("ProductSearch XOQL keyword=" + kw + " type=" + searchType
                    + " prefixOnly=" + prefixOnly + " salesOrgId=" + salesOrgId
                    + " page=" + pageNum + "/" + pageSize);

            String kwLike = escapeLike(kw);
            String where = buildWhere(searchType, kwLike, prefixOnly);
            logger.info("ProductSearch WHERE: " + where);

            // ===== 2) 检索 + 分页（两条分支）=====
            List<JSONObject> pageRows;
            long total;

            if (entitySaleorgIds != null) {
                // 带交易实体过滤：关键字优先，先锁定范围再校验归属，内存分页
                List<JSONObject> matchedRows = scanKeywordMatched(where);
                List<JSONObject> allowedRows = filterByEntity(matchedRows, entitySaleorgIds);
                total = allowedRows.size();
                int from = (pageNum - 1) * pageSize;
                pageRows = new ArrayList<>();
                if (from < allowedRows.size()) {
                    int to = Math.min(from + pageSize, allowedRows.size());
                    pageRows = new ArrayList<>(allowedRows.subList(from, to));
                }
                if (matchedRows.size() >= MAX_SCAN) {
                    logger.warn("ProductSearch 关键字命中数达到扫描上限 " + MAX_SCAN
                            + "，total 为上限内统计值 keyword=" + kw);
                }
            } else {
                // 无交易实体过滤：XOQL LIMIT 直接分页
                total = countMatched(where);
                pageRows = pageInDb(where, pageNum, pageSize);
            }

            logger.info("ProductSearch 过滤后total=" + total + " 本页=" + pageRows.size());

            // ===== 3) 组装（只返回订货号 + 描述）=====
            JSONArray list = new JSONArray();
            for (JSONObject p : pageRows) {
                JSONObject item = new JSONObject();
                item.put("productName", getStr(p, "productName"));       // 订货号
                item.put("description", getStr(p, "proDescription__c")); // 描述
                list.add(item);
            }

            // ===== 4) 返回 =====
            JSONObject data = new JSONObject();
            data.put("total", total);
            data.put("pageNum", pageNum);
            data.put("pageSize", pageSize);
            data.put("list", list);

            logger.info("ProductSearch 完成 total=" + total + " returned=" + list.size() + " keyword=" + kw);
            return HttpUtil.success(data).toJSONString();

        } catch (Exception e) {
            e.printStackTrace();
            logger.error("ProductSearch 执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    // ===================== 查询条件构造 =====================

    /**
     * 构造 WHERE 条件（不含 "WHERE" 关键字）。
     * searchType=1 时用 OR 单条查询（替代之前两次查询再合并去重的做法）。
     * prefixOnly=true 时用前缀匹配（LIKE 'kw%'），否则用包含匹配（LIKE '%kw%'）。
     */
    private String buildWhere(int searchType, String kwLike, boolean prefixOnly) {
        String like = likeExpr(kwLike, prefixOnly);
        if (searchType == SEARCH_TYPE_ORDER_NUMBER) {
            return "enableStatus = 1 AND productName" + like;
        } else if (searchType == SEARCH_TYPE_DESCRIPTION) {
            return "enableStatus = 1 AND proDescription__c " + like;
        }
        // 默认：订货号或描述
        return "enableStatus = 1 AND (productName" + like + " OR proDescription__c " + like + ")";
    }

    /**
     * 根据 prefixOnly 拼 LIKE 表达式。
     * prefixOnly=true  → LIKE 'kw%'（仅打头）
     * prefixOnly=false → LIKE '%kw%'（包含即可）
     * kwLike 已经过 escapeLike 转义，外部再用 % 包裹不会破坏转义。
     */
    private String likeExpr(String kwLike, boolean prefixOnly) {
        if (prefixOnly) {
            return " LIKE '" + kwLike + "%'";
        }
        return " LIKE '%" + kwLike + "%'";
    }

    /**
     * 转义 LIKE 通配符，防止用户传入的 % / _ 被当成通配符导致意外匹配
     */
    private String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ===================== product 检索（XoqlService 通道）=====================

    /**
     * 统计匹配总数（轻量聚合，不拉全量数据）
     */
    private long countMatched(String where) {
        try {
            String countSql = "SELECT COUNT(id) AS cnt FROM product WHERE " + where;
            logger.info("ProductSearch count SQL: " + countSql);
            QueryResult<JSONObject> cqr = XoqlService.instance().query(countSql);
            if (cqr != null && cqr.getRecords() != null && !cqr.getRecords().isEmpty()) {
                JSONObject row = cqr.getRecords().get(0);
                // 优先取别名 cnt，其次常见键，最后取第一个数值
                Object v = row.get("cnt");
                if (v == null) v = row.get("count");
                if (v == null) v = row.get("total");
                if (v != null) return toLong(v);
                for (Object val : row.values()) {
                    if (val instanceof Number) return ((Number) val).longValue();
                }
            }
        } catch (Exception e) {
            logger.error("ProductSearch count 失败", e);
        }
        return 0;
    }

    /**
     * 无实体过滤分支：XOQL LIMIT 数据库层分页。
     */
    private List<JSONObject> pageInDb(String where, int pageNum, int pageSize) throws Exception {
        List<JSONObject> pageRows = new ArrayList<>();
        int from = (pageNum - 1) * pageSize;
        String pageSql = "SELECT id, productName, proDescription__c FROM product "
                + "WHERE " + where + " LIMIT " + from + ", " + pageSize;
        logger.info("pageSql:" + pageSql);
        QueryResult<JSONObject> qr = XoqlService.instance().query(pageSql);
        if (qr != null && qr.getRecords() != null) {
            for (Object o : qr.getRecords()) {
                pageRows.add((JSONObject) o);
            }
        }
        return pageRows;
    }

    /**
     * 按关键字 WHERE 条件分批扫描 product，返回命中记录（含 id/productName/描述）。
     * 使用 ORDER BY id + LIMIT 分批推进，总量不超过 MAX_SCAN。
     */
    private List<JSONObject> scanKeywordMatched(String where) {
        List<JSONObject> rows = new ArrayList<>();
        int offset = 0;
        while (rows.size() < MAX_SCAN) {
            int size = Math.min(SCAN_BATCH, MAX_SCAN - rows.size());
            String sql = "SELECT id, productName, proDescription__c FROM product "
                    + "WHERE " + where + " ORDER BY id LIMIT " + offset + ", " + size;
            logger.info("ProductSearch 关键字扫描 SQL: " + sql);
            List<JSONObject> batch = new ArrayList<>();
            try {
                QueryResult<JSONObject> qr = XoqlService.instance().query(sql);
                if (qr != null && qr.getRecords() != null) {
                    for (Object o : qr.getRecords()) {
                        batch.add((JSONObject) o);
                    }
                }
            } catch (Exception e) {
                logger.error("ProductSearch 关键字扫描失败 offset=" + offset, e);
                break;
            }
            if (batch.isEmpty()) {
                break;
            }
            rows.addAll(batch);
            if (batch.size() < size) {
                break; // 已到末尾
            }
            offset += size;
        }
        return rows;
    }

    // ===================== 销售组织(salesOrgId) 过滤 =====================

    /**
     * 校验候选产品中哪些归属给定销售组织集合，按扫描原顺序返回通过的产品行。
     * 按 product id 分批（ORG_QUERY_CHUNK）反查 productOrg__c（只取 product__c 判归属），
     * 避免全量拉取几十万条记录。
     */
    private List<JSONObject> filterByEntity(List<JSONObject> rows, List<Long> saleorgIds) {
        List<JSONObject> allowedRows = new ArrayList<>();
        if (rows == null || rows.isEmpty() || saleorgIds == null || saleorgIds.isEmpty()) {
            return allowedRows;
        }
        String soIn = XoqlHelper.joinLongs(saleorgIds);
        Set<Long> allowedIds = new HashSet<>();

        for (int i = 0; i < rows.size(); i += ORG_QUERY_CHUNK) {
            List<JSONObject> chunk = rows.subList(i, Math.min(i + ORG_QUERY_CHUNK, rows.size()));
            List<Long> chunkIds = new ArrayList<>();
            for (JSONObject r : chunk) {
                long pid = toLong(r.get("id"));
                if (pid != 0) {
                    chunkIds.add(pid);
                }
            }
            if (chunkIds.isEmpty()) {
                continue;
            }
            String xoql = "SELECT product__c FROM productOrg__c "
                    + "WHERE product__c IN (" + XoqlHelper.joinLongs(chunkIds)
                    + ") AND salesOrg__c IN (" + soIn + ")";
            logger.info("ProductSearch productOrg 归属校验 SQL: " + xoql);
            allowedIds.addAll(XoqlHelper.ids(xoql, "product__c"));
        }
        // 按扫描原顺序保留归属当前交易实体的产品行
        for (JSONObject r : rows) {
            long pid = toLong(r.get("id"));
            if (pid != 0 && allowedIds.contains(pid)) {
                allowedRows.add(r);
            }
        }
        logger.info("ProductSearch 归属校验: 关键字扫描=" + rows.size()
                + ", 归属通过=" + allowedRows.size() + ", 销售组织=" + saleorgIds);
        return allowedRows;
    }

    // ===================== 工具方法 =====================

    private String getStr(JSONObject r, String key) {
        Object v = r.get(key);
        return v == null ? "" : v.toString();
    }

    private long toLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return 0; }
    }
}
