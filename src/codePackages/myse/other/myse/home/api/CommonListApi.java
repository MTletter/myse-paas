package other.myse.home.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.BatchOperateResult;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;
import com.rkhd.platform.sdk.data.model.CommonList__c;
import com.rkhd.platform.sdk.data.model.CommonListDetail__c;
import other.myse.home.utils.HttpUtil;
import other.myse.home.utils.PermissionChangeGuard;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 常用清单相关接口（other.myse.home）
 * <p>
 * 1) 常用清单列表页   POST /home/commonlist/list
 *    入参：userId（必填）、legalEntity（必填，交易实体选项 code，如 1/2/3/4，映射到 legalEntity__c）
 *    入参：sorts（可选，排序，[{field: 字段API名, order: "ASC"|"DESC"}]；默认 updatedAt DESC）
 *    返回：id、name、quantity__c（明细数量，平台只读汇总）、updatedAt（时间戳→yyyy-MM-dd HH:mm:ss）
 * 2) 常用清单明细     POST /home/commonlist/detail
 *    入参：id（常用清单数据 id，必填）
 *    返回：name（产品名称，来自 catalogNumber__c 关联 product）、posicion__c、catalogNumber__c（产品id）、quantity__c、description__c
 * 3) 常用清单创建/更新  POST /home/commonlist/create
 *    入参：userId、legalEntity、name、details[]（明细字段同上）
 *    若传了 id（常用清单数据 id）则为「更新」：更新表头(name/交易实体)，
 *    并在请求携带 details 时整体替换明细——「全量删除再创建」（先删全部旧明细，再批量插入本次明细）；
 *    不传 details 则保留原明细；不传 id 则为「创建」。
 * </p>
 */
@RestApi(baseUrl = "/home")
public class CommonListApi {

    private static final Logger logger = LoggerFactory.getLogger();

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    // commonList__c 默认业务类型（沙盒）
    private static final Long LIST_BUSI_TYPE = 4401514661053482L;
    // commonListDetail__c 默认业务类型（沙盒）
    private static final Long DETAIL_BUSI_TYPE = 4401520776397832L;

    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    // ===================== 1. 常用清单列表页 =====================
    @RestMapping(value = "/commonlist/list", method = RequestMethod.POST)
    public String list(@RestBeanParam(name = "json") String body) {
        logger.info("常用清单查询："+body);
        try {
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = PermissionChangeGuard.checkPermissionChanged("CommonListApi");
            if (guard != null) {
                return guard;
            }
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }
            JSONObject req = JSON.parseObject(body);
            Long userId = req.getLong("userId");
            String legalEntity=req.getString("legalEntity");
//            Long accountId = req.getLong("accountId");
            if (userId == null) {
                return HttpUtil.fail("PARAM_ERROR", "userId 不能为空").toJSONString();
            }
//            if (accountId == null) {
//                return HttpUtil.fail("PARAM_ERROR", "accountId 不能为空").toJSONString();
//            }

            int pageNum = req.getIntValue("pageNum");
            int pageSize = req.getIntValue("pageSize");
            if (pageNum <= 0) pageNum = 1;
            if (pageSize <= 0) pageSize = DEFAULT_PAGE_SIZE;
            if (pageSize > MAX_PAGE_SIZE) pageSize = MAX_PAGE_SIZE;
            int offset = (pageNum - 1) * pageSize;

            // 固定条件：所有人 = userId，交易实体(账户) = accountId（legalEntity__c 选项 code）
            String where = "ownerId = " + userId +" and legalEntity__c in ("+legalEntity+")";

            // ===== 排序（可选）：[{field, order}]，默认 updatedAt DESC（与 ApprovalListApi 同协议）=====
            String orderBy = buildOrderBy(req.getJSONArray("sorts"), "updatedAt DESC");

            // 总条数（轻量聚合）
            long total = 0;
            try {
                String countXoql = "SELECT COUNT(id) AS cnt FROM commonList__c WHERE " + where;
                QueryResult<JSONObject> cqr = XoqlService.instance().query(countXoql);
                if (cqr != null && cqr.getRecords() != null && !cqr.getRecords().isEmpty()) {
                    Object first = cqr.getRecords().get(0);
                    if (first instanceof JSONObject) {
                        String cnt = ((JSONObject) first).getString("cnt");
                        if (cnt != null && !cnt.isEmpty()) {
                            total = Long.parseLong(cnt);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("CommonList list 计数异常", e);
            }

            JSONArray list = new JSONArray();
            try {
                String xoql = "SELECT id, name, quantity__c,legalEntity__c, updatedAt,createdAt FROM commonList__c WHERE " + where
                        + " ORDER BY " + orderBy + " LIMIT " + offset + ", " + pageSize;
                QueryResult<JSONObject> qr = XoqlService.instance().query(xoql);
                if (qr != null && qr.getRecords() != null) {
                    for (Object o : qr.getRecords()) {
                        if (!(o instanceof JSONObject)) {
                            continue;
                        }
                        JSONObject r = (JSONObject) o;
                        JSONObject item = new JSONObject();
                        item.put("id", r.getString("id"));
                        item.put("name", str(r.get("name")));
                        item.put("quantity", toInt(r.get("quantity__c")));
                        item.put("legalEntity", parseLegalEntityText(r.get("legalEntity__c")));
                        item.put("legalEntityCodes", parseLegalEntityCodes(r.get("legalEntity__c")));
                        item.put("updatedAt", formatTimestamp(r.get("updatedAt")));
                        item.put("createdAt", formatTimestamp(r.get("createdAt")));
                        list.add(item);
                    }
                }
            } catch (Exception e) {
                return HttpUtil.fail("QUERY_ERROR", "查询常用清单失败：" + e.getMessage()).toJSONString();
            }

            JSONObject data = new JSONObject();
            data.put("total", total);
            data.put("pageNum", pageNum);
            data.put("pageSize", pageSize);
            data.put("list", list);
            return HttpUtil.success(data).toJSONString();
        } catch (Exception e) {
            logger.error("CommonList list 异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    // ===================== 2. 常用清单明细 =====================
    @RestMapping(value = "/commonlist/detail", method = RequestMethod.POST)
    public String detail(@RestBeanParam(name = "json") String body) {
        try {
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = PermissionChangeGuard.checkPermissionChanged("CommonListApi");
            if (guard != null) {
                return guard;
            }
            JSONObject data = new JSONObject();
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }
            JSONObject req = JSON.parseObject(body);
            Long listId = req.getLong("id");
            if (listId == null) {
                return HttpUtil.fail("PARAM_ERROR", "常用清单id(id)不能为空").toJSONString();
            }

            String commonListXoql = "SELECT id, name,legalEntity__c FROM commonList__c WHERE id="+listId;
            logger.info("commonListXoql:"+commonListXoql);
            QueryResult<JSONObject> commonListQr = XoqlService.instance().query(commonListXoql);
            if (commonListQr != null && commonListQr.getRecords() != null) {
                for (Object o : commonListQr.getRecords()) {
                    if (!(o instanceof JSONObject)) {
                        continue;
                    }
                    JSONObject r = (JSONObject) o;
                    data.put("name",r.getString("name") );
                    data.put("legalEntity", parseLegalEntityText(r.get("legalEntity__c")));
                    data.put("legalEntityCodes", parseLegalEntityCodes(r.get("legalEntity__c")));
                }
            }

            String xoql = "SELECT id, catalogNumber__c, posicion__c, quantity__c, description__c, createdAt,selectionId__c,selectionXml__c "
                    + "FROM commonListDetail__c WHERE commonListId__c = " + listId
                    + " ORDER BY posicion__c ASC";
            JSONArray list = new JSONArray();
            Map<String, String> productNameMap = new HashMap<>();

            QueryResult<JSONObject> qr = XoqlService.instance().query(xoql);
            if (qr != null && qr.getRecords() != null) {
                for (Object o : qr.getRecords()) {
                    if (!(o instanceof JSONObject)) {
                        continue;
                    }
                    JSONObject r = (JSONObject) o;
                    JSONObject item = new JSONObject();
                    // catalogNumber__c 是关联 product 的 lookup：可能返回 {id,name} 或纯 id
                    Object cat = r.get("catalogNumber__c");
                    String productId = null;
                    String productName = null;
                    if (cat instanceof JSONObject) {
                        JSONObject c = (JSONObject) cat;
                        productId = c.getString("id");
                        productName = c.getString("name");
                    } else if (cat != null) {
                        productId = cat.toString();
                    }
                    if (productId != null && productName == null) {
                        productNameMap.put(productId, null); // 待批量查 product 名称
                    }
                    item.put("name", productName == null ? "" : productName);
                    item.put("posicion", str(r.get("posicion__c")));
                    item.put("catalogNumber", productId);
                    item.put("quantity", toInt(r.get("quantity__c")));
                    item.put("description", str(r.get("description__c")));
                    item.put("selectionId__c",r.getString("selectionId__c"));
                    item.put("selectionXml__c",r.getString("selectionXml__c"));
                    list.add(item);
                }
            }

            // 批量补全产品名称
            if (!productNameMap.isEmpty()) {
                try {
                    StringBuilder inSb = new StringBuilder("(");
                    boolean first = true;
                    for (String pid : productNameMap.keySet()) {
                        if (!first) {
                            inSb.append(",");
                        }
                        inSb.append(pid);
                        first = false;
                    }
                    inSb.append(")");
                    String psql = "SELECT id, productName FROM product WHERE id IN " + inSb.toString();
                    QueryResult<JSONObject> pr = XoqlService.instance().query(psql);
                    if (pr != null && pr.getRecords() != null) {
                        for (Object o : pr.getRecords()) {
                            if (!(o instanceof JSONObject)) {
                                continue;
                            }
                            JSONObject p = (JSONObject) o;
                            productNameMap.put(p.getString("id"), str(p.get("productName")));
                        }
                    }
                    // 回填 name
                    for (Object o : list) {
                        JSONObject it = (JSONObject) o;
                        String pid = it.getString("catalogNumber");
                        if (pid != null && productNameMap.containsKey(pid)) {
                            String nm = productNameMap.get(pid);
                            it.put("name", nm == null ? "" : nm);
                        }
                    }
                } catch (Exception e) {
                    logger.error("CommonList detail 补全产品名称失败", e);
                }
            }



            data.put("list", list);
            return HttpUtil.success(data).toJSONString();
        } catch (Exception e) {
            logger.error("CommonList detail 异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    // ===================== 3. 常用清单创建 / 更新 =====================
    @RestMapping(value = "/commonlist/create", method = RequestMethod.POST)
    public String create(@RestBeanParam(name = "json") String body) {
        logger.info("常用清单创建/更新："+body);
        try {
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = PermissionChangeGuard.checkPermissionChanged("CommonListApi");
            if (guard != null) {
                return guard;
            }
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }
            JSONObject req = JSON.parseObject(body);
            Long userId = req.getLong("userId");
            String name = req.getString("name");
            Long id = req.getLong("id");                 // 传了 id 即更新
            JSONArray details = req.getJSONArray("details");
            logger.info("userId:"+userId+"=name:"+name+"=id:"+id);

            if (userId == null) {
                return HttpUtil.fail("PARAM_ERROR", "userId 不能为空").toJSONString();
            }
            if (name == null || name.trim().isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "name 不能为空").toJSONString();
            }

            if (id != null) {
                // ===== 更新分支 =====
                return updateCommonList(id, userId, req, name, details);
            }

            // ===== 创建分支 =====
//            Long accountId = req.getLong("accountId");
//            if (accountId == null) {
//                return HttpUtil.fail("PARAM_ERROR", "accountId 不能为空").toJSONString();
//            }
            Long dimDepart = getDimDepart(userId);
            if (dimDepart == null) {
                return HttpUtil.fail("DIM_DEPART_EMPTY", "无法获取用户(" + userId + ")的所属部门，创建失败").toJSONString();
            }

            CommonList__c cl = new CommonList__c();
            cl.setName(name);
            cl.setOwnerId(userId);
            cl.setEntityType(LIST_BUSI_TYPE);
            cl.setDimDepart(dimDepart);
            cl.setLegalEntity__c(req.getInteger("legalEntity"));
            cl.setUpdatedBy(userId);

            OperateResult or = XObjectService.instance().insert(cl, true);
            if (!Boolean.TRUE.equals(or.getSuccess())) {
                logger.error("CommonList 创建失败: " + or.getErrorMessage());
                return HttpUtil.fail("CREATE_FAIL", or.getErrorMessage()).toJSONString();
            }
            Long listId = or.getDataId();
            logger.info("CommonList 创建成功 listId=" + listId);

            int detailCount = saveDetails(listId, dimDepart, details);

            JSONObject data = new JSONObject();
            data.put("id", listId);
            data.put("name", name);
            data.put("detailCount", detailCount);
            data.put("action", "create");
            return HttpUtil.success(data).toJSONString();
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("CommonList create 异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    /** 更新分支：更新表头 + 整体替换明细（仅当请求携带 details 时） */
    private String updateCommonList(Long id, Long userId, JSONObject req, String name, JSONArray details) throws Exception {
        // 1) 取现有记录，校验存在性与归属
        CommonList__c existing;
        try {
            logger.info("id:"+id);
            CommonList__c probe = new CommonList__c();
            probe.setId(id);
            existing = XObjectService.instance().get(probe);
            logger.info("existing:" + existing);
        } catch (Exception e) {
            logger.error("CommonList 更新：记录不存在 id=" + id, e);
            return HttpUtil.fail("LIST_NOT_FOUND", "常用清单不存在 id=" + id).toJSONString();
        }
        if (existing.getOwnerId() != null && !existing.getOwnerId().equals(userId)) {
            return HttpUtil.fail("FORBIDDEN", "无权更新他人常用清单").toJSONString();
        }

        // 2) 更新表头（name 必改；legalEntity 可选，传了则更新交易实体，与 create 分支保持一致）
        Integer legalEntity = req.getInteger("legalEntity");
        existing.setName(name);
        existing.setUpdatedBy(userId);
        if (legalEntity != null) {
            existing.setLegalEntity__c(legalEntity);
        }
        OperateResult ur = XObjectService.instance().update(existing);
        if (!Boolean.TRUE.equals(ur.getSuccess())) {
            logger.error("CommonList 更新失败: " + ur.getErrorMessage());
            return HttpUtil.fail("UPDATE_FAIL", ur.getErrorMessage()).toJSONString();
        }

        // 3) 整体替换明细（全量删除再创建）：仅当请求携带 details 时执行。
        //    要点：先确定明细必填的系统维度 dimDepart，确保其可写入后再删除旧明细，
        //    避免「旧的已删除、新的因维度缺失插不进」导致明细数据丢失。
        int detailCount;
        if (details != null) {
            // 3.0 解析明细必填维度 dimDepart（优先取清单自身，兜底取用户部门）
            Long dimDepart = existing.getDimDepart();
            if (dimDepart == null) {
                dimDepart = getDimDepart(userId);
            }
            if (dimDepart == null) {
                return HttpUtil.fail("DIM_DEPART_EMPTY",
                        "无法获取用户(" + userId + ")的所属部门，更新明细失败").toJSONString();
            }

            // 3.1 全量删除旧明细
            List<Long> oldIds = queryDetailIds(id);
            if (!oldIds.isEmpty()) {
                List<CommonListDetail__c> toDelete = new ArrayList<>();
                for (Long did : oldIds) {
                    CommonListDetail__c d = new CommonListDetail__c();
                    d.setId(did);
                    toDelete.add(d);
                }
                try {
                    XObjectService.instance().delete(toDelete);
                } catch (Exception e) {
                    logger.error("CommonList 更新：删除旧明细失败 id=" + id, e);
                    return HttpUtil.fail("DETAIL_DELETE_FAIL", e.getMessage()).toJSONString();
                }
            }

            // 3.2 全量创建新明细
            detailCount = saveDetails(id, dimDepart, details);
        } else {
            // 未传 details：保留原明细，仅回写当前数量
            detailCount = queryDetailIds(id).size();
        }

        JSONObject data = new JSONObject();
        data.put("id", id);
        data.put("name", name);
        data.put("detailCount", detailCount);
        data.put("action", "update");
        return HttpUtil.success(data).toJSONString();
    }

    /** 批量写入明细，返回写入条数（失败抛 RuntimeException 由上层捕获） */
    private int saveDetails(Long listId, Long dimDepart, JSONArray details) throws Exception {
        if (details == null || details.isEmpty()) {
            return 0;
        }
        List<CommonListDetail__c> detailObjs = new ArrayList<>();
        for (int i = 0; i < details.size(); i++) {
            JSONObject d = details.getJSONObject(i);
            CommonListDetail__c dt = new CommonListDetail__c();
            dt.setCommonListId__c(listId);
            dt.setEntityType(DETAIL_BUSI_TYPE);
            dt.setDimDepart(dimDepart);
            Long pid = d.getLong("catalogNumber__c");
            if (pid != null) {
                dt.setCatalogNumber__c(pid);
            }
            dt.setPosicion__c(d.getString("posicion__c"));
            dt.setQuantity__c(d.getLong("quantity__c"));
            dt.setDescription__c(d.getString("description__c"));
            dt.setSelectionId__c(d.getString("selectionId__c"));
            dt.setSelectionXml__c(d.getString("selectionXml__c"));
            detailObjs.add(dt);
        }
        BatchOperateResult br = XObjectService.instance().insert(detailObjs, false, true);
        if (!Boolean.TRUE.equals(br.getSuccess())) {
            logger.error("CommonList 明细批量写入失败: " + br.getErrorMessage());
            throw new RuntimeException(br.getErrorMessage());
        }
        return detailObjs.size();
    }

    /** 查询某常用清单下所有明细 id */
    private List<Long> queryDetailIds(Long listId) {
        List<Long> ids = new ArrayList<>();
        try {
            String xoql = "SELECT id FROM commonListDetail__c WHERE commonListId__c = " + listId;
            QueryResult<JSONObject> qr = XoqlService.instance().query(xoql);
            if (qr != null && qr.getRecords() != null) {
                for (Object o : qr.getRecords()) {
                    if (o instanceof JSONObject) {
                        Long did = ((JSONObject) o).getLong("id");
                        if (did != null) {
                            ids.add(did);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("查询旧明细id失败 listId=" + listId, e);
        }
        return ids;
    }

    // ===================== 工具方法 =====================

    /**
     * 解析前端排序参数为 ORDER BY 子句（与 coordinator 包 UserPermissionBaseApi.buildOrderBy 同协议）。
     * sorts: [{field: 字段API名, order: "ASC"|"DESC"}]，可多级排序；
     * order 大小写不敏感，非 DESC 一律按 ASC；全部非法/为空时返回默认排序 defaultOrderBy。
     * 注：home 包与 coordinator 包跨包类不可见，此方法为内联副本。
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
                String ord = "DESC".equalsIgnoreCase(s.getString("order")) ? "DESC" : "ASC";
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(field.trim()).append(" ").append(ord);
            }
        }
        return sb.length() == 0 ? defaultOrderBy : sb.toString();
    }

    /**
     * 获取用户所属部门（dimDepart），用于创建记录时必填的系统维度。
     * 优先取用户自身部门；兜底取任一部门。
     */
    private Long getDimDepart(Long userId) {
        // 1. 优先取用户所属部门
        try {
            String sql = "SELECT id, dimDepart FROM user WHERE id = " + userId + " LIMIT 1";
            QueryResult<JSONObject> qr = XoqlService.instance().query(sql);
            if (qr != null && qr.getRecords() != null && !qr.getRecords().isEmpty()) {
                Object u = qr.getRecords().get(0);
                if (u instanceof JSONObject) {
                    Object d = ((JSONObject) u).get("dimDepart");
                    if (d instanceof JSONObject) {
                        return ((JSONObject) d).getLong("id");
                    }
                    if (d != null) {
                        return Long.parseLong(d.toString());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("获取用户部门失败, userId=" + userId, e);
        }
        // 2. 兜底：取任一部门
        try {
            QueryResult<JSONObject> dr = XoqlService.instance().query("SELECT id FROM department LIMIT 1");
            if (dr != null && dr.getRecords() != null && !dr.getRecords().isEmpty()) {
                Object d = dr.getRecords().get(0).get("id");
                if (d != null) {
                    return Long.parseLong(d.toString());
                }
            }
        } catch (Exception e) {
            logger.error("兜底获取部门失败", e);
        }
        return null;
    }

    /**
     * 时间戳格式化：数据库存的是时间戳（毫秒/秒，可能是 Number 或字符串），转换为 yyyy-MM-dd HH:mm:ss
     */
    private String formatTimestamp(Object ts) {
        if (ts == null) {
            return "";
        }
        long millis;
        if (ts instanceof Number) {
            millis = ((Number) ts).longValue();
        } else if (ts instanceof String) {
            try {
                millis = Long.parseLong(((String) ts).trim());
            } catch (Exception e) {
                return ts.toString();
            }
        } else {
            return ts.toString();
        }
        if (millis <= 1000000000000L) {
            millis *= 1000L; // 秒转毫秒
        }
        synchronized (SDF) {
            return SDF.format(new Date(millis));
        }
    }

    /** 数值转 int（兼容 Number / 数值字符串，如汇总字段返回的 "2.0"） */
    private int toInt(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return (int) Double.parseDouble(v.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private String str(Object v) {
        return v == null ? "" : v.toString();
    }

    // 交易实体(legalEntity__c) 选项集：value(整数码) -> {apicode, 显示名}，按 value / apicode / 显示名 三维度建索引。
    // 来源：/rest/data/v2.0/xobjects/commonList__c/description (selectitem)
    // 注意：运行时 XOQL 单选 picklist 返回的并非整数 value，而是【显示名字符串】（如 "SEC施耐德电气(中国)有限公司"），也非 apicode。
    // 因此统一解析出整数 value，供 legalEntityCodes 回传——该 value 与列表筛选 `legalEntity__c in (value)` 同源，
    // 前端可直接复用其做筛选（即用户传入的 1/2/3/4）；legalEntity 回传显示名。
    // 选项集由平台配置，字段增删时需同步更新 register(...)。
    private static final Map<Integer, String[]> LE_VALUE_TO_META = new HashMap<>();
    private static final Map<String, Integer> LE_APICODE_TO_VALUE = new HashMap<>();
    private static final Map<String, Integer> LE_LABEL_TO_VALUE = new HashMap<>();
    static {
        register(1, "CN01-SEC", "SEC施耐德电气(中国)有限公司");
        register(2, "CN22-SEIT", "SEIT施耐德电气信息技术(中国)有限公司");
        register(3, "CN26-SWEEC", "施耐德万高(天津)电气设备有限公司");
        register(4, "CN30-ASCO", "上海高双电气科技有限公司");
    }
    private static void register(int value, String apicode, String label) {
        LE_VALUE_TO_META.put(value, new String[]{apicode, label});
        LE_APICODE_TO_VALUE.put(apicode, value);
        LE_LABEL_TO_VALUE.put(label, value);
    }

    /**
     * 统一解析单元素为整数 value（与列表筛选 in(...) 同源）。
     * 入参可能是：整数 value、apicode 字符串、显示名字符串、{code/value/name/label} 对象。
     * 命中任一索引即返回对应整数 value；均不命中返回 null（调用方退化为原串）。
     */
    private Integer resolveLegalEntityValue(Object e) {
        if (e == null) {
            return null;
        }
        if (e instanceof JSONObject) {
            JSONObject jo = (JSONObject) e;
            Integer v = toIntOrNull(jo.get("value"));
            if (v != null && LE_VALUE_TO_META.containsKey(v)) {
                return v;
            }
            Object code = jo.get("code");
            if (code != null) {
                Integer vc = toIntOrNull(code);
                if (vc != null && LE_VALUE_TO_META.containsKey(vc)) {
                    return vc;
                }
                Integer av = LE_APICODE_TO_VALUE.get(code.toString());
                if (av != null) {
                    return av;
                }
            }
            String nm = str(jo.get("name"));
            if (nm.isEmpty()) {
                nm = str(jo.get("label"));
            }
            if (!nm.isEmpty()) {
                Integer lv = LE_LABEL_TO_VALUE.get(nm);
                if (lv != null) {
                    return lv;
                }
            }
            return null;
        }
        if (e instanceof Number) {
            int iv = ((Number) e).intValue();
            return LE_VALUE_TO_META.containsKey(iv) ? iv : null;
        }
        String s = e.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            int iv = Integer.parseInt(s);
            if (LE_VALUE_TO_META.containsKey(iv)) {
                return iv;
            }
        } catch (Exception ex) {
            // 非整数，继续按 apicode / 显示名匹配
        }
        Integer av = LE_APICODE_TO_VALUE.get(s);
        if (av != null) {
            return av;
        }
        Integer lv = LE_LABEL_TO_VALUE.get(s);
        if (lv != null) {
            return lv;
        }
        // 兜底：实际 picklist 选项显示名可能带前缀/后缀（如 "SASS(ASCO)上海高双电气科技有限公司"），子串包含匹配。
        for (Map.Entry<String, Integer> en : LE_LABEL_TO_VALUE.entrySet()) {
            String lbl = en.getKey();
            if (!lbl.isEmpty() && (s.contains(lbl) || lbl.contains(s))) {
                return en.getValue();
            }
        }
        return null;
    }

    /**
     * 解析交易实体(legalEntity__c)为易读显示名返回前端。
     * 单选返回显示名；多值数组逐元素拼接（顿号分隔）。兼容对象 / 纯值 / 显示名 / apicode 形态。
     */
    private String parseLegalEntityText(Object val) {
        if (val == null) {
            return "";
        }
        if (val instanceof JSONArray) {
            JSONArray arr = (JSONArray) val;
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                Integer v = resolveLegalEntityValue(arr.get(i));
                if (v != null) {
                    String lbl = LE_VALUE_TO_META.get(v)[1];
                    if (!lbl.isEmpty()) {
                        parts.add(lbl);
                    }
                }
            }
            return String.join("、", parts);
        }
        Integer v = resolveLegalEntityValue(val);
        return v != null ? LE_VALUE_TO_META.get(v)[1] : str(val);
    }

    /**
     * 提取交易实体整数 value 数组返回前端（如 1），与列表筛选 `legalEntity__c in (value)` 同源，
     * 前端可直接复用做筛选（即用户传入的 1/2/3/4）。单选项返回长度为 1 的数组。
     * 兼容多值数组 / {code,value} 对象 / 显示名 / apicode 形态。
     */
    private Integer parseLegalEntityCodes(Object val) {
        Integer codes = null;
       
        if (val instanceof JSONArray) {
            JSONArray arr = (JSONArray) val;
            for (int i = 0; i < arr.size(); i++) {
                Integer v = resolveLegalEntityValue(arr.get(i));
                if (v != null) {
                    codes=v;
                }
            }
            return codes;
        }
        Integer v = resolveLegalEntityValue(val);
        codes=v;
        return codes;
    }

    /** 安全转 Integer（兼容 Number / 数值字符串），失败返回 null */
    private Integer toIntOrNull(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    public static void main(String[] args) {
       new CommonListApi().create("{\"accountId\":\"4404206677674076\",\"name\":\"SWEEC0001\",\"details\":[{\"catalogNumber__c\":\"4431245141592070\",\"quantity__c\":1,\"description__c\":\"3P3T MIC 2.3 630A NSX630H 塑壳断路器\",\"posicion__c\":10}],\"id\":\"4441192226572335\",\"userId\":\"4433780814545882\",\"legalEntity\":\"3\"}");
    }
}
