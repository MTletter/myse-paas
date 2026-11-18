package other.xsy.myse.apis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.data.model.MessageCenter__c;
import com.rkhd.platform.sdk.data.model.QuoteDetail__c;
import com.rkhd.platform.sdk.data.model.Quote__c;
import com.rkhd.platform.sdk.data.model.SpecialOrderApplication__c;
import com.rkhd.platform.sdk.exception.CustomConfigException;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.BatchOperateResult;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.CustomConfigService;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;
import com.rkhd.platform.sdk.http.RkhdHttpClient;
import com.rkhd.platform.sdk.http.RkhdHttpData;
import org.apache.commons.lang.StringUtils;
import other.xsy.myse.utils.GlobalPickHelper;
import other.xsy.myse.utils.HttpUtil;
import static other.xsy.myse.utils.JsonValues.getSingleObject;
import static other.xsy.myse.utils.JsonValues.parseDateTime;
import other.xsy.myse.utils.QueryUtil;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * MYSE-14 推送报价单（SAP → mySE，被动接收）
 * <p>
 * 销售易 API 路径：/rest/data/v2.0/scripts/api/myse/quotation/push
 * </p>
 * <p><b>实际 SAP 报文结构（与 YAML 设计稿不同，以真实 IDoc 为准）</b>：
 * 根节点控制字段：E_ACTCODE(C/U/D)、E_DOCNUMBER、E_BUKRS、E_WAERS、E_TIMESTAMP、E_RES_ORDERS_LINE
 * T_ZORDER_HDR.item        —— 报价单头（单条为对象；批量为数组，数组模式下每个 item 内内联自己的 T_ZORDER_DTL）
 * T_ZORDER_DTL.item         —— 行项目（单条为对象，多条为数组）
 * T_ZQUOTATION_ORDER_NUMBER.item —— SAP 内部报价单编号映射
 * </p>
 * <p><b>操作代码</b>：C=新建，U=更新，D=删除（C/U 均按 quoteNumberAutoGenerate__c 做 upsert）</p>
 *
 * <p><b>返回口径</b>：SAP 不看返回，接口恒返回 success，
 * 每个报价单的成功/失败只记 log（成功带 quoteNumber + 明细计数，失败带 quoteNumber + 原因）。</p>
 *
 * <p><b>批量推送</b>：T_ZORDER_HDR.item 兼容 单对象 / 数组。
 * 单条（历史报文）明细取根节点 T_ZORDER_DTL；批量模式下每个 HDR item 需内联自己的 T_ZORDER_DTL
 * （否则该单明细视为空，避免误挂其它单的明细）。</p>
 *
 */
@RestApi(baseUrl = "/myse")
public class Myse14QuotationPushApi extends SapPushBaseApi {

    /** 本接口的日志 TAG（基类工具方法用它打日志） */
    @Override
    protected String tag() {
        return TAG;
    }

    /** 消息中心业务类型（基类构造通知时用，避免重复读配置集） */
    @Override
    protected Long messageCenterBusiType() {
        return MESSAGE_CENTER_BUSI_TYPE;
    }

    /** 日志标识 */
    private static final String TAG = "MYSE-14";

    /** 批量写入 / IN 预查询单批大小 */

    /** 报价单业务类型（entityType），取自租户元数据 busiTypeId。 */
    private static final Long QUOTE_BUSI_TYPE;
    /** 报价单明细业务类型（entityType）。 */
    private static final Long QUOTE_DETAIL_BUSI_TYPE;
    /** 消息中心业务类型（entityType），用于写期望发货日期变更通知。 */
    private static final Long MESSAGE_CENTER_BUSI_TYPE;
    /** 消息类型：待办事项（与 Myse15 订单状态变更通知一致）。 */
    private static final int MSG_TYPE_TODO = 1;
    /** 消息类型：报价单状态变更通知（原逻辑硬编码 6，抽到基类后改为具名常量）。 */
    private static final int MSG_STATUS_CHANGED = 6;
    /** 消息已读标志：2=否（未读）。 */

    /**
     * quoteStatus__c 的【全部拒单】选项 apiKey（沙盒实测 value=5，label=全部拒单）。
     * <p>用于判断库里报价单是否已被业务方标记为全部拒单。</p>
     */
    private static final String QUOTE_STATUS_REJECTED_API_KEY = "5";

    /** 【全部拒单】选项的 label，apiKey 反查失败时的兜底判定用。 */
    private static final String QUOTE_STATUS_REJECTED_LABEL = "全部拒单";

    static {
        try {
            // 获取对象实例
            CustomConfigService instance = CustomConfigService.instance();
            // 获取配置集,返回此配置集中的所有配置项
            Map<String, String> configSet = instance.getConfigSet("entityType");
            QUOTE_BUSI_TYPE=Long.parseLong(configSet.get("QUOTE_BUSI_TYPE"));
            QUOTE_DETAIL_BUSI_TYPE=Long.parseLong(configSet.get("QUOTE_DETAIL_BUSI_TYPE"));
            MESSAGE_CENTER_BUSI_TYPE=Long.parseLong(configSet.get("MessageCenter"));
        } catch (CustomConfigException e) {
            throw new RuntimeException(e);
        }
    }

    /** SAP 单位码 → CRM salesUnit__c 选项码（件=1/箱=7/套=11/公斤=8/米=4/公升=17/吨=15/小时=2 等）。可扩展。 */
    private static final Map<String, Integer> SAP_UNIT_TO_CODE;
    static {
        Map<String, Integer> m = new HashMap<>();
        m.put("PC", 1); m.put("ST", 1); m.put("EA", 1);    // 件 Piece / Stück / Each
        m.put("BOX", 7); m.put("CASE", 7);                 // 箱
        m.put("SET", 11);                                  // 套
        m.put("KG", 8);                                    // 公斤
        m.put("G", 18); m.put("GR", 18);                   // 克
        m.put("T", 15);                                    // 吨
        m.put("M", 4);                                     // 米
        m.put("L", 17); m.put("LR", 17);                   // 公升
        m.put("HR", 2);                                    // 小时
        m.put("BAG", 10);                                  // 袋
        m.put("DRUM", 6);                                  // 桶
        m.put("ROLL", 20);                                 // 卷
        m.put("PAC", 16);                                  // 包
        SAP_UNIT_TO_CODE = Collections.unmodifiableMap(m);
    }

    /** 单条待处理报价单（头 + 明细 + 控制字段 + 处理结果占位）。 */
    private static class Doc {
        JSONObject hdr;
        JSONArray dtl;
        String orderNumber;
        String bukrs;
        String returnMsg;
        String sapQuotationOrderNo;
        Long quoteId;
        int changedCount;
        /**
         * 特价标志（SAP BSTZD）。= "ESPA" 表示特价申请单，此时需把报价单挂到特价申请单上。
         */
        String pmsFlag;
        /**
         * 特价申请单号（pmsId__c，SAP PURCHASEORDER 在特价场景下的值），用于反查特价申请单。
         */
        String pmsId;
        /**
         * 本单的操作码（E_ACTCODE）。
         * <p>老报文格式：整批共用根节点的 E_ACTCODE；
         * 新批量格式（T_OUTPUT 包装）：每条单据有自己的 E_ACTCODE。</p>
         */
        String actionCode;
    }

    /** 待写入的报价单及其状态变更通知信息（批量写入后回填 id 再发通知）。 */
    private static class PendingQuote {
        Quote__c q;
        Doc doc;
        Integer oldStatus;
        Integer newStatus;
        String oldStatusLabel;
        String newStatusLabel;
        Long notifyAccountId;
        Long notifyOwnerId;
    }

    /** 待写入的报价单明细（批量写入后回填 id，防止同行号重复插入）。 */
    private static class PendingDetail {
        QuoteDetail__c d;
        Doc doc;
        Long quoteId;
        String posKey;
        Long rdd;
        boolean dateChanged;
    }

    @RestMapping(value = "/quotation/push", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        try {
            if (body == null || body.isEmpty()) {
                logger.warn(TAG + " 请求体为空，忽略");
                return HttpUtil.success(null).toJSONString();
            }
            JSONObject root = JSON.parseObject(body);

            // ====== 单据容器：兼容两种报文格式 ======
            // 老格式：root 自身就是单据节点（root.T_ZORDER_HDR）
            // 新批量格式：root.T_OUTPUT.item[] 每个元素是一个完整单据，自带控制字段
            //           （E_ACTCODE / E_DOCNUMBER / E_BUKRS / E_RES_ORDERS_LINE 都在元素内部）
            List<JSONObject> containers = new ArrayList<>();
            JSONArray outputItems = getItems(root, "T_OUTPUT");
            boolean wrapped = outputItems != null && !outputItems.isEmpty();
            if (wrapped) {
                for (int i = 0; i < outputItems.size(); i++) {
                    JSONObject one = outputItems.getJSONObject(i);
                    if (one == null) {
                        logger.warn(TAG + " T_OUTPUT 第 " + i + " 条为空，跳过");
                        continue;
                    }
                    containers.add(one);
                }
                logger.info(TAG + " 报文格式: T_OUTPUT 批量包装, 单据数=" + containers.size());
            } else {
                containers.add(root);
            }

            // ====== 拆分成单条报价单（每个容器独立解析控制字段与明细）======
            List<Doc> docs = new ArrayList<>();
            for (JSONObject container : containers) {
                // 控制字段：容器级（老格式即根级，新格式为 T_OUTPUT.item 元素级）
                String cAction = trimToNull(container.getString("E_ACTCODE"));
                if (cAction == null) {
                    cAction = "U";
                }
                cAction = cAction.toUpperCase(Locale.ROOT);
                if (!"A".equals(cAction) && !"C".equals(cAction) && !"U".equals(cAction)
                        && !"D".equals(cAction)) {
                    logger.warn(TAG + " 未知操作码 E_ACTCODE=" + cAction + "，按 upsert 处理");
                }

                // T_ZORDER_HDR.item 兼容 单对象（历史报文）/ 数组（批量推送）
                JSONArray hdrs = getItems(container, "T_ZORDER_HDR");
                if (hdrs == null || hdrs.isEmpty()) {
                    logger.warn(TAG + " T_ZORDER_HDR 为空，忽略");
                    continue;
                }
                boolean batchMode = hdrs.size() > 1;
                if (batchMode) {
                    logger.info(TAG + " 入参(批量): 条数=" + hdrs.size());
                } else {
                    logger.info(TAG + " 入参(单条): " + container.toJSONString());
                }

                // 容器级共用控制字段（单条场景）
                JSONArray rootDtl = getItems(container, "T_ZORDER_DTL");
                String rootBukrs = trimToNull(container.getString("E_BUKRS"));
                JSONObject resOrders = container.getJSONObject("E_RES_ORDERS_LINE");
                String rootReturnMsg = resOrders != null ? trimToNull(resOrders.getString("RETURNMESSAGE")) : null;
                JSONObject rootQon = getSingleObject(container, "T_ZQUOTATION_ORDER_NUMBER");
                String rootSapQuotationOrderNo = rootQon != null ? trimToNull(rootQon.getString("ORDER_NUMBER")) : null;

                for (int i = 0; i < hdrs.size(); i++) {
                    JSONObject hdr = hdrs.getJSONObject(i);
                    if (hdr == null) {
                        logger.warn(TAG + " T_ZORDER_HDR 第 " + i + " 条为空，跳过");
                        continue;
                    }
                    String orderNumber = trimToNull(hdr.getString("ORDERNUMBER"));
                    if (orderNumber == null) {
                        orderNumber = trimToNull(container.getString("E_DOCNUMBER"));
                    }
                    if (orderNumber == null) {
                        logger.error(TAG + " 第 " + i + " 条报价单缺少报价单号(ORDERNUMBER/E_DOCNUMBER)，跳过");
                        continue;
                    }
                    Doc doc = new Doc();
                    doc.hdr = hdr;
                    doc.orderNumber = orderNumber;
                    doc.actionCode = cAction;
                    String bukrs = trimToNull(hdr.getString("E_BUKRS"));
                    doc.bukrs = bukrs != null ? bukrs : rootBukrs;
                    JSONObject r = hdr.getJSONObject("E_RES_ORDERS_LINE");
                    doc.returnMsg = r != null ? trimToNull(r.getString("RETURNMESSAGE")) : rootReturnMsg;
                    JSONObject qon = getSingleObject(hdr, "T_ZQUOTATION_ORDER_NUMBER");
                    doc.sapQuotationOrderNo = qon != null ? trimToNull(qon.getString("ORDER_NUMBER"))
                            : (batchMode ? null : rootSapQuotationOrderNo);
                    // 明细：优先取 HDR item 内联的 T_ZORDER_DTL；否则取容器级 T_ZORDER_DTL
                    //      （新批量格式的 T_ZORDER_DTL 就在容器级，单条历史报文也在根级）
                    JSONArray dtl = getItems(hdr, "T_ZORDER_DTL");
                    if (dtl == null) {
                        if (batchMode) {
                            logger.warn(TAG + " 批量模式第 " + i + " 条报价单无内联 T_ZORDER_DTL，明细视为空: quoteNumber=" + orderNumber);
                        } else {
                            dtl = rootDtl;
                        }
                    }
                    doc.dtl = dtl;
                    docs.add(doc);
                }
            }
            if (docs.isEmpty()) {
                logger.warn(TAG + " 无有效报价单，忽略");
                return HttpUtil.success(null).toJSONString();
            }

            // ====== D=删除（当前仅记日志，不做实际删除）======
            // 新批量格式下每条单据可有自己的 E_ACTCODE，故按单据分流
            List<Doc> deleteDocs = new ArrayList<>();
            List<Doc> upsertDocs = new ArrayList<>();
            for (Doc doc : docs) {
                if ("D".equals(doc.actionCode)) {
                    deleteDocs.add(doc);
                } else {
                    upsertDocs.add(doc);
                }
            }
            for (Doc doc : deleteDocs) {
                logger.info(TAG + " 删除操作（当前未执行删除）: quoteNumber=" + doc.orderNumber);
            }
            if (upsertDocs.isEmpty()) {
                return HttpUtil.success(null).toJSONString();
            }
            docs = upsertDocs;

            // ====== 预收集业务键 ======
            Set<String> quoteNumbers = new HashSet<>();
            Set<String> kunnrs = new HashSet<>();
            Set<String> bukrsSet = new HashSet<>();
            Set<String> sapOrderNos = new HashSet<>();
            Set<String> matNos = new HashSet<>();
            for (Doc doc : docs) {
                quoteNumbers.add(doc.orderNumber);
                if (doc.bukrs != null) {
                    bukrsSet.add(doc.bukrs);
                }
                if (doc.sapQuotationOrderNo != null) {
                    sapOrderNos.add(doc.sapQuotationOrderNo);
                }
                String purchaser = trimToNull(doc.hdr.getString("PURCHASERID"));
                if (purchaser == null) {
                    purchaser = trimToNull(doc.hdr.getString("CUSTOMERID"));
                }
                if (purchaser != null) {
                    kunnrs.add(Long.parseLong(purchaser)+"");
                }
                if (doc.dtl != null) {
                    for (int i = 0; i < doc.dtl.size(); i++) {
                        JSONObject item = doc.dtl.getJSONObject(i);
                        if (item == null) {
                            continue;
                        }
                        String matNo = trimToNull(item.getString("CATALOGNUMBER"));
                        if (matNo != null) {
                            matNos.add(matNo);
                        }
                    }
                }
            }

            // ====== 一次 IN 批量预查询（循环内零反查 SQL）======
            Map<String, JSONObject> quoteMap = queryQuotesByNumbers(quoteNumbers);
            Map<String, Long> accountMap = queryIdsByFieldIn("account", "kunnr__c", kunnrs);
            Map<String, Long> saleOrgMap = queryIdsByFieldIn("saleorg__c", "companyCode__c", bukrsSet);
            Map<String, Long> orderMap = queryIdsByFieldIn("order__c", "orderNumberAutoGenerate__c", sapOrderNos);
            Map<String, Long> productMap = queryIdsByFieldIn("product", "productCode__c", matNos);

            // 报价单状态选项集：整请求只取一次
            Map<String, Map<String, Integer>> quoteStatusMap = QueryUtil.getPicklistDescriptionApiKeyToValue("quote__c");
            Map<String, Map<Integer, String>> quoteLabelMap = QueryUtil.getPicklistDescriptionValueToLabel("quote__c");
            Map<String, Integer> statusOpts  = quoteStatusMap.get("quoteStatus__c");
            Map<Integer, String> statusLabels =quoteLabelMap.get("quoteStatus__c");

            // ====== 逐单构建报价单（不写库），失败只记 log ======
            List<MessageCenter__c> notifications = new ArrayList<>();
            List<PendingQuote> toInsertQuote = new ArrayList<>();
            List<PendingQuote> toUpdateQuote = new ArrayList<>();
            int failCount = 0;
            for (Doc doc : docs) {
                try {
                    PendingQuote pq = buildQuote(doc, quoteMap.get(doc.orderNumber), accountMap, saleOrgMap,
                            orderMap, statusOpts, statusLabels);
                    if (pq.q.getId() != null) {
                        toUpdateQuote.add(pq);
                    } else {
                        toInsertQuote.add(pq);
                    }
                } catch (Exception e) {
                    failCount++;
                    logger.error(TAG + " 报价单构建失败 quoteNumber=" + doc.orderNumber + " err=" + e.getMessage(), e);
                }
            }

            // ====== 批量写入报价单（先 insert 后 update，分批 200），回填 id ======
            failCount += writeQuotes(toInsertQuote, true, notifications);
            failCount += writeQuotes(toUpdateQuote, false, notifications);

            // ====== 特价申请单回写：报价单落库拿到 id 后，把报价单挂到特价申请单上 ======
            writeBackSpecialOrders(docs);

            // ====== 已有明细：按 quoteId 一次 IN 预查询（新报价单无已有明细）======
            Set<Long> quoteIds = new HashSet<>();
            for (Doc doc : docs) {
                if (doc.quoteId != null) {
                    quoteIds.add(doc.quoteId);
                }
            }
            Map<Long, Map<String, ExistingDetail>> existingByQuote = queryExistingDetailsByQuoteIds(quoteIds);

            // ====== 逐单逐行构建明细（不写库），失败只记 log ======
            List<PendingDetail> toInsertDetail = new ArrayList<>();
            List<PendingDetail> toUpdateDetail = new ArrayList<>();
            Set<String> pendingPosKeys = new HashSet<>();
            for (Doc doc : docs) {
                if (doc.quoteId == null || doc.dtl == null) {
                    continue;
                }
                Map<String, ExistingDetail> existingByPos = existingByQuote.get(doc.quoteId);
                if (existingByPos == null) {
                    existingByPos = new HashMap<>();
                    existingByQuote.put(doc.quoteId, existingByPos);
                }
                logger.info(TAG + " 报价单已有明细: quoteNumber=" + doc.orderNumber + ", quoteId=" + doc.quoteId
                        + ", 已有行数=" + existingByPos.size() + ", SAP报文行数=" + doc.dtl.size());
                for (int i = 0; i < doc.dtl.size(); i++) {
                    JSONObject item = doc.dtl.getJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    try {
                        PendingDetail pd = buildDetail(doc, item, existingByPos, productMap);
                        if (pd.d.getId() != null) {
                            toUpdateDetail.add(pd);
                        } else {
                            // 同批重复行号（归一化后）只保留第一条，避免重复插入
                            String dedupKey = doc.quoteId + "#" + pd.posKey;
                            if (pd.posKey != null && !pendingPosKeys.add(dedupKey)) {
                                logger.warn(TAG + " 同批重复行号，跳过: quoteNumber=" + doc.orderNumber + ", 行号=" + pd.posKey);
                                continue;
                            }
                            toInsertDetail.add(pd);
                        }
                    } catch (Exception e) {
                        logger.error(TAG + " 明细构建失败 quoteNumber=" + doc.orderNumber + " 行号="
                                + item.getString("POSICION") + " err=" + e.getMessage(), e);
                    }
                }
            }

            // ====== 批量写入明细（先 insert 后 update，分批 200），回填 id 防重复 ======
            failCount += writeDetails(toInsertDetail, true, existingByQuote);
            failCount += writeDetails(toUpdateDetail, false, existingByQuote);

            batchInsertNotifications(notifications);

            logger.info(TAG + " 推送完成: total=" + docs.size() + " success=" + (docs.size() - failCount)
                    + " fail=" + failCount);
            return HttpUtil.success(null).toJSONString();

        } catch (Exception e) {
            logger.error(TAG + " 推送报价单处理异常", e);
            return HttpUtil.success(null).toJSONString();
        }
    }

    // ===================== 报价单构建 / 批量写入 =====================

    /**
     * 构建单条报价单（不写库）。已存在（quoteNumberAutoGenerate__c 命中）→ setId 走更新；否则新建。
     * 报价单状态变更只在此记录新旧值，通知在批量写入成功后统一发。
     */
    private PendingQuote buildQuote(Doc doc, JSONObject existing, Map<String, Long> accountMap,
                                    Map<String, Long> saleOrgMap, Map<String, Long> orderMap,
                                    Map<String, Integer> statusOpts, Map<Integer, String> statusLabels) {
        JSONObject hdr = doc.hdr;
        boolean isUpdate = existing != null;

        // 验证人：USERNAME → creatorOne__c（文本，存 SAP 登录号）
        String verifier = trimToNull(hdr.getString("USERNAME"));
        // 创建人：先用通用选项集(cjr__c)按 apiKey 匹配 → 命中取 label（如 NEW_MYSE_SYS → MYSE），
        //        未命中则原样填 SAP 传的值（老板 2026-10-22 口径）
        String creator = GlobalPickHelper.matchGlobalPick(GlobalPickHelper.PICK_CREATOR, verifier);

        Quote__c q = new Quote__c();
        if (isUpdate) {
            q.setId(existing.getLong("id"));
            doc.quoteId = existing.getLong("id");
        } else {
            q.setEntityType(QUOTE_BUSI_TYPE);

            q.setCreatorOne__c(creator);
        }

        if (existing != null && StringUtils.isBlank(existing.getString("creatorOne__c"))) {
            q.setCreatorOne__c(creator);
        }

        q.setQuoteNumberAutoGenerate__c(doc.orderNumber);

        //创建日期
        Long creationDate = parseDateTime(hdr.getString("CREATIONTIME"));
        if (creationDate != null) {
            q.setCreatedAt__c(creationDate);
        }

        // 账户：按 SAP 客户号 kunnr__c 反查 account.id
        String purchaser = trimToNull(hdr.getString("PURCHASERID"));
        if (purchaser != null) {
            Long accountId = accountMap.get(Long.parseLong(purchaser)+"");
            if (accountId != null) {
                q.setPurchaserId__c(accountId);
            } else {
                logger.warn(TAG + " 未找到账户 account: kunnr__c=" + purchaser);
            }
        }

        // 销售组织：SAP E_BUKRS（根节点）→ 按 companyCode__c 反查 saleorg__c.id
        if (doc.bukrs != null) {
            Long saleOrgId = saleOrgMap.get(doc.bukrs);
            if (saleOrgId != null) {
                q.setSalesOrganization__c(saleOrgId);
            } else {
                logger.warn(TAG + " 未找到销售组织 saleorg__c: companyCode__c=" + doc.bukrs);
            }
        }

        String cuPo = trimToNull(hdr.getString("PURCHASEORDER"));
        String pmsFlag=trimToNull(hdr.getString("BSTZD"));
        if (pmsFlag != null) {
            q.setPmsFlag__c(pmsFlag);
        }
        if (cuPo != null) {
            if("ESPA".equals(pmsFlag)) {
                q.setPmsId__c(cuPo);
                // 特价申请单：记录标志 + 特价申请单号，落库后回写特价申请单关联
                doc.pmsFlag = pmsFlag;
                doc.pmsId = cuPo;
            } else{
                q.setPurchaseOrder__c(cuPo);
            }
        }

        // 报价单状态：SAP ORDERSTATUS（字母 apiKey，如 O/C/P）→ quoteStatus__c 选项码
        // 与库里现有状态不同时写回，并在保存成功后发消息中心通知
        PendingQuote pq = new PendingQuote();
        pq.q = q;
        pq.doc = doc;
        String orderStatus = trimToNull(hdr.getString("ORDERSTATUS"));
        if (orderStatus != null) {
            // 库里旧状态：XOQL 可能返回整数 / 中文 label 数组 / apiKey，统一换算成选项 value
            Integer oldStatus = extractStatusValue(existing == null ? null : existing.get("quoteStatus__c"), statusOpts, statusLabels);
            // 2026-10-21 老板口径：更新场景下，若销售易里该报价单已是【全部拒单】，
            //   则不再接收 SAP 下发的状态（保留原状态，也不发状态变更通知）。
            //   仅对更新生效；新建时库里没有旧状态，不受此限制。
            if (isUpdate && isRejectedStatus(oldStatus, statusOpts, statusLabels)) {
                logger.info(TAG + " 该报价单已是【全部拒单】，忽略 SAP 下发的状态: orderNumber="
                        + doc.orderNumber + ", 库里状态=" + (statusLabels == null ? oldStatus : statusLabels.get(oldStatus))
                        + ", SAP ORDERSTATUS=" + orderStatus);
            } else {
                Integer newStatus = statusOpts == null ? null : statusOpts.get(orderStatus);
                if (newStatus == null) {
                    logger.warn(TAG + " 未识别报价单状态 ORDERSTATUS=" + orderStatus + "（quoteStatus__c 不赋值、不发通知）");
                } else {
                    q.setQuoteStatus__c(newStatus);
                    pq.oldStatus = oldStatus;
                    pq.newStatus = newStatus;
                    if (statusLabels != null) {
                        pq.newStatusLabel = statusLabels.get(newStatus);
                        pq.oldStatusLabel = statusLabels.get(oldStatus);
                    }
                }
            }
        }
        Double total = parseDouble(hdr.getString("TOTALPRICE"));
        if (total != null) {
            q.setTotalPrice__c(total);
        }
        Double totalWith = parseDouble(hdr.getString("TOTALAMOUNTWITHSURCHARGE"));
        if (totalWith != null) {
            q.setTotalAmount__c(totalWith);
        }
        String ship = buildAddress(hdr);
        if (ship != null && !ship.isEmpty()) {
            q.setShipToAddress__c(ship);
        }

        Long statusDate = parseDate(hdr.getString("QOTDATE"));
        if (statusDate != null) {
            q.setStatusDate__c(statusDate);
            q.setTransferOrderDate__c(statusDate);
        }
        // 项目名称：PROJECTNAME → projectName__c（文本，直接写）
        String projectName = trimToNull(hdr.getString("PROJECTNAME"));
        if (projectName != null) {
            q.setProjectName__c(projectName);
        }

        if (doc.returnMsg != null) {
            q.setSyncDescription__c(doc.returnMsg);
        }
        // 通知接收方：账户取本次落库的 purchaserId__c（没有则取库里旧值），负责人取库里已有负责人
        pq.notifyAccountId = q.getPurchaserId__c() != null ? q.getPurchaserId__c()
                : (existing == null ? null : existing.getLong("purchaserId__c"));
        pq.notifyOwnerId = (existing == null ? null : existing.getLong("ownerId"));
        return pq;
    }

    /**
     * 批量写入报价单（分批 200），按 operateResults 顺序回填 id；写入成功后再发状态变更通知。
     *
     * @return 失败条数
     */
    private int writeQuotes(List<PendingQuote> pendings, boolean isInsert, List<MessageCenter__c> notifications) {
        int fail = 0;
        if (pendings.isEmpty()) {
            return fail;
        }
        String opName = isInsert ? "insert" : "update";
        for (int from = 0; from < pendings.size(); from += WRITE_BATCH_SIZE) {
            List<PendingQuote> part = pendings.subList(from, Math.min(from + WRITE_BATCH_SIZE, pendings.size()));
            List<Quote__c> objs = new ArrayList<>();
            for (PendingQuote p : part) {
                objs.add(p.q);
            }
            BatchOperateResult br;
            try {
                br = isInsert
                        ? XObjectService.instance().insert(objs, true)
                        : XObjectService.instance().update(objs);
            } catch (Exception e) {
                logger.error(TAG + " 批量" + opName + "报价单异常 条数=" + part.size() + " err=" + e.getMessage(), e);
                fail += part.size();
                continue;
            }
            boolean batchOk = br != null && Boolean.TRUE.equals(br.getSuccess());
            List<OperateResult> ops = br == null ? null : br.getOperateResults();
            int partFail = 0;
            for (int k = 0; k < part.size(); k++) {
                PendingQuote p = part.get(k);
                OperateResult r = (ops != null && k < ops.size()) ? ops.get(k) : null;
                if (batchOk && r != null && Boolean.TRUE.equals(r.getSuccess())) {
                    Long id = isInsert ? r.getDataId() : p.q.getId();
                    if (id != null) {
                        p.q.setId(id);
                        p.doc.quoteId = id;
                        // 报价单状态变更：仅在已存在的报价单状态发生跳变时发通知
                        if (!isInsert && p.oldStatus != null && p.newStatus != null && !p.oldStatus.equals(p.newStatus)) {
                            String oldLabel = p.oldStatusLabel != null ? p.oldStatusLabel : String.valueOf(p.oldStatus);
                            String newLabel = p.newStatusLabel != null ? p.newStatusLabel : String.valueOf(p.newStatus);
                            notifications.add(newNotification("【通知】报价单状态发生变更",
                                    "【通知】报价单:" + p.doc.orderNumber + ",状态从" + oldLabel + "变更为" + newLabel,
                                    p.notifyAccountId, p.notifyOwnerId, p.doc.orderNumber, "quote__c",
                                    MSG_STATUS_CHANGED));
                        }
                    } else {
                        partFail++;
                        logger.error(TAG + " 报价单" + opName + "后未获取到 id quoteNumber=" + p.doc.orderNumber);
                    }
                } else {
                    partFail++;
                    String err = !batchOk
                            ? (br == null ? "批量" + opName + "返回 null" : br.getErrorMessage())
                            : (r == null ? "单条结果缺失" : r.getErrorMessage());
                    logger.error(TAG + " 报价单" + opName + "失败 quoteNumber=" + p.doc.orderNumber + " err=" + err);
                }
            }
            fail += partFail;
            logger.info(TAG + " 批量" + opName + "报价单 成功=" + (part.size() - partFail) + "/" + part.size());
        }
        return fail;
    }

    // ===================== 特价申请单回写 =====================

    /**
     * 回写特价申请单：报价单落库拿到 id 后，对「特价申请单（pmsFlag=ESPA）」来的报价单，
     * 用 pmsId__c（特价申请单号）反查 specialOrderApplication__c（按 specialOfferOrderNo__c 匹配），
     * 把报价单 id 回写到 relevantQuoteNumber__c（lookup quote__c）。
     *
     * <p>找不到特价申请单只记 warn 不阻断主流程（回写是附加动作，报价单本身已正常落库）。</p>
     */
    private void writeBackSpecialOrders(List<Doc> docs) {
        for (Doc doc : docs) {
            if (!"ESPA".equals(doc.pmsFlag) || doc.pmsId == null || doc.pmsId.trim().isEmpty()) {
                continue;
            }
            if (doc.quoteId == null) {
                logger.warn(TAG + " 特价报价单未落库，跳过特价申请单回写 quoteNumber=" + doc.orderNumber
                        + ", pmsId=" + doc.pmsId);
                continue;
            }
            try {
                String sql = "SELECT id FROM specialOrderApplication__c WHERE specialOfferOrderNo__c = '"
                        + doc.pmsId.trim() + "' LIMIT 1";
                QueryResult<JSONObject> res = XoqlService.instance().query(sql);
                if (res == null || res.getRecords() == null || res.getRecords().isEmpty()) {
                    logger.warn(TAG + " 未找到特价申请单 specialOrderApplication__c: specialOfferOrderNo__c="
                            + doc.pmsId + "（报价单 quoteNumber=" + doc.orderNumber + " 不挂接）");
                    continue;
                }
                Long spId = res.getRecords().get(0).getLong("id");
                if (spId == null) {
                    continue;
                }
                SpecialOrderApplication__c sp = new SpecialOrderApplication__c();
                sp.setId(spId);
                sp.setRelevantQuoteNumber__c(doc.quoteId);
                OperateResult r = XObjectService.instance().update(sp, true);
                logger.info(TAG + " 特价申请单回写报价单: spId=" + spId + ", relevantQuoteNumber__c=" + doc.quoteId
                        + ", quoteNumber=" + doc.orderNumber
                        + ", success=" + (r != null && Boolean.TRUE.equals(r.getSuccess())));
            } catch (Exception e) {
                logger.error(TAG + " 特价申请单回写异常 quoteNumber=" + doc.orderNumber + " pmsId=" + doc.pmsId, e);
            }
        }
    }

    // ===================== 明细构建 / 批量写入 =====================

    /**
     * 构建单条明细（不写库）。已存在（行号归一化命中）→ setId 走更新；否则新建。
     * 期望发货日期变更：库里原日期非空且与 SAP REQUIREDELIVERYDATE 不同 → 备份旧值到
     * requestedDeliveryDateOld__c 并累计 doc.changedCount。
     */
    private PendingDetail buildDetail(Doc doc, JSONObject item, Map<String, ExistingDetail> existingByPos,
                                      Map<String, Long> productMap) {
        // 行号(kunnr) → 按 posicion__c 定位已有明细：
        // SAP 侧行号常为 5 位补零形式（00010），CRM 侧可能存在未补零形式（10），
        // 匹配时统一去前导零，避免同一行被判成新行重复创建。
        String pos = trimToNull(item.getString("POSICION"));
        String posKey = normalizePos(pos);
        ExistingDetail existing = posKey != null ? existingByPos.get(posKey) : null;
        Long existingId = existing != null ? existing.id : null;
        boolean isUpdate = existingId != null;

        PendingDetail pd = new PendingDetail();
        pd.doc = doc;
        pd.quoteId = doc.quoteId;
        pd.posKey = posKey;

        QuoteDetail__c d = new QuoteDetail__c();
        pd.d = d;
        if (isUpdate) {
            d.setId(existingId);
        } else {
            d.setEntityType(QUOTE_DETAIL_BUSI_TYPE);
            d.setQuoteId__c(doc.quoteId);
        }
        if (pos != null) {
            d.setPosicion__c(pos);
        }
        // 产品：订货号(CATALOGNUMBER) → product.productCode__c 反查 id
        String matNo = trimToNull(item.getString("CATALOGNUMBER"));
        if (matNo != null) {
            Long productId = productMap.get(matNo);
            if (productId != null) {
                d.setCatalogNumber__c(productId);
            } else {
                logger.warn(TAG + " 未找到产品 product: productCode__c=" + matNo);
            }
        }

        // C 决策：以下 SAP 字段 CRM 无对应目标，仅记日志不落库
//        logIfPresent(item, "LINEITEMSTATUS", "行项目状态");
//        logIfPresent(item, "YEARLYDISCOUNT", "年奖抵扣金额");
//        logIfPresent(item, "MONTHLYDISCOUNT", "月度抵扣金额");

        //订单直降
        Double QUARTYBONUSDIS = parseDouble(item.getString("QUARTYBONUSDIS"));
        if (QUARTYBONUSDIS != null) {
            d.setDisCountk03b__c(QUARTYBONUSDIS);
        }
        Double qty = parseDouble(item.getString("QUANTITY"));
        if (qty != null) {
            d.setQuantity__c(qty.longValue());
        }
        // 列表价(含税)：PROFILEPRICE → unitnetPrice__c
        Double profilePrice = parseDouble(item.getString("PROFILEPRICE"));
        if (profilePrice != null) {
            d.setUnitnetPrice__c(profilePrice);
        }
        // 折扣后单价(含税)：UNITNETPRICE → profilePrice__c
        Double listPrice = parseDouble(item.getString("UNITNETPRICE"));
        if (listPrice != null) {
            d.setProfilePrice__c(listPrice);
        }
        Double ext = parseDouble(item.getString("EXTENDEDPROFILEPRICE"));
        if (ext != null) {
            d.setExtendedProfilePrice__c(ext);
        }

        String discPct = trimToNull(item.getString("DISCOUNTPERCENTAGE"));
        if (StringUtils.isNotBlank(discPct)) {
            if ("ESPA".equals(doc.pmsFlag)) {
                d.setDisCountPercentage__c(parseDouble(discPct));
            } else {
                d.setDisCountPercentage__c(parseDouble(discPct) / 100);
            }
        }
        String discK03c = trimToNull(item.getString("DISCOUNTK03C"));
        if (StringUtils.isNotBlank(discK03c)) {
            d.setDisCountk03c__c(parseDouble(discK03c));
        }
        // 销售单位：SAP 单位码 → CRM 选项码（itemType 3 整型）
        String salesUnit = trimToNull(item.getString("SALES_UNIT"));
        if (salesUnit != null) {
            Integer unitCode = mapSalesUnit(salesUnit);
            if (unitCode != null) {
                d.setSalesUnit__c(unitCode);
            } else {
                logger.warn(TAG + " 未识别销售单位 SALES_UNIT=" + salesUnit + "，跳过");
            }
        }
        // 期望交货日期：REQUIREDELIVERYDATE → requestedDeliveryDate__c（原误用 COMMITTEDDATE/EDATU）
        // 变更检测：仅当该行已存在且库里原日期非空、且与本次 SAP 日期不同时，
        // 把库里原日期备份到 requestedDeliveryDateOld__c 并通知；未变更则不动该字段。
        Long rdd = parseDate(item.getString("REQUIREDELIVERYDATE"));
        pd.rdd = rdd;
        if (rdd != null) {
            Long oldDate = existing != null ? existing.requestedDeliveryDate : null;
            if (oldDate != null && !oldDate.equals(rdd)) {
                d.setRequestedDeliveryDateOld__c(oldDate);
                pd.dateChanged = true;
                logger.info(TAG + " 明细行期望发货日期变更: quoteNumber=" + doc.orderNumber + ", 行号=" + pos
                        + ", " + formatDate(oldDate) + " → " + formatDate(rdd));
            }
            // 库里原日期为空（首次下发）时无"旧日期"可备份，不视为变更、不通知
            d.setRequestedDeliveryDate__c(rdd);
        }
        // 预计交货日期：PROMISEDATE → promiseDate__c
        Long pd2 = parseDate(item.getString("PROMISEDATE"));
        if (pd2 != null) {
            d.setPromiseDate__c(pd2);
        }
        // 产品标准交货期（工作日）：LEADTIME → leadTime__c（itemType 6 实数，原值写入）
        Double leadTime = parseDouble(item.getString("LEADTIME"));
        if (leadTime != null) {
            d.setLeadTime__c(leadTime);
        }
        String ship = buildAddress(item);
        if (ship != null && !ship.isEmpty()) {
            d.setShipToAddress__c(ship);
        }
        return pd;
    }

    /**
     * 批量写入明细（分批 200），按 operateResults 顺序回填 id；
     * insert 成功后回填 existingByQuote（防同批重复行号），并累计日期变更行数。
     *
     * @return 失败条数
     */
    private int writeDetails(List<PendingDetail> pendings, boolean isInsert,
                             Map<Long, Map<String, ExistingDetail>> existingByQuote) {
        int fail = 0;
        if (pendings.isEmpty()) {
            return fail;
        }
        String opName = isInsert ? "insert" : "update";
        for (int from = 0; from < pendings.size(); from += WRITE_BATCH_SIZE) {
            List<PendingDetail> part = pendings.subList(from, Math.min(from + WRITE_BATCH_SIZE, pendings.size()));
            List<QuoteDetail__c> objs = new ArrayList<>();
            for (PendingDetail p : part) {
                objs.add(p.d);
            }
            BatchOperateResult br;
            try {
                br = isInsert
                        ? XObjectService.instance().insert(objs, true)
                        : XObjectService.instance().update(objs);
            } catch (Exception e) {
                logger.error(TAG + " 批量" + opName + "明细异常 条数=" + part.size() + " err=" + e.getMessage(), e);
                fail += part.size();
                continue;
            }
            boolean batchOk = br != null && Boolean.TRUE.equals(br.getSuccess());
            List<OperateResult> ops = br == null ? null : br.getOperateResults();
            int ok = 0;
            for (int k = 0; k < part.size(); k++) {
                PendingDetail p = part.get(k);
                OperateResult r = (ops != null && k < ops.size()) ? ops.get(k) : null;
                if (batchOk && r != null && Boolean.TRUE.equals(r.getSuccess())) {
                    ok++;
                    if (isInsert && r.getDataId() != null && p.posKey != null) {
                        Map<String, ExistingDetail> byPos = existingByQuote.get(p.quoteId);
                        if (byPos == null) {
                            byPos = new HashMap<>();
                            existingByQuote.put(p.quoteId, byPos);
                        }
                        ExistingDetail ref = new ExistingDetail();
                        ref.id = r.getDataId();
                        ref.requestedDeliveryDate = p.rdd;
                        byPos.put(p.posKey, ref);
                    }
                    if (p.dateChanged && p.doc != null) {
                        // 期望发货日期变更：按报价单维度累计（整单写完后发一条通知）
                        p.doc.changedCount++;
                    }
                } else {
                    String err = !batchOk
                            ? (br == null ? "批量" + opName + "返回 null" : br.getErrorMessage())
                            : (r == null ? "单条结果缺失" : r.getErrorMessage());
                    logger.error(TAG + " 明细" + opName + "失败 quoteId=" + p.quoteId + " 行号=" + p.posKey + " err=" + err);
                }
            }
            fail += part.size() - ok;
            logger.info(TAG + " 批量" + opName + "明细 成功=" + ok + "/" + part.size());
        }
        return fail;
    }

    // ===================== 消息中心通知（批量写入）=====================

    // ===================== 预查询（一次 IN，分批 100）=====================

    /**
     * 按 quoteNumberAutoGenerate__c 批量预查询已有报价单
     * （含 quoteStatus__c / ownerId / purchaserId__c，供状态变更与日期变更通知使用）。
    /** 已有明细的引用信息：id + 库里当前的期望发货日期（用于变更检测）。 */
    private static class ExistingDetail {
        Long id;
        Long requestedDeliveryDate;
    }

    /**
     * 按 quoteId 批量预查询已有明细：quoteId → (行号归一化 → id + requestedDeliveryDate__c)。
     * 注：quoteDetail__c 在 API 上下文下有对象级数据权限隔离，走 RkhdHttpClient 内部 XOQL（系统鉴权）。
     */
    private Map<Long, Map<String, ExistingDetail>> queryExistingDetailsByQuoteIds(Set<Long> quoteIds) {
        Map<Long, Map<String, ExistingDetail>> map = new HashMap<>();
        if (quoteIds.isEmpty()) {
            return map;
        }
        StringBuilder inClause = new StringBuilder();
        for (Long id : quoteIds) {
            if (inClause.length() > 0) {
                inClause.append(",");
            }
            inClause.append(id);
        }
        String xoql = "SELECT id, quoteId__c, posicion__c, requestedDeliveryDate__c FROM quoteDetail__c"
                + " WHERE quoteId__c IN (" + inClause + ")";
        logger.info(TAG + " 内部XOQL: " + xoql);
        try {
            RkhdHttpData httpData = RkhdHttpData.newBuilder()
                    .callString("/rest/data/v2.0/query/xoql")
                    .callType("POST")
                    .formData("xoql", xoql)
                    .build();
            String respStr = RkhdHttpClient.instance().performRequest(httpData);
            if (respStr != null && !respStr.trim().isEmpty()) {
                JSONArray records = extractRecords(JSON.parseObject(respStr));
                if (records != null) {
                    for (int i = 0; i < records.size(); i++) {
                        JSONObject rec = records.getJSONObject(i);
                        if (rec == null) {
                            continue;
                        }
                        Object id = rec.get("id");
                        Long quoteId = rec.getLong("quoteId__c");
                        String pos = normalizePos(trimToNull(rec.getString("posicion__c")));
                        if (id == null || quoteId == null || pos == null) {
                            continue;
                        }
                        Map<String, ExistingDetail> byPos = map.get(quoteId);
                        if (byPos == null) {
                            byPos = new HashMap<>();
                            map.put(quoteId, byPos);
                        }
                        ExistingDetail ref = new ExistingDetail();
                        ref.id = Long.parseLong(id.toString());
                        ref.requestedDeliveryDate = extractTs(rec.get("requestedDeliveryDate__c"));
                        ExistingDetail prevRef = byPos.put(pos, ref);
                        if (prevRef != null && !prevRef.id.equals(ref.id)) {
                            logger.warn(TAG + " 报价单明细行号重复(归一化后相同): quoteId=" + quoteId
                                    + ", posKey=" + pos + ", 已有id=" + prevRef.id + ", 本次id=" + ref.id
                                    + "（保留先查到的 " + prevRef.id + "）");
                            byPos.put(pos, prevRef);
                        }
                    }
                }
            }
            logger.info(TAG + " 已有明细预查询: quoteId数=" + quoteIds.size() + " 命中报价单数=" + map.size());
        } catch (Exception e) {
            logger.warn(TAG + " 内部XOQL查询已有明细失败", e);
        }
        return map;
    }

    // ===================== 解析/工具方法 =====================

    /**
     * 从 XOQL 返回的单选项字段取选项 value：兼容 整数 / 数字串 / 中文 label（含数组形式 ["等待确认"]）/
     * apiKey（如 "O"）多种返回形态。
     */
    private Integer extractStatusValue(Object o, Map<String, Integer> apiKeyToValue,
                                       Map<Integer, String> valueToLabel) {
        if (o == null) {
            return null;
        }
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            return a.isEmpty() ? null : extractStatusValue(a.get(0), apiKeyToValue, valueToLabel);
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            Object v = j.get("value");
            if (v == null) {
                v = j.get("id");
            }
            return extractStatusValue(v, apiKeyToValue, valueToLabel);
        }
        String s = o.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (Exception ignore) {
        }
        // 中文 label 反查
        if (valueToLabel != null) {
            for (Map.Entry<Integer, String> e : valueToLabel.entrySet()) {
                if (s.equals(e.getValue())) {
                    return e.getKey();
                }
            }
        }
        // apiKey 反查（如 "O"/"C"/"P"）
        if (apiKeyToValue != null) {
            Integer v = apiKeyToValue.get(s);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /**
     * 判断报价单状态是否已是【全部拒单】。
     *
     * <p>用途（老板 2026-10-21 口径）：更新场景下，库里已是「全部拒单」就不再接收 SAP 下发的状态。</p>
     *
     * <p><b>为什么不用硬编码 value=5</b>：quoteStatus__c 的选项 apiKey/value 是
     * 数字字母混用（1/O/3/C/5/P/7），且 value 可能随选项集调整而变，
     * 故按 apiKey 常量 {@link #QUOTE_STATUS_REJECTED_API_KEY} 反查当前 value。</p>
     *
     * <p><b>双重判定</b>：先比 apiKey 反查出的 value，再用 label 兜底
     * （万一平台把 apiKey 改了、但 label 仍是「全部拒单」，仍能命中）。</p>
     *
     * @param oldStatus     库里现有状态（选项 value，已由 extractStatusValue 换算）
     * @param apiKeyToValue quoteStatus__c 的 apiKey → value
     * @param valueToLabel  quoteStatus__c 的 value → label
     * @return true = 已是全部拒单
     */
    private boolean isRejectedStatus(Integer oldStatus,
                                     Map<String, Integer> apiKeyToValue,
                                     Map<Integer, String> valueToLabel) {
        if (oldStatus == null) {
            return false;
        }
        if (apiKeyToValue != null) {
            Integer rejectedValue = apiKeyToValue.get(QUOTE_STATUS_REJECTED_API_KEY);
            if (rejectedValue != null && rejectedValue.equals(oldStatus)) {
                return true;
            }
        }
        // label 兜底
        if (valueToLabel != null && QUOTE_STATUS_REJECTED_LABEL.equals(valueToLabel.get(oldStatus))) {
            return true;
        }
        return false;
    }

    /**
     * 行号归一化：去前导零，使 SAP 的 "00010" 与 CRM 可能存在的 "10" 视为同一行。
     * 非纯数字或全零时不做处理（如 "0" → "0"）。
     */
    private String normalizePos(String p) {
        if (p == null) {
            return null;
        }
        String s = p.trim();
        if (s.isEmpty()) {
            return null;
        }
        String stripped = s.replaceFirst("^0+", "");
        return stripped.isEmpty() ? "0" : stripped;
    }

    /** SAP 单位码 → CRM 销售单位选项码；未知返回 null。 */
    private Integer mapSalesUnit(String sapUnit) {
        if (sapUnit == null) {
            return null;
        }
        return SAP_UNIT_TO_CODE.get(sapUnit.trim().toUpperCase(Locale.ROOT));
    }

}
