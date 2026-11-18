package other.xsy.myse.apis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.data.model.ProformaInvoice__c;
import com.rkhd.platform.sdk.data.model.ProformaInvoiceDetail__c;
import com.rkhd.platform.sdk.exception.CustomConfigException;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.BatchOperateResult;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.model.XObject;
import com.rkhd.platform.sdk.service.CustomConfigService;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;
import other.xsy.myse.utils.HttpUtil;
import static other.xsy.myse.utils.JsonValues.getSingleObject;
import static other.xsy.myse.utils.JsonValues.getItems;

import static other.xsy.myse.utils.JsonValues.trimToNull;
import static other.xsy.myse.utils.JsonValues.asLong;
import static other.xsy.myse.utils.JsonValues.parseDate;
import static other.xsy.myse.utils.JsonValues.parseDouble;
import static other.xsy.myse.utils.JsonValues.escape;
import static other.xsy.myse.utils.JsonValues.asString;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MYSE-16 推送形式发票（Invoice）（SAP → mySE，被动接收）
 * <p>
 * 销售易 API 路径：/rest/data/v2.0/scripts/api/myse/invoice/push
 * </p>
 * <p><b>实际 SAP 报文结构（IDoc 风格）</b>：
 * 根节点控制字段：E_ACTCODE(A/C/U/D)、E_BUKRS、E_DOCNUMBER(发票号)、E_KUNRG(客户号)、E_TIMESTAMP、E_WAERS、ZRES_FAC
 * T_CABS_FAC.item      —— 发票头（金额/税额/币种/付款条件/地址等）
 * T_POS_FACMYSEDV.item —— 发票明细行（单条为对象，多条为数组）
 * </p>
 * <p><b>操作代码</b>：A/C/U=新建或更新（按 name(形式发票号=E_DOCNUMBER) 做 upsert，幂等）；D=删除。</p>
 * <p><b>落库对象（两对象，proformaInvoiceDetail__c 通过 proformaInvoiceId__c 关联父）</b>：
 *  - proformaInvoice__c      （形式发票，busiType 4402728206124060）
 *  - proformaInvoiceDetail__c （形式发票明细，busiType 4402827804886034）
 * </p>
 */
@RestApi(baseUrl = "/myse")
public class Myse16InvoicePushApi {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 形式发票业务类型（entityType），取自租户元数据 busiTypeId。 */
    private static final Long PROFORMA_INVOICE_BUSI_TYPE;
    /** 形式发票明细业务类型（entityType）。 */
    private static final Long PROFORMA_INVOICE_DETAIL_BUSI_TYPE;

    static {
        try {
            // 获取对象实例
            CustomConfigService instance = CustomConfigService.instance();
            // 获取配置集,返回此配置集中的所有配置项
            Map<String, String> configSet = instance.getConfigSet("entityType");
            PROFORMA_INVOICE_BUSI_TYPE=Long.parseLong(configSet.get("PROFORMA_INVOICE_BUSI_TYPE"));
            PROFORMA_INVOICE_DETAIL_BUSI_TYPE=Long.parseLong(configSet.get("PROFORMA_INVOICE_DETAIL_BUSI_TYPE"));
        } catch (CustomConfigException e) {
            throw new RuntimeException(e);
        }
    }

    /** SAP 付款条件文本(payerCond__c) → CRM 选项码。SAP 发的是中文文本，需匹配平台 optionLabel。
     *  当前仅收录已确认的样本值；其余未知值仅记日志，待补充完整对照表。 */
    private static final Map<String, Integer> PAYERCOND_TO_CODE = new HashMap<String, Integer>();
    static {
        PAYERCOND_TO_CODE.put("下月1日或15日到期", 17);
        // 扩展：如 SAP 还会发其它付款条件，按 metadata-entityDescribe payerCond__c 的选项 label→code 补此表
    }

    @RestMapping(value = "/invoice/push", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
            try {
                JSONObject root = JSON.parseObject(body);

                // ====== 单据容器：兼容两种报文格式 ======
                // 老格式：root 自身就是一张单据
                // 新批量格式：root.T_OUTPUT.item[] 每个元素是一张完整单据，自带控制字段
                List<JSONObject> containers = new ArrayList<>();
                JSONArray outputItems = getItems(root, "T_OUTPUT");
                if (outputItems != null && !outputItems.isEmpty()) {
                    for (int i = 0; i < outputItems.size(); i++) {
                        JSONObject one = outputItems.getJSONObject(i);
                        if (one == null) {
                            logger.warn("MYSE-16 T_OUTPUT 第 " + i + " 条为空，跳过");
                            continue;
                        }
                        containers.add(one);
                    }
                    logger.info("MYSE-16 报文格式: T_OUTPUT 批量包装, 单据数=" + containers.size());
                } else {
                    containers.add(root);
                }

                // ====== 批量预取：整批报文的关联 id 一次查完，循环内不再查库 =====
                RefCache cache = prefetch(containers);

                // ====== 阶段 B：解析（只构造对象，不落库）======
                List<PreparedInvoice> prepared = new ArrayList<>();
                for (JSONObject container : containers) {
                    PreparedInvoice one = prepareInvoice(container, cache);
                    if (one != null) {
                        prepared.add(one);
                    }
                }

                // ====== 阶段 C：批量落库（主记录 / 明细 / 删除，全部批量）======
                batchUpsertMain(prepared, cache);
                batchSyncDetails(prepared, cache);
                batchDeleteInvoices(prepared, cache);

                // ====== 阶段 D：组装结果 =====
                JSONArray results = new JSONArray();
                JSONObject firstResult = null;
                for (PreparedInvoice p : prepared) {
                    results.add(p.result);
                    if (firstResult == null) {
                        firstResult = p.result;
                    }
                }
                if (results.isEmpty()) {
                    return HttpUtil.fail("PARAM_ERROR", "发票号(E_DOCNUMBER)不能为空").toJSONString();
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
                logger.info("MYSE-16 批量处理完成: 单据数=" + results.size());
                return HttpUtil.success(data).toJSONString();

            } catch (Exception e) {
                logger.error("MYSE-16 推送形式发票处理异常", e);
                return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
            }
        }

    /**
     * 单据准备结果（阶段 B 解析产出 → 阶段 C 批量落库消费）。
     */
    private static class PreparedInvoice {
        /** 处理结果（对外出参） */
        JSONObject result = new JSONObject();
        /** 操作码：A/C/U = upsert，D = 删除 */
        String actionCode = "A";
        /** 发票号 */
        String invNumber;
        /** 是否删除类单据 */
        boolean isDelete = false;
        /** 是否新增（false = 更新） */
        boolean isNew = true;
        /** 解析失败（不参与落库） */
        boolean failed = false;
        /** 主记录对象（未落库） */
        ProformaInvoice__c invoice;
        /** 明细对象（未落库，未设 proformaInvoiceId__c） */
        List<ProformaInvoiceDetail__c> details = new ArrayList<>();
        /** 发票 id（新增成功后回填） */
        Long invoiceId;
        /** 删除的明细条数 */
        int deletedItemCount;
        /** 插入的明细条数 */
        int insertedCount;
    }

    /**
     * 阶段 C-1：批量落库主记录。
     *
     * <p>新增走批量 insert（{@code BatchOperateResult.getOperateResults()} 按下标取回 dataId），
     * 更新走批量 update —— 不再一张一张 insert/update。</p>
     */
    private void batchUpsertMain(List<PreparedInvoice> prepared, RefCache cache) {
        List<PreparedInvoice> insTargets = new ArrayList<>();
        List<PreparedInvoice> updTargets = new ArrayList<>();
        for (PreparedInvoice p : prepared) {
            if (p.failed || p.isDelete || p.invoice == null) {
                continue;
            }
            if (p.isNew) {
                insTargets.add(p);
            } else {
                updTargets.add(p);
            }
        }

        // ===== 批量更新 =====
        for (int i = 0; i < updTargets.size(); i += BATCH_SIZE) {
            List<PreparedInvoice> part = updTargets.subList(i, Math.min(i + BATCH_SIZE, updTargets.size()));
            List<ProformaInvoice__c> objs = new ArrayList<>();
            for (PreparedInvoice p : part) {
                objs.add(p.invoice);
            }
            try {
                BatchOperateResult br = XObjectService.instance().update(objs, true, true);
                List<OperateResult> ops = br == null ? null : br.getOperateResults();
                for (int k = 0; k < part.size(); k++) {
                    OperateResult op = (ops != null && k < ops.size()) ? ops.get(k) : null;
                    if (op != null && Boolean.TRUE.equals(op.getSuccess())) {
                        part.get(k).invoiceId = part.get(k).invoice.getId();
                        part.get(k).result.put("invoiceId", String.valueOf(part.get(k).invoiceId));
                    } else {
                        String err = op == null ? "批量更新无返回" : op.getErrorMessage();
                        part.get(k).result.put("action", "error");
                        part.get(k).result.put("error", "更新形式发票失败: " + err);
                        logger.error("MYSE-16 批量更新形式发票失败 invNumber=" + part.get(k).invNumber
                                + " err=" + err);
                    }
                }
            } catch (Exception e) {
                logger.error("MYSE-16 批量更新形式发票异常", e);
                for (PreparedInvoice p : part) {
                    p.result.put("action", "error");
                    p.result.put("error", "批量更新异常: " + e.getMessage());
                }
            }
        }

        // ===== 批量插入 =====
        for (int i = 0; i < insTargets.size(); i += BATCH_SIZE) {
            List<PreparedInvoice> part = insTargets.subList(i, Math.min(i + BATCH_SIZE, insTargets.size()));
            List<ProformaInvoice__c> objs = new ArrayList<>();
            for (PreparedInvoice p : part) {
                objs.add(p.invoice);
            }
            try {
                BatchOperateResult br = XObjectService.instance().insert(objs, true, true);
                List<OperateResult> ops = br == null ? null : br.getOperateResults();
                for (int k = 0; k < part.size(); k++) {
                    OperateResult op = (ops != null && k < ops.size()) ? ops.get(k) : null;
                    if (op != null && Boolean.TRUE.equals(op.getSuccess()) && op.getDataId() != null) {
                        part.get(k).invoice.setId(op.getDataId());
                        part.get(k).invoiceId = op.getDataId();
                        part.get(k).result.put("invoiceId", String.valueOf(part.get(k).invoiceId));
                    } else {
                        String err = op == null ? "批量插入无返回" : op.getErrorMessage();
                        part.get(k).result.put("action", "error");
                        part.get(k).result.put("error", "保存形式发票失败: " + err);
                        logger.error("MYSE-16 批量插入形式发票失败 invNumber=" + part.get(k).invNumber
                                + " err=" + err);
                    }
                }
            } catch (Exception e) {
                logger.error("MYSE-16 批量插入形式发票异常", e);
                for (PreparedInvoice p : part) {
                    p.result.put("action", "error");
                    p.result.put("error", "批量插入异常: " + e.getMessage());
                }
            }
        }
        // ===== 新增结果 id 映射校验 =====
        // BatchOperateResult.getOperateResults() 按下标取 dataId，理论上与入参同序；
        // 但一旦顺序错位，明细会挂到别的发票上（静默数据错）。这里用 1 次按 name 的批量查询交叉核对。
        if (!insTargets.isEmpty()) {
            try {
                Set<String> names = new LinkedHashSet<>();
                for (PreparedInvoice p : insTargets) {
                    if (p.invoice != null && p.invoice.getName() != null) {
                        names.add(p.invoice.getName());
                    }
                }
                Map<String, Long> idByName = batchResolveByField("proformaInvoice__c", "name", names);
                for (PreparedInvoice p : insTargets) {
                    if (p.invoice == null || p.invoice.getName() == null) {
                        continue;
                    }
                    Long realId = idByName.get(p.invoice.getName());
                    if (realId == null) {
                        logger.warn("MYSE-16 批量插入后未查到发票号 invNumber=" + p.invNumber + "（可能未落库）");
                    } else if (!realId.equals(p.invoiceId)) {
                        logger.warn("MYSE-16 批量插入 id 映射修正 invNumber=" + p.invNumber
                                + " 取值=" + p.invoiceId + " 实际=" + realId);
                        p.invoiceId = realId;
                        p.invoice.setId(realId);
                    }
                }
            } catch (Exception e) {
                logger.error("MYSE-16 批量插入 id 映射校验异常（不影响落库结果）", e);
            }
        }
        logger.info("MYSE-16 主记录批量落库完成: 新增=" + insTargets.size() + ", 更新=" + updTargets.size());
    }

    /**
     * 阶段 C-2：批量同步明细（整组替换：先批量删旧明细，再批量插新明细）。
     */
    private void batchSyncDetails(List<PreparedInvoice> prepared, RefCache cache) {
        // ===== 1) 批量删除旧明细 =====
        List<ProformaInvoiceDetail__c> toDelete = new ArrayList<>();
        for (PreparedInvoice p : prepared) {
            if (p.failed || p.isDelete || p.invoiceId == null) {
                continue;
            }
            List<Long> oldIds;
            if (p.isNew) {
                // 新建发票：id 刚生成，库里不可能有旧明细 → 不用查
                oldIds = Collections.emptyList();
            } else {
                oldIds = (cache != null && !cache.disabled)
                        ? cache.childIdsByInvoice.get(p.invoiceId) : null;
                if (oldIds == null) {
                    oldIds = queryIds("SELECT id FROM proformaInvoiceDetail__c WHERE proformaInvoiceId__c = " + p.invoiceId);
                }
            }
            for (Long id : oldIds) {
                ProformaInvoiceDetail__c d = new ProformaInvoiceDetail__c();
                d.setId(id);
                toDelete.add(d);
            }
            p.deletedItemCount = oldIds.size();
        }
        int deleted = batchDeleteRecords(toDelete, "形式发票明细");

        // ===== 2) 批量插入新明细 =====
        List<ProformaInvoiceDetail__c> toInsert = new ArrayList<>();
        for (PreparedInvoice p : prepared) {
            if (p.failed || p.isDelete || p.invoiceId == null) {
                continue;
            }
            for (ProformaInvoiceDetail__c d : p.details) {
                d.setProformaInvoiceId__c(p.invoiceId);
                toInsert.add(d);
            }
        }
        int inserted = batchInsertRecords(toInsert, "形式发票明细");
        // 回填每单的明细条数（出参）
        for (PreparedInvoice p : prepared) {
            if (p.failed || p.isDelete || p.invoiceId == null) {
                continue;
            }
            p.insertedCount = p.details.size();
            p.result.put("invoiceId", String.valueOf(p.invoiceId));
            p.result.put("detailCount", p.insertedCount);
            p.result.put("deletedItemCount", p.deletedItemCount);
        }
        logger.info("MYSE-16 明细批量落库完成: 插入=" + inserted + ", 删除=" + deleted);
    }

    /**
     * 阶段 C-3：批量删除（D 类单据）——先批量删明细，再批量删主记录。
     */
    private void batchDeleteInvoices(List<PreparedInvoice> prepared, RefCache cache) {
        List<PreparedInvoice> deletes = new ArrayList<>();
        for (PreparedInvoice p : prepared) {
            if (p.isDelete && !p.failed) {
                deletes.add(p);
            }
        }
        if (deletes.isEmpty()) {
            return;
        }
        // 1) 批量删明细
        List<ProformaInvoiceDetail__c> detailStubs = new ArrayList<>();
        for (PreparedInvoice p : deletes) {
            if (p.invoiceId == null) {
                p.result.put("deletedItemCount", 0);
                continue;
            }
            List<Long> oldIds = (cache != null && !cache.disabled)
                    ? cache.childIdsByInvoice.get(p.invoiceId) : null;
            if (oldIds == null) {
                oldIds = queryIds("SELECT id FROM proformaInvoiceDetail__c WHERE proformaInvoiceId__c = " + p.invoiceId);
            }
            for (Long id : oldIds) {
                ProformaInvoiceDetail__c d = new ProformaInvoiceDetail__c();
                d.setId(id);
                detailStubs.add(d);
            }
            p.deletedItemCount = oldIds.size();
        }
        batchDeleteRecords(detailStubs, "形式发票明细");

        // 2) 批量删主记录
        List<ProformaInvoice__c> mainStubs = new ArrayList<>();
        for (PreparedInvoice p : deletes) {
            if (p.invoiceId == null) {
                continue;
            }
            ProformaInvoice__c stub = new ProformaInvoice__c();
            stub.setId(p.invoiceId);
            mainStubs.add(stub);
        }
        batchDeleteRecords(mainStubs, "形式发票");

        for (PreparedInvoice p : deletes) {
            p.result.put("action", "delete");
            p.result.put("deletedItemCount", p.deletedItemCount);
            if (p.invoiceId == null) {
                logger.info("MYSE-16 删除：未找到形式发票 invNumber=" + p.invNumber);
            } else {
                logger.info("MYSE-16 删除形式发票成功 invNumber=" + p.invNumber + ", 子明细=" + p.deletedItemCount);
            }
        }
    }

    /** 批量插入（分批），返回成功条数；单条失败仅记日志 */
    private <T extends XObject> int batchInsertRecords(List<T> list, String label) {
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int success = 0;
        for (int i = 0; i < list.size(); i += BATCH_SIZE) {
            List<T> part = new ArrayList<>(list.subList(i, Math.min(i + BATCH_SIZE, list.size())));
            try {
                BatchOperateResult br = XObjectService.instance().insert(part, true, true);
                List<OperateResult> ops = br == null ? null : br.getOperateResults();
                if (ops != null) {
                    for (OperateResult op : ops) {
                        if (op != null && Boolean.TRUE.equals(op.getSuccess())) {
                            success++;
                        } else if (op != null) {
                            logger.error("MYSE-16 批量插入" + label + "失败: " + op.getErrorMessage());
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("MYSE-16 批量插入" + label + "异常: " + e.getMessage(), e);
            }
        }
        return success;
    }

    /** 批量删除（分批），返回删除条数；单条失败仅记日志 */
    private <T extends XObject> int batchDeleteRecords(List<T> stubs, String label) {
        if (stubs == null || stubs.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (int i = 0; i < stubs.size(); i += BATCH_SIZE) {
            List<T> part = new ArrayList<>(stubs.subList(i, Math.min(i + BATCH_SIZE, stubs.size())));
            try {
                BatchOperateResult br = XObjectService.instance().delete(part);
                List<OperateResult> ops = br == null ? null : br.getOperateResults();
                if (ops != null) {
                    for (OperateResult op : ops) {
                        if (op != null && Boolean.TRUE.equals(op.getSuccess())) {
                            deleted++;
                        } else if (op != null) {
                            logger.error("MYSE-16 批量删除" + label + "失败: " + op.getErrorMessage());
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("MYSE-16 批量删除" + label + "异常: " + e.getMessage(), e);
            }
        }
        return deleted;
    }

    /**
     * 处理单张形式发票。
     *
     * <p>控制字段（E_ACTCODE / E_DOCNUMBER / E_BUKRS / ZRES_FAC / E_WAERS）都在
     * 自己的容器里取：新批量格式为 T_OUTPUT.item 元素级，老格式为根级。</p>
     *
     * @return 本张单据的处理结果（失败时带 error 字段，不抛异常）
     */
    private PreparedInvoice prepareInvoice(JSONObject container, RefCache cache) {
        PreparedInvoice prep = new PreparedInvoice();
        JSONObject result = prep.result;
        try {
            // ====== 控制字段 ======
            String actionCode = trimToNull(asString(container, "E_ACTCODE"));
            if (actionCode == null) {
                actionCode = "A";
            }
            actionCode = actionCode.toUpperCase(Locale.ROOT);

            // 发票号：E_DOCNUMBER，作为 name(形式发票号) 的 upsert 键
            String invNumber = trimToNull(asString(container, "E_DOCNUMBER"));
            if (invNumber == null) {
                logger.error("MYSE-16 发票号(E_DOCNUMBER)为空，跳过该单据");
                result.put("action", "skip");
                result.put("error", "发票号(E_DOCNUMBER)不能为空");
                return prep;
            }
            result.put("invNumber", invNumber);

            // 返回信息（ZRES_FAC）
            JSONObject resFac = container.getJSONObject("ZRES_FAC");
            if (resFac != null) {
                logger.info("MYSE-16 ZRES_FAC.RETURNCODE=" + asString(resFac, "RETURNCODE")
                        + ", RETURNMESSAGE=" + asString(resFac, "RETURNMESSAGE"));
            }
            // 货币码（E_WAERS）：proformaInvoice__c 无对应字段，仅日志
            String waers = asString(container, "E_WAERS");
            if (waers != null) {
                logger.info("MYSE-16 E_WAERS=" + waers + "（CRM 无对应字段，仅日志；金额币种见 T_CABS_FAC.CURRENCY）");
            }
            // 公司代码（E_BUKRS）：无对应字段，仅日志
            String bukrs = asString(container, "E_BUKRS");
            if (bukrs != null) {
                logger.info("MYSE-16 E_BUKRS=" + bukrs + "（CRM 无对应字段，仅日志）");
            }

            prep.actionCode = actionCode;
            prep.invNumber = invNumber;

            // ====== D=删除（阶段 C 批量执行）=====
            if ("D".equals(actionCode)) {
                prep.isDelete = true;
                prep.invoiceId = findInvoiceIdByName(invNumber, cache);
                return prep;
            }

            // ====== A / C / U = upsert ======
            if (!"A".equals(actionCode) && !"C".equals(actionCode) && !"U".equals(actionCode)) {
                logger.warn("MYSE-16 未知操作码 E_ACTCODE=" + actionCode + "，按 upsert 处理");
            }
            JSONObject hdr = getSingleObject(container, "T_CABS_FAC");
            Long existingId = findInvoiceIdByName(invNumber, cache);
            prep.isNew = (existingId == null);
            prep.invoice = buildInvoice(container, hdr, invNumber, cache, existingId);

            JSONArray items = getItems(container, "T_POS_FACMYSEDV");
            prep.details = buildDetails(items, cache);

            result.put("action", "upsert");
            return prep;
        } catch (Exception e) {
            logger.error("MYSE-16 单据解析异常 invNumber=" + result.getString("invNumber")
                    + ": " + e.getMessage(), e);
            result.put("action", "error");
            result.put("error", e.getMessage());
            prep.failed = true;
            return prep;
        }
    }

    // ===================== 主记录 upsert =====================

    private ProformaInvoice__c buildInvoice(JSONObject root, JSONObject hdr, String invNumber,
                                           RefCache cache, Long existingId) {
        ProformaInvoice__c inv = new ProformaInvoice__c();
        if (existingId != null) {
            inv.setId(existingId);
        } else {
            inv.setEntityType(PROFORMA_INVOICE_BUSI_TYPE);
        }
        // 形式发票号（name 必填，且作 upsert 键）
        inv.setName(invNumber);

        if (hdr != null) {
            // 货币（CURRENCY → currency__c，文本）
            String currency = asString(hdr, "CURRENCY");
            if (currency != null) {
                inv.setCurrency__c(currency);
            }
            // 形式发票日期（DATECREATE → dateCreate__c，yyyy-MM-dd → Long）
            Long dateCreate = parseDate(asString(hdr, "DATECREATE"));
            if (dateCreate != null) {
                inv.setDateCreate__c(dateCreate);
            }
            // 付款条件（PAYERCOND → payerCond__c，选项码）
            String payerCond = asString(hdr, "PAYERCOND");
            if (payerCond != null) {
                Integer code = PAYERCOND_TO_CODE.get(payerCond.trim());
                if (code != null) {
                    inv.setPayerCond__c(code);
                } else {
                    logger.warn("MYSE-16 未匹配付款条件选项: PAYERCOND=" + payerCond + "（CRM 无对应选项，仅日志）");
                }
            }
            // 付款方式（PAYCOND → payCond__c，文本）
            String payCond = asString(hdr, "PAYCOND");
            if (payCond != null) {
                inv.setPayCond__c(payCond);
            }
            // 地址（ADRESS1 → adress1__c，文本）
            String adress1 = asString(hdr, "ADRESS1");
            if (adress1 != null) {
                inv.setAdress1__c(adress1);
            }
            // 电话（TELF1 → telf1__c，文本）
            String telf1 = asString(hdr, "TELF1");
            if (telf1 != null) {
                inv.setTelf1__c(telf1);
            }
            // 含税发票总额（TOT_PAG → tot_Pag__c，Double）
            Double totPag = parseDouble(asString(hdr, "TOT_PAG"));
            if (totPag != null) {
                inv.setTot_Pag__c(totPag);
            }
            // 不含税发票总额（TOTALNET → base_Iva2__c，Double）
            Double totalNet = parseDouble(asString(hdr, "TOTALNET"));
            if (totalNet != null) {
                inv.setBase_Iva2__c(totalNet);
            }
            // 净发票总额（BASE_IVA → base_Iva1__c，Double）
            Double baseIva = parseDouble(asString(hdr, "BASE_IVA"));
            if (baseIva != null) {
                inv.setBase_Iva1__c(baseIva);
            }
            // 未折扣总额（TOT_BTO → tot_Bto__c，Double；SAP 无折扣时可能不发本字段）
            Double totBto = parseDouble(asString(hdr, "TOT_BTO"));
            if (totBto != null) {
                inv.setTot_Bto__c(totBto);
            }
            // 折扣总额（DTO_COMER → dto_Comer__c，Double；SAP 无折扣时可能不发本字段）
            Double dtoComer = parseDouble(asString(hdr, "DTO_COMER"));
            if (dtoComer != null) {
                inv.setDto_Comer__c(dtoComer);
            }
            // 增值税率（PCTJ_IVA → pctj_Iva__c，itemType 33 = 0~1 比例；SAP 发百分比→÷100，如 13.000→0.13）
            Double pctj = parseDouble(asString(hdr, "PCTJ_IVA"));
            if (pctj != null) {
                inv.setPctj_Iva__c(pctj / 100.0);
            }
            // 增值税金额（IMP_IVA → imp_Iva__c，Double）
            Double impIva = parseDouble(asString(hdr, "IMP_IVA"));
            if (impIva != null) {
                inv.setImp_Iva__c(impIva);
            }
            // 发货单关联（DELIVERYNOTEID → deliveryNoteId__c，lookup delivery__c，按 delivery__c 字段反查）
            String deliveryNoteId = asString(hdr, "DELIVERYNOTEID");
            if (deliveryNoteId != null) {
                Long dId = resolveByField("delivery__c", "delivery__c", deliveryNoteId, cache);
                if (dId != null) {
                    inv.setDeliveryNoteId__c(dId);
                } else {
                    logger.info("MYSE-16 未找到发货单 delivery__c: delivery__c=" + deliveryNoteId + "（仅日志）");
                }
            }
            //销售组织
            String vkorg=asString(hdr, "VKORG");
            if (vkorg != null) inv.setVkorg__c(vkorgIdByNumber(vkorg, cache));

        }

        // 账户关联（E_KUNRG → purchaserId__c，lookup account，按 kunnr__c 反查）
        String kunrg = asString(root, "E_KUNRG");
        if (kunrg != null) {
            Long accId = resolveByField("account", "kunnr__c", Integer.parseInt(kunrg)+"", cache);
            if (accId != null) {
                inv.setPurchaserId__c(accId);
            } else {
                logger.info("MYSE-16 未找到账户 account: kunnr__c=" + kunrg + "（仅日志）");
            }
        }

        // 落库由阶段 C 的批量方法统一执行（batchUpsertMain）
        return inv;
    }

    // ===================== 明细同步（整组替换：先删后插）=====================

    private List<ProformaInvoiceDetail__c> buildDetails(JSONArray items, RefCache cache) {
        List<ProformaInvoiceDetail__c> details = new ArrayList<>();
        int resolvedProduct = 0;
        int resolvedOrder = 0;
        int resolvedDelivery = 0;
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                ProformaInvoiceDetail__c d = new ProformaInvoiceDetail__c();
                d.setEntityType(PROFORMA_INVOICE_DETAIL_BUSI_TYPE);
                // proformaInvoiceId__c 在阶段 C 批量落库前统一回填

                // 名称（必填文本）：明细号 INVNUMBER
                String invNum = asString(item, "INVNUMBER");
                d.setName(invNum != null ? invNum : ("line" + i));

                // 订货号（MATNR → catalogNumber__c，lookup product，按 productCode__c 反查）
                String matNo = asString(item, "MATNR");
                if (matNo != null) {
                    Long productId = resolveByField("product", "productCode__c", matNo, cache);
                    if (productId != null) {
                        d.setCatalogNumber__c(productId);
                        resolvedProduct++;
                    } else {
                        logger.warn("MYSE-16 未找到产品 product: productCode__c=" + matNo);
                    }
                }
                // 数量（QUANTITY → quantity__c，整型）
                Double qty = parseDouble(asString(item, "QUANTITY"));
                if (qty != null) {
                    d.setQuantity__c(qty.longValue());
                }
                // 列表价（TOTALIMPORT → totalImport__c，Double）
                Double totalImport = parseDouble(asString(item, "TOTALIMPORT"));
                if (totalImport != null) {
                    d.setTotalImport__c(totalImport);
                }
                // 折扣（DTO → dto__c，Double）
                Double dto = parseDouble(asString(item, "DTO"));
                if (dto != null) {
                    d.setDto__c(dto);
                }
                // 净价（折扣后）（NETIMPORT → netImport__c，Double）
                Double net = parseDouble(asString(item, "NETIMPORT"));
                if (net != null) {
                    d.setNetImport__c(net);
                }
                // 订单关联（ORDERNUMBER → orderNumber__c lookup 反查 + order__c 文本兜底）
                String orderPos = asString(item, "ORDERPOS");

                String orderNo = asString(item, "ORDERNUMBER");
                Long oId = null;
                if (orderNo != null) {
                    d.setOrder__c(orderNo+"/"+orderPos);
                    // 订单号文本（期初数据无订单时先存，后面刷数据回填关联；model.jar 无 setter，用 setAttribute 写）
                    d.setAttribute("orderText__c", orderNo);
                    oId = resolveByField("order__c", "orderNumberAutoGenerate__c", orderNo, cache);
                    if (oId != null) {
                        d.setOrderNumber__c(oId);
                        resolvedOrder++;
                    } else {
                        logger.info("MYSE-16 未找到销售订单 order__c: orderNumberAutoGenerate__c=" + orderNo + "（仅存文本 order__c）");
                    }
                }
                // 订单行号文本（同上，先存文本）
                if (orderPos != null) {
                    d.setAttribute("orderLineText__c", orderPos);
                }

                if (orderPos != null) {
                    Long odId = resolveOrderPos(orderPos, oId, cache);
                    if (odId != null) {
                        d.setOrderPos__c(odId);
                    } else {
                        logger.info("MYSE-16 未找到订单明细 orderDetail__c(订单id=" + oId + ", 行号=" + orderPos + ")，仅忽略");
                    }
                }
                // 发货单关联（SHIPNUMBER → deliveryNumber__c lookup 反查 + delivery__c 文本兜底）
                String shipNo = asString(item, "SHIPNUMBER");
                if (shipNo != null) {
                    d.setDelivery__c(shipNo+"/"+orderPos);
                    Long dId = resolveByField("delivery__c", "delivery__c", shipNo, cache);
                    if (dId != null) {
                        d.setDeliveryNumber__c(dId);
                        resolvedDelivery++;
                    } else {
                        logger.info("MYSE-16 未找到发货单 delivery__c: delivery__c=" + shipNo + "（仅存文本 delivery__c）");
                    }
                }

                details.add(d);
            }
        }
        logger.info("MYSE-16 形式发票明细构造完成: 条数=" + details.size()
                + ", 解析产品=" + resolvedProduct + ", 解析订单=" + resolvedOrder
                + ", 解析发货单=" + resolvedDelivery);
        return details;
    }

    // ===================== 删除 =====================

    /** 删除某形式发票下明细记录，返回删除条数（用系统鉴权 XOQL 绕过 API 上下文权限隔离）。 */

    // ===================== 批量预取（一次查完，循环内不再查库） =====================

    /**
     * 批量预取的 id 映射缓存。
     *
     * <p>所有反查（发票号/客户/产品/订单/发货单/销售组织/订单行/子表明细）都在处理单据前
     * <b>一次性批量查完</b>，循环内只查内存字典，避免按行、按单重复查询触发平台查询次数限制。</p>
     */
    private static class RefCache {
        /** proformaInvoice__c.name（发票号） → id */
        Map<String, Long> invoiceByName = new HashMap<>();
        /** delivery__c.delivery__c（发货单号） → id */
        Map<String, Long> deliveryByNo = new HashMap<>();
        /** saleorg__c.name（销售组织编号） → id */
        Map<String, Long> saleorgByName = new HashMap<>();
        /** account.kunnr__c（客户号） → id */
        Map<String, Long> accountByKunnr = new HashMap<>();
        /** product.productCode__c（物料号） → id */
        Map<String, Long> productByCode = new HashMap<>();
        /** order__c.orderNumberAutoGenerate__c（订单号） → id */
        Map<String, Long> orderByNo = new HashMap<>();
        /** orderId → (posicion__c → orderDetail__c.id) */
        Map<Long, Map<String, Long>> orderPosByOrder = new HashMap<>();
        /** 发票 id → 其明细 id 列表（整组替换时删旧明细用） */
        Map<Long, List<Long>> childIdsByInvoice = new HashMap<>();
        /** 预取失败时置 true → 全部退化为逐条实时查询（保证功能不挂） */
        boolean disabled = false;
        /**
         * 预取失败的「对象+字段」组合（key = entity#field）。
         * <p>某类反查预取失败时，<b>只有该类</b>退回实时查询，其它类继续用缓存
         * （避免一���失败导致全部反查都退化成逐条查）。</p>
         */
        Set<String> failedLookups = new HashSet<>();
        /** 预取失败登记 */
        void markFailed(String entity, String field) {
            failedLookups.add(entity + "#" + field);
        }

        /** 该类反查是否需要走实时查询（预取失败） */
        boolean isFailed(String entity, String field) {
            return failedLookups.contains(entity + "#" + field);
        }

        /**
         * 按对象+字段取对应的缓存 map；无对应缓存返回 null（调用方转实时查询）。
         */
        Map<String, Long> mapFor(String entity, String field) {
            if ("delivery__c".equals(entity) && "delivery__c".equals(field)) {
                return deliveryByNo;
            }
            if ("account".equals(entity) && "kunnr__c".equals(field)) {
                return accountByKunnr;
            }
            if ("product".equals(entity) && "productCode__c".equals(field)) {
                return productByCode;
            }
            if ("order__c".equals(entity) && "orderNumberAutoGenerate__c".equals(field)) {
                return orderByNo;
            }
            if ("saleorg__c".equals(entity) && "name".equals(field)) {
                return saleorgByName;
            }
            if ("proformaInvoice__c".equals(entity) && "name".equals(field)) {
                return invoiceByName;
            }
            return null;
        }
    }

    /** 批量 IN 查询的分批大小（避免 SQL 过长） */
    private static final int BATCH_SIZE = 200;

    /**
     * 单次查询返回条数上限。
     * <p><b>实测（2026-11-02 沙盒）</b>：不带 LIMIT 只返回 100 条；写 {@code LIMIT 0,500} / {@code LIMIT 0,2000}
     * 也只返回 300 条 —— 平台单次响应硬上限 300，超出会<b>静默截断</b>（不报错，最容易丢数据）。</p>
     */
    private static final int PAGE_LIMIT = 300;

    /** 分页取全的最大总行数保护（超出记告警并停止，避免异常情况下无限翻页） */
    private static final int MAX_FETCH_ROWS = 50000;

    /**
     * 预取整批报文所需的全部关联 id（每类对象一次 IN 查询，分批执行）。
     *
     * <p>失败时置 {@code cache.disabled=true}，调用方自动退化为逐条实时查询，功能不挂。</p>
     */
    private RefCache prefetch(List<JSONObject> containers) {
        RefCache cache = new RefCache();
        Set<String> invNumbers = new LinkedHashSet<>();
        Set<String> deliveryNos = new LinkedHashSet<>();
        Set<String> vkorgs = new LinkedHashSet<>();
        Set<String> kunrgs = new LinkedHashSet<>();
        Set<String> matNos = new LinkedHashSet<>();
        Set<String> orderNos = new LinkedHashSet<>();

        // ===== 1. 扫描整批报文，收集所有待反查的 key =====
        for (JSONObject container : containers) {
            String invNumber = trimToNull(asString(container, "E_DOCNUMBER"));
            if (invNumber != null) {
                invNumbers.add(invNumber.trim());
            }
            String kunrg = trimToNull(asString(container, "E_KUNRG"));
            if (kunrg != null) {
                // 与 resolveByField 的口径保持一致：客户号去前导零后反查
                kunrgs.add(normalizeKey("account", "kunnr__c", kunrg));
            }
            JSONObject hdr = getSingleObject(container, "T_CABS_FAC");
            if (hdr != null) {
                String d = trimToNull(asString(hdr, "DELIVERYNOTEID"));
                if (d != null) {
                    deliveryNos.add(d.trim());
                }
                String v = trimToNull(asString(hdr, "VKORG"));
                if (v != null) {
                    vkorgs.add(v.trim());
                }
            }
            JSONArray items = getItems(container, "T_POS_FACMYSEDV");
            if (items != null) {
                for (int i = 0; i < items.size(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String m = trimToNull(asString(item, "MATNR"));
                    if (m != null) {
                        matNos.add(m.trim());
                    }
                    String o = trimToNull(asString(item, "ORDERNUMBER"));
                    if (o != null) {
                        orderNos.add(o.trim());
                    }
                    String s = trimToNull(asString(item, "SHIPNUMBER"));
                    if (s != null) {
                        deliveryNos.add(s.trim());
                    }
                }
            }
        }

        // ===== 2. 各类对象批量反查（每类独立降级：某类失败只影响该类）=====
        // 2.1 发票号 → id
        try {
            cache.invoiceByName = batchResolveByField("proformaInvoice__c", "name", invNumbers);
        } catch (Exception e) {
            logger.error("MYSE-16 预取发票号失败，该类退回实时查询", e);
            cache.markFailed("proformaInvoice__c", "name");
        }
        // 2.2 发货单号 → id
        try {
            cache.deliveryByNo = batchResolveByField("delivery__c", "delivery__c", deliveryNos);
        } catch (Exception e) {
            logger.error("MYSE-16 预取发货单号失败，该类退回实时查询", e);
            cache.markFailed("delivery__c", "delivery__c");
        }
        // 2.3 销售组织 → id
        try {
            cache.saleorgByName = batchResolveByField("saleorg__c", "name", vkorgs);
        } catch (Exception e) {
            logger.error("MYSE-16 预取销售组织失败，该类退回实时查询", e);
            cache.markFailed("saleorg__c", "name");
        }
        // 2.4 客户号 → id
        try {
            cache.accountByKunnr = batchResolveByField("account", "kunnr__c", kunrgs);
        } catch (Exception e) {
            logger.error("MYSE-16 预取客户号失败，该类退回实时查询", e);
            cache.markFailed("account", "kunnr__c");
        }
        // 2.5 物料号 → id
        try {
            cache.productByCode = batchResolveByField("product", "productCode__c", matNos);
        } catch (Exception e) {
            logger.error("MYSE-16 预取物料号失败，该类退回实时查询", e);
            cache.markFailed("product", "productCode__c");
        }
        // 2.6 销售订单号 → id
        try {
            cache.orderByNo = batchResolveByField("order__c", "orderNumberAutoGenerate__c", orderNos);
        } catch (Exception e) {
            logger.error("MYSE-16 预取销售订单号失败，该类退回实时查询", e);
            cache.markFailed("order__c", "orderNumberAutoGenerate__c");
        }
        // 2.7 订单明细行：按订单 id 批量查（再按行号在内存里匹配）
        try {
            cache.orderPosByOrder = batchLoadOrderPositions(cache.orderByNo.values());
        } catch (Exception e) {
            logger.error("MYSE-16 预取订单明细行失败，该类退回按订单整单查询", e);
            cache.markFailed("orderDetail__c", "posicion__c");
        }
        // 2.8 发票子表明细：按已存在的发票 id 批量查（整组替换时删旧明细）
        try {
            cache.childIdsByInvoice = batchLoadChildIds(cache.invoiceByName.values());
        } catch (Exception e) {
            logger.error("MYSE-16 预取发票子表明细失败，该类退回实时查询", e);
            cache.markFailed("proformaInvoiceDetail__c", "proformaInvoiceId__c");
        }

        logger.info("MYSE-16 批量预取完成: 发票号=" + invNumbers.size() + " 发货单号=" + deliveryNos.size()
                + " 销售组织=" + vkorgs.size() + " 客户=" + kunrgs.size()
                + " 物料号=" + matNos.size() + " 订单号=" + orderNos.size()
                + " 降级实时查询=" + cache.disabled);
        return cache;
    }

    /**
     * 反查 key 归一化：统一 trim；客户号（account.kunnr__c）去前导零
     * （原实时查询用的是 {@code Integer.parseInt(kunrg)+""}，预取与查询必须同口径）。
     */
    private String normalizeKey(String entity, String field, String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if ("account".equals(entity) && "kunnr__c".equals(field)) {
            try {
                v = Integer.parseInt(v) + "";
            } catch (Exception ignore) {
                // 非数字客户号：原样保留
            }
        }
        return v;
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
                sb.append("'").append(escape(v)).append("'");
            }
            String sql = "SELECT id, " + field + " FROM " + entity
                    + " WHERE " + field + " IN (" + sb + ")";
            for (JSONObject rec : queryAll(sql)) {
                if (rec == null) {
                    continue;
                }
                Long id = asLong(rec.get("id"));
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

    /**
     * 批量加载订单明细行：{@code SELECT id, order__c, posicion__c FROM orderDetail__c WHERE order__c IN (...)}
     *
     * @return orderId → (posicion__c → 明细 id)
     */
    private Map<Long, Map<String, Long>> batchLoadOrderPositions(Collection<Long> orderIds) {
        Map<Long, Map<String, Long>> out = new HashMap<>();
        if (orderIds == null || orderIds.isEmpty()) {
            return out;
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(orderIds));
        for (int i = 0; i < ids.size(); i += BATCH_SIZE) {
            List<Long> part = ids.subList(i, Math.min(i + BATCH_SIZE, ids.size()));
            StringBuilder sb = new StringBuilder();
            for (Long id : part) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(id);
            }
            String sql = "SELECT id, order__c, posicion__c FROM orderDetail__c WHERE order__c IN (" + sb + ")";
            for (JSONObject rec : queryAll(sql)) {
                if (rec == null) {
                    continue;
                }
                Long id = asLong(rec.get("id"));
                Long orderId = asLong(rec.get("order__c"));
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

    /**
     * 批量加载形式发票子表明细 id：{@code SELECT id, proformaInvoiceId__c FROM proformaInvoiceDetail__c WHERE proformaInvoiceId__c IN (...)}
     *
     * @return 发票 id → 明细 id 列表
     */
    private Map<Long, List<Long>> batchLoadChildIds(Collection<Long> invoiceIds) {
        Map<Long, List<Long>> out = new HashMap<>();
        if (invoiceIds == null || invoiceIds.isEmpty()) {
            return out;
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(invoiceIds));
        for (int i = 0; i < ids.size(); i += BATCH_SIZE) {
            List<Long> part = ids.subList(i, Math.min(i + BATCH_SIZE, ids.size()));
            StringBuilder sb = new StringBuilder();
            for (Long id : part) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(id);
            }
            String sql = "SELECT id, proformaInvoiceId__c FROM proformaInvoiceDetail__c"
                    + " WHERE proformaInvoiceId__c IN (" + sb + ")";
            for (JSONObject rec : queryAll(sql)) {
                if (rec == null) {
                    continue;
                }
                Long id = asLong(rec.get("id"));
                Long invoiceId = asLong(rec.get("proformaInvoiceId__c"));
                if (id == null || invoiceId == null) {
                    continue;
                }
                out.computeIfAbsent(invoiceId, k -> new ArrayList<>()).add(id);
            }
        }
        return out;
    }

    // ===================== 查询/解析辅助 =====================

    /**
     * 分页取全：反复 {@code LIMIT offset, 300} 直到取完。
     *
     * <p><b>为什么必须分页</b>：平台单次响应条数有硬上限（实测：不带 LIMIT 只返 100 条，
     * 写 {@code LIMIT 0,500} 也只返 300 条），超出会<b>静默截断</b>且不报错 —— 最容易丢数据。</p>
     *
     * @param baseSql 不含 LIMIT 的查询语句（内部自动补 ORDER BY id 保证分页稳定）
     * @return 全部记录
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
                logger.error("MYSE-16 分页查询失败 sql=" + sql, e);
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
            logger.warn("MYSE-16 分页查询达到上限 " + MAX_FETCH_ROWS + " 行，结果可能不完整 sql=" + baseSql);
        }
        return all;
    }

    /**
     * 用平台内部 XOQL（系统鉴权，绕过 API 上下文对象级权限隔离）执行查询并返回 id 列表。
     *
     */
    private List<Long> queryIds(String xoql) {
        List<Long> ids = new ArrayList<>();
        try {
            for (JSONObject rec : queryAll(xoql)) {
                if (rec == null) {
                    continue;
                }
                Long id = asLong(rec.get("id"));
                if (id != null) {
                    ids.add(id);
                }
            }
        } catch (Exception e) {
            logger.warn("MYSE-16 内部XOQL查询失败: " + xoql, e);
        }
        return ids;
    }

    private Long findInvoiceIdByName(String invNumber, RefCache cache) {
        if (cache != null && !cache.disabled) {
            // 批量查过没有就是没有 → 不再二次查询
            return cache.invoiceByName.get(invNumber == null ? null : invNumber.trim());
        }
        // 不带 LIMIT：queryAll 会自动加分页（ORDER BY id LIMIT offset,300）
        List<Long> ids = queryIds("SELECT id FROM proformaInvoice__c WHERE name = '" + escape(invNumber) + "'");
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Long vkorgIdByNumber(String vkorg, RefCache cache) {
        if (cache != null && !cache.disabled) {
            return cache.saleorgByName.get(vkorg == null ? null : vkorg.trim());
        }
        List<Long> ids = queryIds("SELECT id FROM saleorg__c WHERE name = '" + escape(vkorg) + "'");
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 通用反查：SELECT id FROM entity WHERE field = 'value'（用系统鉴权 XOQL，分页由 queryAll 处理）。 */
    private Long resolveByField(String entity, String field, String value, RefCache cache) {
        if (value == null) {
            return null;
        }
        if (cache != null && !cache.disabled) {
            Map<String, Long> map = cache.mapFor(entity, field);
            if (map != null) {
                // 批量查过没有就是没有 → 直接返回，不再二次查询
                return map.get(normalizeKey(entity, field, value));
            }
        }
        try {
            // 不带 LIMIT：queryAll 会自动加分页（ORDER BY id LIMIT offset,300）
            String xoql = "SELECT id FROM " + entity + " WHERE " + field + " = '" + escape(value) + "'";
            List<Long> ids = queryIds(xoql);
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
        } catch (Exception e) {
            logger.warn("MYSE-16 反查失败 entity=" + entity + " " + field + "=" + value, e);
        }
        return null;
    }

    /** 反查订单明细行：限定订单（order__c=订单id）+行号（posicion__c）双条件，仅行号会跨订单错配。
     *  SAP 发票明细行号(如 000010,6位)与订单明细 posicion__c(00010,5位)位宽不同，补零兼容。
     *  orderId 为 null（订单未解析到）时直接返回 null，不退化为纯行号查询。 */
    private Long resolveOrderPos(String pos, Long orderId, RefCache cache) {
        if (pos == null || orderId == null) {
            return null;
        }
        boolean cached = cache != null && !cache.disabled;
        Set<String> cands = new LinkedHashSet<String>();
        cands.add(pos);
        if (pos.matches("\\d+")) {
            try {
                int v = Integer.parseInt(pos);
                cands.add(String.format("%05d", v)); // 补零5位（如 00010）
                cands.add(String.valueOf(v));        // 裸数字（如 10；沙盒实测 posicion__c 有此格式，Myse15 原样落库不格式化）
            } catch (Exception ignore) {
                // 忽略，保留原值
            }
        }
        if (cached) {
            Map<String, Long> posMap = cache.orderPosByOrder.get(orderId);
            if (posMap == null) {
                return null;   // 该订单下没有明细行（预取已覆盖全批）
            }
            for (String c : cands) {
                Long id = posMap.get(c);
                if (id != null) {
                    return id;
                }
            }
            // 批量查过没有就是没有 → 不再二次查询
            return null;
        }
        // 无预取上下文（不按批处理时的单张调用）→ 实时查一次
        StringBuilder in = new StringBuilder("'").append(escape(pos)).append("'");
        if (pos.matches("\\d+")) {
            try {
                int v = Integer.parseInt(pos);
                in.append(",'").append(String.format("%05d", v)).append("'");
                in.append(",'").append(String.valueOf(v)).append("'");
            } catch (Exception ignore) {
                // 忽略
            }
        }
        String xoql = "SELECT id FROM orderDetail__c WHERE order__c = " + orderId
                + " AND posicion__c IN (" + in + ")";
        List<Long> ids = queryIds(xoql);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
