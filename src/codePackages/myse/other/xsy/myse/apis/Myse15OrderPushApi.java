package other.xsy.myse.apis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.data.model.MessageCenter__c;
import com.rkhd.platform.sdk.data.model.Order__c;
import com.rkhd.platform.sdk.data.model.OrderDetail__c;
import com.rkhd.platform.sdk.data.model.Quote__c;
import com.rkhd.platform.sdk.data.model.SpecialOrderApplication__c;
import com.rkhd.platform.sdk.exception.ApiEntityServiceException;
import com.rkhd.platform.sdk.exception.CustomConfigException;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.BatchOperateResult;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.CustomConfigService;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.http.RkhdHttpClient;
import com.rkhd.platform.sdk.http.RkhdHttpData;
import com.rkhd.platform.sdk.service.XoqlService;
import org.apache.commons.lang.StringUtils;
import other.xsy.myse.utils.GlobalPickHelper;
import other.xsy.myse.utils.HttpUtil;
import other.xsy.myse.utils.QueryUtil;
import java.util.*;

/**
 * MYSE-15 推送销售订单（SAP → mySE，被动接收）
 * <p>
 * 销售易 API 路径：/rest/data/v2.0/scripts/api/myse/order/push
 * </p>
 */
@RestApi(baseUrl = "/myse")
public class Myse15OrderPushApi extends SapPushBaseApi {

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
    private static final String TAG = "MYSE-15";

    /** 批量写入 / IN 预查询单批大小 */

    /** 发运地点主数据(shippingPoint__c)单次拉取上限（主数据小表，全量取回内存匹配） */
    private static final int SHIPPING_POINT_LIMIT = 5000;

    // ===================== SAP 业务标识字段（文本，原样存 SAP 值；2026-10-19 新增）=====================
    /** order__c.modifiable__c ← 头 MODIFIABLE（订单-是否可以修改日期） */
    private static final String MODIFIABLE_FIELD_ORDER = "modifiable__c";
    /** order__c.adress_Modifiable__c ← 头 ADRESS_MODIFIABLE（订单-是否可以修改地址） */
    private static final String ADRESS_MODIFIABLE_FIELD_ORDER = "adress_Modifiable__c";
    /** orderDetail__c.mtvfp__c ← 明细 mtvfp（订单明细-是否有库存） */
    private static final String MTVFP_FIELD_DETAIL = "mtvfp__c";
    /** orderDetail__c.modifiable__c ← 明细 MODIFIABLE（订单明细-是否可以修改日期） */
    private static final String MODIFIABLE_FIELD_DETAIL = "modifiable__c";
    /** orderDetail__c.adress_Modifiable__c ← 明细 ADRESS_MODIFIABLE（订单明细-是否可以修改地址） */
    private static final String ADRESS_MODIFIABLE_FIELD_DETAIL = "adress_Modifiable__c";

    /** 订单业务类型（entityType），取自租户元数据 busiTypeId。 */
    private static final Long ORDER_BUSI_TYPE ;
    /** 订单明细业务类型（entityType）。 */
    private static final Long ORDER_DETAIL_BUSI_TYPE ;
    /** 消息中心业务类型（entityType）。 */
    private static final Long MESSAGE_CENTER_BUSI_TYPE;
    /** 消息类型：2=订阅（订单行状态/日期变更订阅通知）。 */
    private static final int MSG_TYPE_SUBSCRIBE = 2;
    /** 消息已读标志：2=否（未读）。 */

    static {
        try {
            // 获取对象实例
            CustomConfigService instance = CustomConfigService.instance();
            // 获取配置集,返回此配置集中的所有配置项
            Map<String, String> configSet = instance.getConfigSet("entityType");
            ORDER_BUSI_TYPE=Long.parseLong(configSet.get("ORDER_BUSI_TYPE"));
            ORDER_DETAIL_BUSI_TYPE=Long.parseLong(configSet.get("ORDER_DETAIL_BUSI_TYPE"));
            MESSAGE_CENTER_BUSI_TYPE=Long.parseLong(configSet.get("MessageCenter"));
        } catch (CustomConfigException e) {
            throw new RuntimeException(e);
        }
    }

    /** SAP 信用状态原始值（与 CRM 选项 label 一致）→ 选项码。 */
    private static final Map<String, Integer> SAP_CREDIT_STATUS_TO_CODE;
    static {
        Map<String, Integer> m = new HashMap<>();
        m.put("OK", 1);
        m.put("OKFORCASH", 2);
        m.put("OVERNONDP", 3);
        m.put("OVERDP", 4);
        m.put("CHECKING", 5);
        SAP_CREDIT_STATUS_TO_CODE = Collections.unmodifiableMap(m);
    }

    /** 单条待处理订单（头 + 明细 + 控制字段 + 处理结果占位）。 */
    private static class Doc {
        JSONObject hdr;
        JSONArray dtl;
        String orderNumber;
        String bukrs;
        String returnMsg;
        Long orderId;
        /** 售达方账户 id（PURCHASERID → account.kunnr__c 反查），供明细关联发货地址复用。 */
        Long accountId;
        /** 源报价单 id（VGBEL → quote__c），订单写入拿到 id 后才回写 quote.relevantOrder__c。 */
        Long sourceQuoteId;
        /**
         * 源报价单<b>已有</b>的验证人（quote__c.verifier__c）。
         * <p>null/空 = 报价单还没有验证人 → 回写时用本次报文的 USERNAME 填充（原有行为）。</p>
         * <p>有值 → 报价单的验证人<b>保持不动</b>，并把订单的创建人改成它（老板 2026-10-23 口径）。</p>
         */
        String sourceQuoteVerifier;
        /** 验证人（USERNAME），订单写入后回写 quote.verifier__c 用。 */
        String verifierName;
        /**
         * 特价标志（SAP BSTZD）。= "ESPA" 表示特价申请单，此时需把订单挂到特价申请单上。
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

    /** 待写入的订单（批量写入后回填 id）。 */
    private static class PendingOrder {
        Order__c o;
        Doc doc;
    }

    /** 待写入的订单明细（批量写入后回填 id，防止同行号重复插入）。 */
    private static class PendingDetail {
        OrderDetail__c d;
        Doc doc;
        Long orderId;
        Integer posKey;
        Long rdd;
        Long pd;
    }

    @RestMapping(value = "/order/push", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body)  {
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
            if (outputItems != null && !outputItems.isEmpty()) {
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

            // ====== 拆分成单条订单（每个容器独立解析控制字段与明细）======
            List<Doc> docs = new ArrayList<>();
            for (JSONObject container : containers) {
                // 控制字段：容器级（老格式即根级，新格式为 T_OUTPUT.item 元素级）
                String actionCode = trimToNull(asString(container, "E_ACTCODE"));
                if (actionCode == null) {
                    actionCode = "A";
                }
                actionCode = actionCode.toUpperCase(Locale.ROOT);
                if (!"A".equals(actionCode) && !"C".equals(actionCode) && !"U".equals(actionCode)
                        && !"D".equals(actionCode)) {
                    logger.warn(TAG + " 未知操作码 E_ACTCODE=" + actionCode + "，按 upsert 处理");
                }

                // T_ZORDER_HDR.item 兼容 单对象（历史报文）/ 数组（批量推送）
                JSONArray hdrs = getItems(container, "T_ZORDER_HDR");
                if (hdrs == null || hdrs.isEmpty()) {
                    logger.warn(TAG + " T_ZORDER_HDR 为空，忽略");
                    continue;
                }
                boolean batchMode = hdrs.size() > 1;
                if (!batchMode) {
                    logger.info(TAG + " 入参(单条): " + container.toJSONString());
                } else {
                    logger.info(TAG + " 入参(批量): 条数=" + hdrs.size());
                }

                // 容器级共用控制字段（单条场景）
                JSONArray rootDtl = getItems(container, "T_ZORDER_DTL");
                String rootBukrs = trimToNull(asString(container, "E_BUKRS"));
                JSONObject resOrders = container.getJSONObject("E_RES_ORDERS_LINE");
                String rootReturnMsg = resOrders != null ? trimToNull(asString(resOrders, "RETURNMESSAGE")) : null;

                for (int i = 0; i < hdrs.size(); i++) {
                    JSONObject hdr = hdrs.getJSONObject(i);
                    if (hdr == null) {
                        logger.warn(TAG + " T_ZORDER_HDR 第 " + i + " 条为空，跳过");
                        continue;
                    }
                    String orderNumber = trimToNull(asString(hdr, "ORDERNUMBER"));
                    if (orderNumber == null) {
                        orderNumber = trimToNull(asString(container, "E_DOCNUMBER"));
                    }
                    if (orderNumber == null) {
                        logger.error(TAG + " 第 " + i + " 条订单缺少订单号(ORDERNUMBER/E_DOCNUMBER)，跳过");
                        continue;
                    }
                    Doc doc = new Doc();
                    doc.hdr = hdr;
                    doc.orderNumber = orderNumber;
                    doc.actionCode = actionCode;
                    String bukrs = trimToNull(asString(hdr, "E_BUKRS"));
                    doc.bukrs = bukrs != null ? bukrs : rootBukrs;
                    JSONObject r = hdr.getJSONObject("E_RES_ORDERS_LINE");
                    doc.returnMsg = r != null ? trimToNull(asString(r, "RETURNMESSAGE")) : rootReturnMsg;
                    // 明细：优先取 HDR item 内联的 T_ZORDER_DTL；否则取容器级 T_ZORDER_DTL
                    //      （新批量格式的 T_ZORDER_DTL 就在容器级）
                    JSONArray dtl = getItems(hdr, "T_ZORDER_DTL");
                    if (dtl == null) {
                        if (batchMode) {
                            logger.warn(TAG + " 批量模式第 " + i + " 条订单无内联 T_ZORDER_DTL，明细视为空: orderNumber=" + orderNumber);
                        } else {
                            dtl = rootDtl;
                        }
                    }
                    doc.dtl = dtl;
                    docs.add(doc);
                }
            }
            if (docs.isEmpty()) {
                logger.warn(TAG + " 无有效订单，忽略");
                return HttpUtil.success(null).toJSONString();
            }

            // ====== D=删除（逐单删，失败只记 log 不中断其余单据）======
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
                try {
                    deleteOrder(doc.orderNumber);
                    logger.info(TAG + " 删除订单成功: orderNumber=" + doc.orderNumber);
                } catch (Exception e) {
                    logger.error(TAG + " 删除订单失败 orderNumber=" + doc.orderNumber + " err=" + e.getMessage(), e);
                }
            }
            if (upsertDocs.isEmpty()) {
                return HttpUtil.success(null).toJSONString();
            }
            docs = upsertDocs;

            // ====== 预收集业务键 ======
            Set<String> orderNumbers = new HashSet<>();
            Set<String> kunnrs = new HashSet<>();
            Set<String> bukrsSet = new HashSet<>();
            Set<String> vgbels = new HashSet<>();
            Set<String> matNos = new HashSet<>();
            // 发货地址唯一键：送达方客户号 CUSTOMERID（头 + 行，行级缺失时回退头）
            Set<String> shipToCodes = new HashSet<>();
            for (Doc doc : docs) {
                orderNumbers.add(doc.orderNumber);
                if (doc.bukrs != null) {
                    bukrsSet.add(doc.bukrs);
                }
                String vgbel = trimToNull(asString(doc.hdr, "VGBEL"));
                if (vgbel != null) {
                    vgbels.add(vgbel);
                }
                String purchaser = trimToNull(asString(doc.hdr, "PURCHASERID"));

                if (purchaser != null) {
                    kunnrs.add(normalizeKunnr(purchaser));
                }
                String hdrCustomer = trimToNull(asString(doc.hdr, "CUSTOMERID"));
                if (hdrCustomer != null) {
                    shipToCodes.add(normalizeKunnr(hdrCustomer));
                    // 送达方客户号也入 kunnrs，反查 account（供 shipToCode__c 送达方账户赋值）
                    kunnrs.add(normalizeKunnr(hdrCustomer));
                }
                if (doc.dtl != null) {
                    for (int i = 0; i < doc.dtl.size(); i++) {
                        JSONObject item = doc.dtl.getJSONObject(i);
                        if (item == null) {
                            continue;
                        }
                        String matNo = trimToNull(asString(item, "CATALOGNUMBER"));
                        if (matNo != null) {
                            matNos.add(matNo);
                        }
                        String itemCustomer = trimToNull(itemCustomerCode(item));
                        if (itemCustomer != null) {
                            shipToCodes.add(normalizeKunnr(itemCustomer));
                        }
                    }
                }
            }

            // ====== 一次 IN 批量预查询（循环内零反查 SQL）======
            Map<String, JSONObject> orderMap = queryOrdersByNumbers(orderNumbers);
            // 源报价单：一次带出 ownerId，后续不再逐条 get
            Map<String, JSONObject> quoteMap = queryQuotesByNumbers(vgbels);
            Map<String, Long> accountMap = queryIdsByFieldIn("account", "kunnr__c", kunnrs);
            Map<String, Long> saleOrgMap = queryIdsByFieldIn("saleorg__c", "companyCode__c", bukrsSet);
            Map<String, Long> productMap = queryIdsByFieldIn("product", "productCode__c", matNos);
            // 发货地址：账户(PURCHASERID 反查的 account) + 送达方(CUSTOMERID) → shipToParty__c
            Map<String, Long> shipToMap = queryShipToPartyIds(accountMap, shipToCodes);

            // 选项集：整请求只取一次
            Map<String, Map<String, Integer>> picklistMap = QueryUtil.getPicklistDescriptionApiKeyToValue("order__c");
            Map<String, Map<String, Integer>> detailPicklistMap = QueryUtil.getPicklistDescriptionApiKeyToValue("orderDetail__c");
            Map<String, Map<Integer, String>> detailLabelMap = QueryUtil.getPicklistDescriptionValueToLabel("orderDetail__c");
            Map<String, Integer> orderStatusOpts = picklistMap.get("orderStatus__c");
            Map<String, Integer> lineStatusOpts = detailPicklistMap.get("lineItemStatus__c");
            Map<Integer, String> lineStatusLabels = detailLabelMap.get("lineItemStatus__c");

            // ====== 逐单构建订单（不写库），失败只记 log ======
            List<MessageCenter__c> notifications = new ArrayList<>();
            List<PendingOrder> toInsertOrder = new ArrayList<>();
            List<PendingOrder> toUpdateOrder = new ArrayList<>();
            int failCount = 0;
            for (Doc doc : docs) {
                try {
                    PendingOrder po = buildOrder(root, doc, orderMap.get(doc.orderNumber), accountMap,
                            saleOrgMap, quoteMap, orderStatusOpts, shipToMap);
                    if (po.o.getId() != null) {
                        toUpdateOrder.add(po);
                    } else {
                        toInsertOrder.add(po);
                    }
                } catch (Exception e) {
                    failCount++;
                    logger.error(TAG + " 订单构建失败 orderNumber=" + doc.orderNumber + " err=" + e.getMessage(), e);
                }
            }

            // ====== 批量写入订单（先 insert 后 update，分批 200），回填 id ======
            failCount += writeOrders(toInsertOrder, true);
            failCount += writeOrders(toUpdateOrder, false);

            // ====== 源报价单回写（verifier + 状态=4 + relevantOrder__c）======
            // 必须放在订单写入之后：新订单此时才有 id，relevantOrder__c 需要真实订单 id
            failCount += writeBackSourceQuotes(docs);

            // ====== 特价申请单回写：订单落库拿到 id 后，把订单挂到特价申请单上 ======
            writeBackSpecialOrders(docs);

            // ====== 已有明细：按 orderId 一次 IN 预查询（新订单无已有明细）======
            Set<Long> orderIds = new HashSet<>();
            for (Doc doc : docs) {
                if (doc.orderId != null) {
                    orderIds.add(doc.orderId);
                }
            }
            Map<Long, Map<Integer, ExistingDetail>> existingByOrder = queryExistingDetailsByOrderIds(orderIds);

            // ====== 发运地点：SAP 明细 VSTEL → 发运地点(shippingPoint__c) 名称匹配 → 描述 ======
            // 只预取一次，避免逐行查库
            Map<String, String> shippingPointDescMap = queryShippingPointDescMap(docs);

            // ====== 逐单逐行构建明细（不写库），失败只记 log ======
            List<PendingDetail> toInsertDetail = new ArrayList<>();
            List<PendingDetail> toUpdateDetail = new ArrayList<>();
            Set<String> pendingPosKeys = new HashSet<>();
            for (Doc doc : docs) {
                if (doc.orderId == null || doc.dtl == null) {
                    continue;
                }
                Map<Integer, ExistingDetail> existingByPos = existingByOrder.get(doc.orderId);
                if (existingByPos == null) {
                    existingByPos = new HashMap<>();
                    existingByOrder.put(doc.orderId, existingByPos);
                }
                JSONObject existingOrder = orderMap.get(doc.orderNumber);
                logger.info(TAG + " 订单已有明细: orderNumber=" + doc.orderNumber + ", orderId=" + doc.orderId
                        + ", 已有行数=" + existingByPos.size() + ", SAP报文行数=" + doc.dtl.size());
                for (int i = 0; i < doc.dtl.size(); i++) {
                    JSONObject item = doc.dtl.getJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    try {
                        PendingDetail pd = buildDetail(doc, item, existingByPos, productMap, existingOrder,
                                lineStatusOpts, lineStatusLabels, notifications, shipToMap,
                                shippingPointDescMap);
                        if (pd.d.getId() != null) {
                            toUpdateDetail.add(pd);
                        } else {
                            // 同批重复行号只保留第一条，避免重复插入
                            String dedupKey = doc.orderId + "#" + pd.posKey;
                            if (pd.posKey != null && !pendingPosKeys.add(dedupKey)) {
                                logger.warn(TAG + " 同批重复行号，跳过: orderNumber=" + doc.orderNumber + ", 行号=" + pd.posKey);
                                continue;
                            }
                            toInsertDetail.add(pd);
                        }
                    } catch (Exception e) {
                        logger.error(TAG + " 明细构建失败 orderNumber=" + doc.orderNumber + " 行号="
                                + asString(item, "POSICION") + " err=" + e.getMessage(), e);
                    }
                }
            }

            // ====== 批量写入明细（先 insert 后 update，分批 200），回填 id 防重复 ======
            failCount += writeDetails(toInsertDetail, true, existingByOrder);
            failCount += writeDetails(toUpdateDetail, false, existingByOrder);

            // ====== 订阅通知批量写入（旁路，失败不阻断）======
            batchInsertNotifications(notifications);

            logger.info(TAG + " 推送完成: total=" + docs.size() + " success=" + (docs.size() - failCount)
                    + " fail=" + failCount);
            return HttpUtil.success(null).toJSONString();

        } catch (Exception e) {
            logger.error(TAG + " 推送销售订单处理异常", e);
            return HttpUtil.success(null).toJSONString();
        }
    }

    // ===================== 订单构建 / 批量写入 =====================

    /**
     * 构建单条订单（不写库）。已存在（orderNumberAutoGenerate__c 命中）→ setId 走更新；否则新建。
     * 源报价单（VGBEL）命中时：用预查到的 ownerId 作为订单负责人，并把 quoteId 暂存到
     * {@link Doc#sourceQuoteId}，等订单写入拿到 id 后再统一回写 quote（verifier / 状态=4 / relevantOrder__c）。
     */
    private PendingOrder buildOrder(JSONObject root, Doc doc, JSONObject existing, Map<String, Long> accountMap,
                                    Map<String, Long> saleOrgMap, Map<String, JSONObject> quoteMap,
                                    Map<String, Integer> orderStatusOpts,
                                    Map<String, Long> shipToMap) {
        JSONObject hdr = doc.hdr;
        boolean isUpdate = existing != null;
        Order__c o = new Order__c();

        // 验证人（USERNAME→userName__c 文本字段，直接存 SAP 登录号，如 SESA484200）
        String userName = trimToNull(asString(hdr, "USERNAME"));
        // 创建人：先用通用选项集(cjr__c)按 apiKey 匹配 → 命中取 label（如 NEW_MYSE_SYS → MYSE），
        //        未命中则原样填 SAP 传的值（老板 2026-10-22 口径）
        //        注意：只影响创建人字段，userName__c（验证人）仍存 SAP 原值
        String creator = GlobalPickHelper.matchGlobalPick(GlobalPickHelper.PICK_CREATOR, userName);

        if (isUpdate) {
            o.setId(existing.getLong("id"));
            doc.orderId = o.getId();
        } else {
            o.setEntityType(ORDER_BUSI_TYPE);
            o.setCreator__c(creator);
        }

        if (existing != null && StringUtils.isBlank(existing.getString("creator__c"))) {
            o.setCreator__c(creator);
        }

        o.setOrderNumberAutoGenerate__c(doc.orderNumber);

        if (userName != null) {
            o.setUserName__c(userName);

        }

        // 报价单（VGBEL=前置凭证号，即源报价单号）→ quotation__c（lookup quote__c，按 quoteNumberAutoGenerate__c 反查）
        String vgbel = trimToNull(asString(hdr, "VGBEL"));
        if (vgbel != null) {
            // 报价单号文本（qtText__c）：不管查不查得到报价单，都先记录报价单号（期初/反查不到时保留追溯），
            // 后续刷数据再回填关联。字段为老板新增，model.jar 无 setter，用 setAttribute 写入。
            o.setAttribute("qtText__c", vgbel);
            JSONObject quote = quoteMap == null ? null : quoteMap.get(vgbel);
            Long quoteId = quote == null ? null : quote.getLong("id");
            if (quoteId != null) {
                o.setQuotation__c(quoteId);
                // 负责人沿用源报价单负责人（预查已带出 ownerId，不再逐条 get）
                Long quoteOwnerId = quote.getLong("ownerId");
                if (quoteOwnerId != null) {
                    o.setOwnerId(quoteOwnerId);
                }
                // 源报价单 id 暂存到 doc：此时订单尚未落库、o.getId() 为空，
                // relevantOrder__c 要等订单写入拿到 id 后再回写（见 writeBackSourceQuotes）
                doc.sourceQuoteId = quoteId;
                doc.verifierName = userName;
                // 报价单已有的验证人：有值 → 报价单保持不动，订单创建人改用它（老板 2026-10-23 口径）
                String quoteVerifier = trimToNull(quote.getString("verifier__c"));
                if (quoteVerifier != null) {
                    doc.sourceQuoteVerifier = quoteVerifier;
                    // 创建人只在「新单」或「库里创建人为空」时写，与本接口既有规则一致
                    if (!isUpdate || StringUtils.isBlank(existing.getString("creator__c"))) {
                        o.setCreator__c(quoteVerifier);
                    }
                    logger.info(TAG + " 源报价单已有验证人，订单创建人沿用之: orderNumber=" + doc.orderNumber
                            + ", quoteId=" + quoteId + ", verifier=" + quoteVerifier);
                }
            } else {
                logger.info(TAG + " 未找到源报价单 quote__c: quoteNumberAutoGenerate__c=" + vgbel + "（VGBEL，仅不关联）");
            }
        }

        // 凭证编号（根 E_DOCNUMBER，文本字段直接存）
        String docNum = trimToNull(asString(root, "E_DOCNUMBER"));
        if (docNum == null) {
            docNum = trimToNull(asString(hdr, "E_DOCNUMBER"));
        }
        if (docNum != null) {
            o.setE_Docnumber__c(docNum);
        }

        // 销售组织（根 E_BUKRS，文本字段直接存 + 按 companyCode__c 反查 saleorg__c → saleOrg__c）
        if (doc.bukrs != null) {
            o.setE_Bukrs__c(doc.bukrs);
            Long saleOrgId = saleOrgMap.get(doc.bukrs);
            if (saleOrgId != null) {
                o.setSaleOrg__c(saleOrgId);
            } else {
                logger.warn(TAG + " 未找到销售组织 saleorg__c: companyCode__c=" + doc.bukrs + "（E_BUKRS），saleOrg__c 不赋值");
            }
        }

        // 账户：按 SAP 售达方客户号 PURCHASERID → account.kunnr__c 反查 account.id
        String purchaser = trimToNull(asString(hdr, "PURCHASERID"));
        if (purchaser == null) {
            purchaser = trimToNull(asString(hdr, "CUSTOMERID"));
        }
        Long accountId = null;
        if (purchaser != null) {
            accountId = accountMap.get(normalizeKunnr(purchaser));
            if (accountId != null) {
                o.setPurchaseId__c(accountId);
                doc.accountId = accountId;   // 明细关联发货地址复用
            } else {
                logger.warn(TAG + " 未找到账户 account: kunnr__c=" + purchaser);
            }
        }

        // 发货地址：送达方 CUSTOMERID + 售达方账户 → shipToParty__c(purchaseId__c + kunnr__c) → shipTo__c
        String shipToCode = trimToNull(asString(hdr, "CUSTOMERID"));
        if (shipToCode != null && accountId != null) {
            Long shipToId = shipToMap == null ? null : shipToMap.get(shipToKey(accountId, shipToCode));
            if (shipToId != null) {
                o.setShipTo__c(shipToId);
            } else {
                logger.warn(TAG + " 未找到发货地址 shipToParty__c: purchaseId__c=" + accountId
                        + " kunnr__c=" + shipToCode + "（CUSTOMERID），shipTo__c 不赋值");
            }
        }

        // 送达方账户：CUSTOMERID（送达方客户号）→ account.kunnr__c 反查 account.id → shipToCode__c（lookup account）
        if (shipToCode != null) {
            Long shipToAccountId = accountMap == null ? null : accountMap.get(normalizeKunnr(shipToCode));
            if (shipToAccountId != null) {
                o.setShipToCode__c(shipToAccountId);
            } else {
                logger.warn(TAG + " 未找到送达方账户 account: kunnr__c=" + shipToCode
                        + "（CUSTOMERID），shipToCode__c 不赋值");
            }
        }

        // 采购编号
        String po = trimToNull(asString(hdr, "PURCHASEORDER"));
        String customerPO=trimToNull(asString(hdr, "CUSTOMER_PO"));
        String pmsFlag = trimToNull(asString(hdr, "BSTZD"));
        if (pmsFlag!=null) {
            o.setPmsFlag__c(pmsFlag);
        }
        if (po != null) {
            if("ESPA".equals(pmsFlag)) { //是特价申请单
                o.setPmsId__c(po);
                o.setPurchaseOrder__c(customerPO);
                // 特价申请单：记录标志 + 特价申请单号，落库后回写特价申请单关联
                doc.pmsFlag = pmsFlag;
                doc.pmsId = po;
            }else{
                o.setPurchaseOrder__c(po);
            }
        }

        // 期望发货日期（REQUIREDELIVERYDATE → requireDeliverydate__c）
        Long rddHdr = parseDate(asString(hdr, "REQUIREDELIVERYDATE"));
        if (rddHdr != null) {
            o.setRequireDeliverydate__c(rddHdr);
        }

        // 信用状态（CREDITSTATUS→crediStatus__c 选项码；SAP 值=选项 label，如 OverNonDP→3）
        String credit = trimToNull(asString(hdr, "CREDITSTATUS"));
        if (credit != null) {
            Integer code = mapCreditStatus(credit);
            if (code != null) {
                o.setCrediStatus__c(code);
            } else {
                logger.warn(TAG + " 未识别信用状态 CREDITSTATUS=" + credit + "，跳过");
            }
        }

        // 订单状态（SAP 传 orderStatus__c 的 optionApiKey，如 O/I/F；运行时从 CRM 元数据换取 optionCode）
        String orderStatus = trimToNull(asString(hdr, "ORDERSTATUS"));
        if (orderStatus != null) {
            Integer code = orderStatusOpts == null ? null : orderStatusOpts.get(orderStatus);
            if (code != null) {
                o.setOrderStatus__c(code);
            } else {
                logger.warn(TAG + " 未识别订单状态 ORDERSTATUS=" + orderStatus + "，跳过");
            }
        }

        // 整单金额（含税）：TOTALPRICE → totalPrice__c（文本字段，存清洗后数值串）
        Double total = parseDouble(asString(hdr, "TOTALPRICE"));
        if (total != null) {
            o.setTotalPrice__c(String.valueOf(total));
        }
        // 含附加费整单金额（CRM 无对应字段，仅日志）
        Double totalWith = parseDouble(asString(hdr, "TOTALAMOUNTWITHSURCHARGE"));
        if (totalWith != null) {
            logger.info(TAG + " TOTALAMOUNTWITHSURCHARGE=" + totalWith + "（CRM 无对应字段，仅日志）");
        }
        //项目名称
        String projectName=asString(hdr, "PROJECTNAME");
        if (total != null) {
            o.setProjectName__c(projectName);
        }
        // 发货地址（SHIPTONAME/ADDRESSLINE1~5/CITY/STATEPROVREGION/POSTALCODE/COUNTRYCODE）
        String ship = buildAddress(hdr);
        if (ship != null) {
            o.setShipToAddress__c(ship);
        }

        // SAP 业务标识（文本字段，原样存 SAP 值）
        // MODIFIABLE（订单-是否可以修改日期）→ order__c.modifiable__c
        setIfPresent(o, asString(hdr, "MODIFIABLE"), MODIFIABLE_FIELD_ORDER, doc);
        // ADRESS_MODIFIABLE（订单-是否可以修改地址）→ order__c.adress_Modifiable__c
        setIfPresent(o, asString(hdr, "ADRESS_MODIFIABLE"), ADRESS_MODIFIABLE_FIELD_ORDER, doc);

        // 返回信息 E_RES_ORDERS_LINE.RETURNMESSAGE（如"正确"）：order__c 无对应字段，仅记日志
        if (doc.returnMsg != null) {
            logger.info(TAG + " 返回信息 RETURNMESSAGE=" + doc.returnMsg);
        }

        PendingOrder pending = new PendingOrder();
        pending.o = o;
        pending.doc = doc;
        return pending;
    }

    /**
     * 回写源报价单：verifier__c / quoteStatus__c=4（全部确认）/ relevantOrder__c=订单id。
     * <p><b>必须在订单写入之后调用</b>：新订单插入前 o.getId() 为空，
     * 此时回写 relevantOrder__c 会写成null，关联关系就丢了。</p>
     * <p>同一条报价单可能被多张订单命中（SAP 批量推送场景），按 quoteId 去重，
     * 保留最后一次命中的订单 id（后覆盖前，与其它批量回写口径一致）。</p>
     *
     * @return 失败条数（订单未成功落库、拿不到 id 的也算失败）
     */
    private int writeBackSourceQuotes(List<Doc> docs) {
        int fail = 0;
        // quoteId → Quote__c（同 id 后覆盖前）
        Map<Long, Quote__c> byQuoteId = new LinkedHashMap<>();
        for (Doc doc : docs) {
            if (doc.sourceQuoteId == null) {
                continue;
            }
            if (doc.orderId == null) {
                // 订单没落库/没拿到 id → 无法建立关联，跳过并告警
                fail++;
                logger.warn(TAG + " 订单未落库，跳过源报价单回写（relevantOrder__c 无法赋值）"
                        + " orderNumber=" + doc.orderNumber + ", quoteId=" + doc.sourceQuoteId);
                continue;
            }
            Quote__c upQuote = new Quote__c();
            upQuote.setId(doc.sourceQuoteId);
            if (doc.sourceQuoteVerifier == null) {
                // 报价单还没有验证人 → 用本次报文的 USERNAME 填充（原有行为）
                upQuote.setVerifier__c(doc.verifierName);
            } else {
                // 报价单已有验证人 → 保持不动（不 set 该字段即为部分更新，库里原值保留）
                logger.info(TAG + " 源报价单已有验证人，不覆盖: quoteId=" + doc.sourceQuoteId
                        + ", orderNumber=" + doc.orderNumber + ", verifier=" + doc.sourceQuoteVerifier);
            }
            upQuote.setQuoteStatus__c(4);
            upQuote.setRelevantOrder__c(doc.orderId);
            byQuoteId.put(doc.sourceQuoteId, upQuote);
        }
        if (byQuoteId.isEmpty()) {
            return fail;
        }
        List<Quote__c> updates = new ArrayList<>(byQuoteId.values());
        try {
            BatchOperateResult br = XObjectService.instance().update(updates, true, true);
            boolean ok = br != null && Boolean.TRUE.equals(br.getSuccess());
            logger.info(TAG + " 源报价单回写: 条数=" + updates.size() + " success=" + ok
                    + " err=" + (br == null ? "null" : br.getErrorMessage()));
            if (!ok) {
                fail += updates.size();
            }
        } catch (Exception e) {
            logger.error(TAG + " 源报价单回写失败 条数=" + updates.size() + " err=" + e.getMessage(), e);
            fail += updates.size();
        }
        return fail;
    }

    /**
     * 回写特价申请单：订单落库拿到 id 后，对「特价申请单（pmsFlag=ESPA）」来的订单，
     * 用 pmsId__c（特价申请单号）反查 specialOrderApplication__c（按 specialOfferOrderNo__c 匹配），
     * 把订单 id 回写到 relevantOrder__c（lookup order__c）。
     *
     * <p>找不到特价申请单只记 warn 不阻断主流程（回写是附加动作，订单本身已正常落库）。</p>
     */
    private void writeBackSpecialOrders(List<Doc> docs) {
        for (Doc doc : docs) {
            if (!"ESPA".equals(doc.pmsFlag) || doc.pmsId == null || doc.pmsId.trim().isEmpty()) {
                continue;
            }
            if (doc.orderId == null) {
                logger.warn(TAG + " 特价订单未落库，跳过特价申请单回写 orderNumber=" + doc.orderNumber
                        + ", pmsId=" + doc.pmsId);
                continue;
            }
            try {
                String sql = "SELECT id FROM specialOrderApplication__c WHERE specialOfferOrderNo__c = '"
                        + doc.pmsId.trim() + "' LIMIT 1";
                QueryResult<JSONObject> res = XoqlService.instance().query(sql);
                if (res == null || res.getRecords() == null || res.getRecords().isEmpty()) {
                    logger.warn(TAG + " 未找到特价申请单 specialOrderApplication__c: specialOfferOrderNo__c="
                            + doc.pmsId + "（订单 orderNumber=" + doc.orderNumber + " 不挂接）");
                    continue;
                }
                Long spId = res.getRecords().get(0).getLong("id");
                if (spId == null) {
                    continue;
                }
                SpecialOrderApplication__c sp = new SpecialOrderApplication__c();
                sp.setId(spId);
                sp.setRelevantOrder__c(doc.orderId);
                OperateResult r = XObjectService.instance().update(sp, true);
                logger.info(TAG + " 特价申请单回写订单: spId=" + spId + ", relevantOrder__c=" + doc.orderId
                        + ", orderNumber=" + doc.orderNumber
                        + ", success=" + (r != null && Boolean.TRUE.equals(r.getSuccess())));
            } catch (Exception e) {
                logger.error(TAG + " 特价申请单回写异常 orderNumber=" + doc.orderNumber + " pmsId=" + doc.pmsId, e);
            }
        }
    }

    /**
     * 批量写入订单（分批 200），按 operateResults 顺序回填 id。
     *
     * @return 失败条数
     */
    private int writeOrders(List<PendingOrder> pendings, boolean isInsert) {
        int fail = 0;
        if (pendings.isEmpty()) {
            return fail;
        }
        String opName = isInsert ? "insert" : "update";
        for (int from = 0; from < pendings.size(); from += WRITE_BATCH_SIZE) {
            List<PendingOrder> part = pendings.subList(from, Math.min(from + WRITE_BATCH_SIZE, pendings.size()));
            List<Order__c> objs = new ArrayList<>();
            for (PendingOrder p : part) {
                objs.add(p.o);
            }
            BatchOperateResult br;
            try {
                br = isInsert
                        ? XObjectService.instance().insert(objs, true,true)
                        : XObjectService.instance().update(objs,true,true);
            } catch (Exception e) {
                logger.error(TAG + " 批量" + opName + "订单异常 条数=" + part.size() + " err=" + e.getMessage(), e);
                fail += part.size();
                continue;
            }
            boolean batchOk = br != null && Boolean.TRUE.equals(br.getSuccess());
            List<OperateResult> ops = br == null ? null : br.getOperateResults();
            int partFail = 0;
            for (int k = 0; k < part.size(); k++) {
                PendingOrder p = part.get(k);
                OperateResult r = (ops != null && k < ops.size()) ? ops.get(k) : null;
                if (batchOk && r != null && Boolean.TRUE.equals(r.getSuccess())) {
                    Long id = isInsert ? r.getDataId() : p.o.getId();
                    if (id != null) {
                        p.o.setId(id);
                        p.doc.orderId = id;
                    } else {
                        partFail++;
                        logger.error(TAG + " 订单" + opName + "后未获取到 id orderNumber=" + p.doc.orderNumber);
                    }
                } else {
                    partFail++;
                    String err = !batchOk
                            ? (br == null ? "批量" + opName + "返回 null" : br.getErrorMessage())
                            : (r == null ? "单条结果缺失" : r.getErrorMessage());
                    logger.error(TAG + " 订单" + opName + "失败 orderNumber=" + p.doc.orderNumber + " err=" + err);
                }
            }
            fail += partFail;
            logger.info(TAG + " 批量" + opName + "订单 成功=" + (part.size() - partFail) + "/" + part.size());
        }
        return fail;
    }

    // ===================== 明细构建 / 批量写入 =====================

    /**
     * 预取「SAP VSTEL → 发运地点描述」映射。
     *
     * <p><b>链路</b>：收集本批 SAP 明细里的 VSTEL → 查 {@code shippingPoint__c}（发运地点）
     * 的 {@code name}（形如 B111/C111/E111）→ 命中则取该记录的 {@code shipToDesc__c}（发运地点描述）。</p>
     *
     * <p><b>匹配口径</b>：按 {@code name} 精确匹配、<b>忽略大小写</b>、trim 后比对（老板 2026-10-21 确认）。
     * key 统一转大写后存储，避免调用方因大小写不同而匹配不上。</p>
     *
     * <p><b>为什么一次查全表</b>：发运地点是<b>主数据小表</b>，一次全量取回内存匹配，
     * 避免在「订单 × 行」双层循环里逐行查库。</p>
     *
     * @return 大写 VSTEL → 发运地点描述；未命中则不含该 key
     */
    private Map<String, String> queryShippingPointDescMap(List<Doc> docs) {
        Map<String, String> map = new HashMap<>();
        try {
            // 本批用到的 VSTEL（仅用于日志与提示，不参与过滤：发运地点是主数据，直接全量取）
            Set<String> vstelUsed = new HashSet<>();
            for (Doc doc : docs) {
                if (doc == null || doc.dtl == null) {
                    continue;
                }
                for (int i = 0; i < doc.dtl.size(); i++) {
                    JSONObject item = doc.dtl.getJSONObject(i);
                    if (item != null) {
                        String v = trimToNull(asString(item, "VSTEL"));
                        if (v != null) {
                            vstelUsed.add(v.toUpperCase());
                        }
                    }
                }
            }
            if (vstelUsed.isEmpty()) {
                return map;
            }

            String sql = "SELECT id, name, shipToDesc__c FROM shippingPoint__c LIMIT 0, "
                    + SHIPPING_POINT_LIMIT;
            QueryResult<JSONObject> rt = XoqlService.instance().query(sql);
            if (rt == null || rt.getRecords() == null) {
                logger.warn(TAG + " 查 shippingPoint__c 失败，发运地点将保留 SAP 原值");
                return map;
            }
            int blankDesc = 0;
            for (JSONObject o : rt.getRecords()) {
                String name = trimToNull(o.getString("name"));
                if (name == null) {
                    continue;
                }
                String desc = trimToNull(o.getString("shipToDesc__c"));
                if (desc == null) {
                    blankDesc++;
                }
                // shipToDesc__c 为空的记录不入映射（调用方会保留 SAP 原值并 warn）
                if (desc != null) {
                    map.put(name.toUpperCase(), desc);
                }
            }
            logger.info(TAG + " 发运地点主数据: shippingPoint__c " + rt.getRecords().size() + " 条"
                    + "（其中 " + blankDesc + " 条 shipToDesc__c 为空不参与匹配）, 本批用到 VSTEL "
                    + vstelUsed.size() + " 个, 命中描述 " + map.size() + " 个");
        } catch (Exception e) {
            logger.error(TAG + " 预取发运地点描述失败，发运地点将保留 SAP 原值", e);
        }
        return map;
    }

    /**
     * 构建单条明细（不写库）。已存在（行号数字匹配）→ setId 走更新；否则新建。
     * 变更检测（仅已订阅的行且库里原值非空才通知）：
     *  - 行状态 LINEITEMSTATUS 变更 → 【订阅通知】订单行状态发送变更
     *  - 期望发货日期 COMMITTEDDATE/EDATU 变更 → 【订阅通知】期望发货日期发生变更（旧值备份 requestedDeliveryDateOld__c）
     *  - 预计交货日期 PROMISEDATE 变更 → 【订阅通知】预计交货日期发生变更
     * 通知只构造不写库，统一收集后批量 insert。
     */
    private PendingDetail buildDetail(Doc doc, JSONObject item, Map<Integer, ExistingDetail> existingByPos,
                                      Map<String, Long> productMap, JSONObject existingOrder,
                                      Map<String, Integer> lineStatusOpts, Map<Integer, String> lineStatusLabels,
                                      List<MessageCenter__c> notifications, Map<String, Long> shipToMap,
                                      Map<String, String> shippingPointDescMap) {
        // 行号(POSICION → posicion__c) 转数字匹配：天然兼容 SAP "00010" 与 CRM "10"
        String pos = trimToNull(asString(item, "POSICION"));
        Integer posKey = parsePosKey(pos);
        if (pos != null && posKey == null) {
            logger.warn(TAG + " 行号无法解析为数字，按新行处理: POSICION=" + pos);
        }
        ExistingDetail existing = posKey != null ? existingByPos.get(posKey) : null;
        Long existingId = existing != null ? existing.id : null;
        boolean isUpdate = existingId != null;

        PendingDetail pd = new PendingDetail();
        pd.doc = doc;
        pd.orderId = doc.orderId;
        pd.posKey = posKey;

        OrderDetail__c d = new OrderDetail__c();
        pd.d = d;
        if (isUpdate) {
            d.setId(existingId);
        } else {
            d.setEntityType(ORDER_DETAIL_BUSI_TYPE);
            d.setOrder__c(doc.orderId);
        }
        if (pos != null) {
            d.setPosicion__c(Integer.parseInt(pos)+"");
        }
        // 订货号（CATALOGNUMBER）→ catelogNumber__c（lookup product，按 productCode__c 反查）
        String matNo = trimToNull(asString(item, "CATALOGNUMBER"));
        if (matNo != null) {
            Long productId = productMap.get(matNo);
            if (productId != null) {
                d.setCatelogNumber__c(productId);
            } else {
                logger.warn(TAG + " 未找到产品 product: productCode__c=" + matNo);
            }
        }

        // 通知接收方：账户取库里订单的 purchaseId__c（新单则取本次落库值），负责人取库里订单 ownerId
        Long accountId = existingOrder == null ? null : existingOrder.getLong("purchaseId__c");
        Long ownerId = existingOrder == null ? null : existingOrder.getLong("ownerId");

        // 订单行状态（SAP 传 lineItemStatus__c 的 optionApiKey；运行时从 CRM 元数据换取 optionCode）
        String lineStatus = trimToNull(asString(item, "LINEITEMSTATUS"));
        if (lineStatus != null) {
            Integer code = lineStatusOpts == null ? null : lineStatusOpts.get(lineStatus);
            if (code != null) {
                d.setLineItemStatus__c(code);
                if (isUpdate && Boolean.TRUE.equals(existing.subscribe__c)
                        && existing.lineItemStatus__c != null && !existing.lineItemStatus__c.equals(code)) {
                    String oldLabel = lineStatusLabels == null ? null : lineStatusLabels.get(existing.lineItemStatus__c);
                    String newLabel = lineStatusLabels == null ? null : lineStatusLabels.get(code);
                    notifications.add(newNotification("【订阅通知】订单行状态发送变更",
                            "【订阅】订单行:" + doc.orderNumber + "/" + posKey + ",状态从"
                                    + (oldLabel != null ? oldLabel : existing.lineItemStatus__c) + "变更为"
                                    + (newLabel != null ? newLabel : code),
                            accountId, ownerId, doc.orderNumber, "order__c",2));
                }
            } else {
                logger.warn(TAG + " 未识别订单行状态 LINEITEMSTATUS=" + lineStatus + "，跳过");
            }
        }

        // 期望发货日期（COMMITTEDDATE/EDATU → requestedDeliveryDate__c）
        // 变更检测：仅当该行已存在且库里原日期非空、且与本次 SAP 日期不同时，备份旧值并通知
        Long rdd = parseDate(asString(item, "EDATU"));
        pd.rdd = rdd;
        if (rdd != null) {
            Long oldDate = existing != null ? existing.requestedDeliveryDate : null;
            if (oldDate != null && !oldDate.equals(rdd)) {
                d.setRequestedDeliveryDateOld__c(oldDate);
                if (isUpdate) {
                    notifications.add(newNotification("【通知】期望发货日期发生变更",
                            "【通知】订单行:" + doc.orderNumber + "/" + posKey + " 的期望发货日期从"
                                    + formatDate(oldDate) + "变更为" + formatDate(rdd),
                            accountId, ownerId, doc.orderNumber, "order__c",6));
                    logger.info(TAG + " 明细行期望发货日期变更: orderNumber=" + doc.orderNumber + ", 行号=" + pos
                            + ", " + formatDate(oldDate) + " → " + formatDate(rdd));
                }
            }
            // 库里原日期为空（首次下发）时无"旧日期"可备份，不视为变更、不通知
            d.setRequestedDeliveryDate__c(rdd);
        }

        // 数量（QUANTITY → quantity__c，整型）
        Double qty = parseDouble(asString(item, "QUANTITY"));
        if (qty != null) {
            d.setQuantity__c(qty.longValue());
        }
        // 未交货数量（BACKORDEREDQUANTITY → backOrderedQuantity__c，文本）
        String backOrder = trimToNull(asString(item, "BACKORDEREDQUANTITY"));
        if (backOrder != null) {
            d.setBackOrderedQuantity__c(backOrder);
        }
        // 已发货数量（SHIPPEDQUANTITY → shippedQuantity__c，整型）
        Double shipped = parseDouble(asString(item, "SHIPPEDQUANTITY"));
        if (shipped != null) {
            d.setShippedQuantity__c(shipped.longValue());
        }

        // 列表价(含税)：PROFILEPRICE → unitnetprice__c
        Double listPrice = parseDouble(asString(item, "PROFILEPRICE"));
        if (listPrice != null) {
            d.setUnitnetprice__c(listPrice);
        }
        // 折扣后单价(含税)：UNITNETPRICE → profilePrice__c
        Double unitNetPrice = parseDouble(asString(item, "UNITNETPRICE"));
        if (unitNetPrice != null) {
            d.setProfilePrice__c(unitNetPrice);
        }
        // 折扣后总价合计(含税)：EXTENDEDPROFILEPRICE → extendedProfilePrice__c
        Double ext = parseDouble(asString(item, "EXTENDEDPROFILEPRICE"));
        if (ext != null) {
            d.setExtendedProfilePrice__c(ext);
        }
        // 折扣（下浮点）：GLOBALDISCOUNT → discountPercentage__c（直接存 SAP 下浮点原值，如 47.50）
        Double disc = parseDouble(asString(item, "DISCOUNTPERCENTAGE"));
        if (disc != null) {
            d.setDiscountPercentage__c(disc);
        }
        // 销售单位：SALES_UNIT → sales_Unit__c（本订单对象为【文本】字段，直接存原始值如 "PC"）
        String salesUnit = trimToNull(asString(item, "SALES_UNIT"));
        if (salesUnit != null) {
            d.setSales_Unit__c(salesUnit);
        }
        //量够下浮/库存折扣（下浮点）
        Double disc03a = parseDouble(asString(item, "DISCOUNTK03A"));
        if (disc03a != null) {
            d.setDiscountk03c__c(disc03a);
        }
        //订单直降（下浮点）
        Double disc03b = parseDouble(asString(item, "DISCOUNTK03B"));
        if (disc03b != null) {
            d.setDiscountk03b__c(disc03b);
        }
        //年奖抵扣金额	YEARLYDISCOUNT
        Double yearlydiscount = parseDouble(asString(item, "YEARLYDISCOUNT"));
        if (yearlydiscount != null) {
            d.setYearlyDiscount__c(yearlydiscount);
        }
        //季度抵扣金额	QUARTYBONUSDIS2
        Double monthlydiscount = parseDouble(asString(item, "MONTHLYDISCOUNT"));
        if (monthlydiscount != null) {
            d.setQuartyBonusdis2__c(monthlydiscount);
        }
        //月度抵扣金额	PCP_BONUS
        Double pcpBonus = parseDouble(asString(item, "PCP_BONUS"));
        if (pcpBonus != null) {
            d.setMonthlyDiscount__c(pcpBonus);
        }

        // 预计交货日期（PROMISEDATE → promiseDate__c
        Long promiseDate = parseDate(asString(item, "RESCHEDULEDATE"));
        pd.pd = promiseDate;
        if (promiseDate != null) {
            Long oldPd = existing != null ? existing.promiseDate : null;
            if (oldPd != null && !oldPd.equals(promiseDate)) {
                if (isUpdate && Boolean.TRUE.equals(existing.subscribe__c)) {
                    notifications.add(newNotification("【订阅通知】预计交货日期发生变更",
                            "【订阅】订单行:" + doc.orderNumber + "/" + posKey + " 的预计交货日期从"
                                    + formatDate(oldPd) + "变更为" + formatDate(promiseDate),
                            accountId, ownerId, doc.orderNumber, "order__c",2));
                    logger.info(TAG + " 明细行预计交货日期变更: orderNumber=" + doc.orderNumber + ", 行号=" + pos
                            + ", " + formatDate(oldPd) + " → " + formatDate(promiseDate));
                }
            }
            d.setPromiseDate__c(promiseDate);
        }

        String ship = buildAddress(item);
        if (ship != null) {
            d.setShipToAddress__c(ship);
        }

        // SAP 业务标识（文本字段，原样存 SAP 值）
        // mtvfp（明细-是否有库存）→ orderDetail__c.mtvfp__c
        setIfPresent(d, asString(item, "MTVFP"), MTVFP_FIELD_DETAIL, doc, pos);
        // MODIFIABLE（明细-是否可以修改日期）→ orderDetail__c.modifiable__c
        setIfPresent(d, asString(item, "MODIFIABLE"), MODIFIABLE_FIELD_DETAIL, doc, pos);
        // ADRESS_MODIFIABLE（明细-是否可以修改地址）→ orderDetail__c.adress_Modifiable__c
        setIfPresent(d, asString(item, "ADRESS_MODIFIABLE"), ADRESS_MODIFIABLE_FIELD_DETAIL, doc, pos);

        // 发货地址信息：行级 CUSTOMERID（缺失回退头 CUSTOMERID）+ 售达方账户 → shipToParty__c → customerId__c
        String itemShipToCode = trimToNull(itemCustomerCode(item));
        if (itemShipToCode == null) {
            itemShipToCode = trimToNull(asString(doc.hdr, "CUSTOMERID"));
        }
        Long detailAccountId = doc.accountId != null ? doc.accountId
                : (existingOrder == null ? null : existingOrder.getLong("purchaseId__c"));
        if (itemShipToCode != null && detailAccountId != null) {
            Long shipToId = shipToMap == null ? null : shipToMap.get(shipToKey(detailAccountId, itemShipToCode));
            if (shipToId != null) {
                d.setCustomerId__c(shipToId);
            } else {
                logger.warn(TAG + " 未找到发货地址 shipToParty__c: purchaseId__c=" + detailAccountId
                        + " kunnr__c=" + itemShipToCode + "（明细行 CUSTOMERID），customerId__c 不赋值");
            }
        }

        d.setAbgru__c(asString(item,"ABGRU"));

        // 发运地点：SAP VSTEL → 发运地点(shippingPoint__c) 按名称匹配 → 存发运地点描述
        //   匹配到 → shippingPoint__c 存该发运地点的 shipToDesc__c（发运地点描述）
        //   未匹配 → 保留 SAP 原始 VSTEL 值，不丢数据
        String vstel = trimToNull(asString(item, "VSTEL"));
        if (vstel != null) {
            // ⚠️ 必须转大写查：queryShippingPointDescMap 的 key 是 name.toUpperCase()，
            //    SAP 传小写（b111）时不转大写会匹配不上
            String shipPointDesc = shippingPointDescMap == null ? null
                    : shippingPointDescMap.get(vstel.toUpperCase());
            if (shipPointDesc != null && !shipPointDesc.isEmpty()) {
                d.setShippingPoint__c(shipPointDesc);
            } else {
                d.setShippingPoint__c(vstel);
                logger.warn(TAG + " 发运地点未匹配到 shippingPoint__c，保留 SAP 原值: orderNumber="
                        + doc.orderNumber + ", 行号=" + pos + ", VSTEL=" + vstel);
            }
        }

        return pd;
    }

    /**
     * 批量写入明细（分批 200），按 operateResults 顺序回填 id；insert 成功后回填已有明细映射防同批重复行号。
     *
     * @return 失败条数
     */
    private int writeDetails(List<PendingDetail> pendings, boolean isInsert,
                             Map<Long, Map<Integer, ExistingDetail>> existingByOrder) {
        int fail = 0;
        if (pendings.isEmpty()) {
            return fail;
        }
        String opName = isInsert ? "insert" : "update";
        for (int from = 0; from < pendings.size(); from += WRITE_BATCH_SIZE) {
            List<PendingDetail> part = pendings.subList(from, Math.min(from + WRITE_BATCH_SIZE, pendings.size()));
            List<OrderDetail__c> objs = new ArrayList<>();
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
                        Map<Integer, ExistingDetail> byPos = existingByOrder.get(p.orderId);
                        if (byPos == null) {
                            byPos = new HashMap<>();
                            existingByOrder.put(p.orderId, byPos);
                        }
                        ExistingDetail ref = new ExistingDetail();
                        ref.id = r.getDataId();
                        ref.requestedDeliveryDate = p.rdd;
                        ref.promiseDate = p.pd;
                        byPos.put(p.posKey, ref);
                    }
                } else {
                    String err = !batchOk
                            ? (br == null ? "批量" + opName + "返回 null" : br.getErrorMessage())
                            : (r == null ? "单条结果缺失" : r.getErrorMessage());
                    logger.error(TAG + " 明细" + opName + "失败 orderNumber="
                            + (p.doc == null ? null : p.doc.orderNumber) + " 行号=" + p.posKey + " err=" + err);
                }
            }
            fail += part.size() - ok;
            logger.info(TAG + " 批量" + opName + "明细 成功=" + ok + "/" + part.size());
        }
        return fail;
    }

    /** 已有明细的引用信息：id + 库里当前的期望发货日期/预计交货日期/行状态/订阅标志。 */
    private static class ExistingDetail {
        Long id;
        Long requestedDeliveryDate;
        Long promiseDate;
        Integer lineItemStatus__c;
        Boolean subscribe__c;
    }

    /**
     * 按 orderNumberAutoGenerate__c 批量预查询已有订单
     * （含 ownerId / purchaseId__c，供订阅通知使用）。
     */
    private Map<String, JSONObject> queryOrdersByNumbers(Set<String> orderNumbers) {
        Map<String, JSONObject> map = new HashMap<>();
        if (orderNumbers.isEmpty()) {
            return map;
        }
        List<String> list = new ArrayList<>(orderNumbers);
        for (int from = 0; from < list.size(); from += QUERY_BATCH_SIZE) {
            List<String> part = list.subList(from, Math.min(from + QUERY_BATCH_SIZE, list.size()));
            String xoql = "SELECT id, orderNumberAutoGenerate__c, ownerId, purchaseId__c,creator__c FROM order__c"
                    + " WHERE orderNumberAutoGenerate__c IN (" + buildInClause(part) + ")";
            logger.info(TAG + " SQL: " + xoql);
            try {
                QueryResult<JSONObject> qr = XoqlService.instance().query(xoql);
                if (qr != null && qr.getRecords() != null) {
                    for (JSONObject rec : qr.getRecords()) {
                        if (rec == null) {
                            continue;
                        }
                        String num = rec.getString("orderNumberAutoGenerate__c");
                        if (num != null) {
                            map.put(num, rec);
                        }
                    }
                }
                logger.info(TAG + " 订单预查询: 入参=" + part.size() + " 命中=" + map.size());
            } catch (Exception e) {
                logger.error(TAG + " 预查询订单失败: " + e.getMessage(), e);
            }
        }
        return map;
    }

    /**
     * 发货地址批量预查询：账户id(PURCHASERID 反查的 account) + 送达方客户号(CUSTOMERID) → shipToParty__c.id。
     * <p>
     * 唯一键 = shipToParty__c.purchaseId__c + kunnr__c（与 MYSE-13 落库口径一致）。
     * 同一键命中多条（历史重复数据）时取 id 最小的一条，保证幂等。
     * </p>
     */
    private Map<String, Long> queryShipToPartyIds(Map<String, Long> accountMap, Set<String> shipToCodes) {
        Map<String, Long> map = new HashMap<>();
        if (accountMap == null || accountMap.isEmpty() || shipToCodes == null || shipToCodes.isEmpty()) {
            return map;
        }
        // 账户 id 去重（同一批订单通常只有少量账户）
        StringBuilder accIn = new StringBuilder();
        for (Long id : new LinkedHashSet<>(accountMap.values())) {
            if (id == null) {
                continue;
            }
            if (accIn.length() > 0) {
                accIn.append(",");
            }
            accIn.append(id);
        }
        if (accIn.length() == 0) {
            return map;
        }
        List<String> codes = new ArrayList<>(shipToCodes);
        for (int from = 0; from < codes.size(); from += QUERY_BATCH_SIZE) {
            List<String> part = codes.subList(from, Math.min(from + QUERY_BATCH_SIZE, codes.size()));
            String xoql = "SELECT id, purchaseId__c, kunnr__c FROM shipToParty__c"
                    + " WHERE purchaseId__c IN (" + accIn + ")"
                    + " AND kunnr__c IN (" + buildInClause(part) + ") LIMIT 0, 2000";
            logger.info(TAG + " SQL: " + xoql);
            try {
                QueryResult<JSONObject> qr = XoqlService.instance().query(xoql);
                if (qr != null && qr.getRecords() != null) {
                    for (JSONObject rec : qr.getRecords()) {
                        if (rec == null) {
                            continue;
                        }
                        Long id = rec.getLong("id");
                        Long accId = rec.getLong("purchaseId__c");
                        String kunnr = normalizeKunnr(rec.getString("kunnr__c"));
                        if (id == null || accId == null || kunnr == null) {
                            continue;
                        }
                        String key = accId + "#" + kunnr;
                        Long prev = map.get(key);
                        if (prev == null || id < prev) {
                            map.put(key, id);
                        }
                    }
                }
                logger.info(TAG + " 发货地址预查询: 账户=" + accIn + " 编码入参=" + part.size() + " 命中=" + map.size());
            } catch (Exception e) {
                logger.error(TAG + " 预查询发货地址失败: " + e.getMessage(), e);
            }
        }
        return map;
    }

    /**
     * 按 orderId 批量预查询已有明细：orderId → (行号数字 → id + 日期 + 行状态 + 订阅标志)。
     * 走平台内部 XOQL（系统鉴权），与 queryIds 同通道。
     */
    private Map<Long, Map<Integer, ExistingDetail>> queryExistingDetailsByOrderIds(Set<Long> orderIds) {
        Map<Long, Map<Integer, ExistingDetail>> map = new HashMap<>();
        if (orderIds.isEmpty()) {
            return map;
        }
        StringBuilder inClause = new StringBuilder();
        for (Long id : orderIds) {
            if (inClause.length() > 0) {
                inClause.append(",");
            }
            inClause.append(id);
        }
        String xoql = "SELECT id, order__c, posicion__c, requestedDeliveryDate__c, promiseDate__c,"
                + " lineItemStatus__c, subscribe__c FROM orderDetail__c WHERE order__c IN (" + inClause + ")";
        logger.info(TAG + " 内部XOQL: " + xoql);
        try {
            RkhdHttpData httpData = RkhdHttpData.newBuilder()
                    .callString("/rest/data/v2.0/query/xoql")
                    .callType("POST")
                    .formData("useSimpleCode", "true")
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
                        Long orderId = rec.getLong("order__c");
                        Integer posKey = parsePosKey(trimToNull(rec.getString("posicion__c")));
                        if (id == null || orderId == null || posKey == null) {
                            continue;
                        }
                        Map<Integer, ExistingDetail> byPos = map.get(orderId);
                        if (byPos == null) {
                            byPos = new HashMap<>();
                            map.put(orderId, byPos);
                        }
                        ExistingDetail ref = new ExistingDetail();
                        ref.id = Long.parseLong(id.toString());
                        ref.requestedDeliveryDate = extractTs(rec.get("requestedDeliveryDate__c"));
                        ref.promiseDate = extractTs(rec.get("promiseDate__c"));
                        ref.lineItemStatus__c = rec.getInteger("lineItemStatus__c");
                        ref.subscribe__c = rec.getBoolean("subscribe__c");
                        ExistingDetail prevRef = byPos.put(posKey, ref);
                        if (prevRef != null && !prevRef.id.equals(ref.id)) {
                            logger.warn(TAG + " 订单明细行号重复(数字化后相同): orderId=" + orderId
                                    + ", posKey=" + posKey + ", 已有id=" + prevRef.id + ", 本次id=" + ref.id
                                    + "（保留先查到的 " + prevRef.id + "）");
                            byPos.put(posKey, prevRef);
                        }
                    }
                }
            }
            logger.info(TAG + " 已有明细预查询: orderId数=" + orderIds.size() + " 命中订单数=" + map.size());
        } catch (Exception e) {
            logger.warn(TAG + " 内部XOQL查询已有明细失败", e);
        }
        return map;
    }

    // ===================== 删除 =====================

    private int deleteOrder(String orderNumber) throws ApiEntityServiceException {
        List<Long> ids = queryIds("SELECT id FROM order__c WHERE orderNumberAutoGenerate__c = '"
                + escape(orderNumber) + "' LIMIT 1");
        if (ids.isEmpty()) {
            logger.info(TAG + " 删除：未找到订单 orderNumber=" + orderNumber);
            return 0;
        }
        Long orderId = ids.get(0);
//        int deletedDetails = deleteOrderDetails(orderId);
        Order__c stub = new Order__c();
        stub.setId(orderId);
        OperateResult res=XObjectService.instance().delete(stub,true);
        logger.info(TAG + " 删除订单状态 orderNumber=" + orderNumber + ", 明细=" + res.getErrorMessage());
        return 0;
    }

    // ===================== 查询/解析/工具方法 =====================

    /** 用平台内部 XOQL（系统鉴权，绕过 API 上下文对象级权限隔离）执行查询并返回 id 列表。 */
    private List<Long> queryIds(String xoql) {
        List<Long> ids = new ArrayList<>();
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
                        Object id = records.getJSONObject(i).get("id");
                        if (id != null) {
                            ids.add(Long.parseLong(id.toString()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn(TAG + " 内部XOQL查询失败: " + xoql, e);
        }
        return ids;
    }

    /**
     * 客户号归一化：去空格 + 去前导零（SAP 常补 10 位如 "0000136100"，CRM 存 "136100"）。
     * 非数字（如含字母的客户号）原样返回，不再抛 NumberFormatException 中断整单。
     */
    private String normalizeKunnr(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        if (v.isEmpty()) {
            return null;
        }
        try {
            return String.valueOf(Long.parseLong(v));
        } catch (Exception e) {
            return v;
        }
    }

    /** 发货地址唯一键：账户id + 送达方客户号（归一化后）。 */
    private String shipToKey(Long accountId, String kunnr) {
        return accountId + "#" + normalizeKunnr(kunnr);
    }

    /**
     * 行号转数字 key：天然去前导零（SAP "00010" 与 CRM "10" → 10）。
     * 空值或非数字返回 null（调用方按新行处理），不再抛 NumberFormatException 中断整单。
     */
    private Integer parsePosKey(String pos) {
        if (pos == null) {
            return null;
        }
        String s = pos.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (Exception e) {
            return null;
        }
    }
    /**
     * 解析金额字符串（如 " +5358.3500"、" +1"、"+30.00"、" 0"）：去空格/千分位/前导正负号后转 Double。
    /** SAP 信用状态原始值（=选项 label，忽略大小写）→ CRM 选项码；未知返回 null。 */
    private Integer mapCreditStatus(String sapStatus) {
        if (sapStatus == null) {
            return null;
        }
        return SAP_CREDIT_STATUS_TO_CODE.get(sapStatus.trim().toUpperCase(Locale.ROOT).replace(" ", ""));
    }

    // ===================== SAP 业务标识字段写入（setAttribute 通用写法）=====================

    /**
     * 订单级业务标识字段写入：SAP 值有值才写，null/空串跳过（避免用空值覆盖库中已有值）。
     *
     * @param o        订单实体
     * @param sapValue SAP 报文里的原始值（可能为 null / 空串）
     * @param field    order__c 字段 apiKey
     * @param doc      单据上下文（仅日志用）
     */
    private void setIfPresent(Order__c o, String sapValue, String field, Doc doc) {
        String v = trimToNull(sapValue);
        if (v == null) {
            return;
        }
        o.setAttribute(field, v);
        logger.info(TAG + " 订单写入 " + field + "=" + v + ", orderNumber=" + doc.orderNumber);
    }

    /**
     * 明细级业务标识字段写入：SAP 值有值才写，null/空串跳过。
     *
     * @param d        订单明细实体
     * @param sapValue SAP 报文里的原始值（可能为 null / 空串）
     * @param field    orderDetail__c 字段 apiKey
     * @param doc      单据上下文（仅日志用）
     * @param pos      行号（仅日志用）
     */
    private void setIfPresent(OrderDetail__c d, String sapValue, String field, Doc doc, String pos) {
        String v = trimToNull(sapValue);
        if (v == null) {
            return;
        }
        d.setAttribute(field, v);
        logger.info(TAG + " 明细行写入 " + field + "=" + v
                + ", orderNumber=" + doc.orderNumber + ", 行号=" + pos);
    }
}
