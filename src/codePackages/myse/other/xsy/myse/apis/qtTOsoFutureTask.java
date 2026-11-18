package other.xsy.myse.apis;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.context.ScriptRuntimeContext;
import com.rkhd.platform.sdk.data.model.*;
import com.rkhd.platform.sdk.exception.ApiEntityServiceException;
import com.rkhd.platform.sdk.exception.ScriptBusinessException;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.model.XObject;
import com.rkhd.platform.sdk.service.CustomConfigService;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;
import com.rkhd.platform.sdk.task.FutureTask;
import org.apache.commons.lang.StringUtils;
import other.xsy.myse.utils.HttpUtil;
import other.xsy.myse.utils.PiConfig;

import java.util.*;
import java.util.stream.Collectors;

import static other.xsy.myse.utils.JsonValues.formatDateSAP;
import static other.xsy.myse.utils.JsonValues.nullSafe;
import static other.xsy.myse.utils.JsonValues.trimToNull;
import static other.xsy.myse.utils.StringUtil.padKunnr;

public class qtTOsoFutureTask implements FutureTask {
    private static final Logger logger = LoggerFactory.getLogger();

    private static String PI_URL;

    private static String API_TOKEN;

    // ===================== 状态码 / 通知常量 =====================

    /** quote__c.quoteStatus__c：2 = 等待确认（转单失败时回滚到此状态，让用户可重新发起转单） */
    private static final Integer QUOTE_STATUS_WAITING_CONFIRM = 2;
    /** quote__c.quoteStatus__c：3 = 正在创建订单（转单成功时回写） */
    private static final Integer QUOTE_STATUS_CREATING_ORDER = 3;
    /** quote__c.syncStatus__c：3 = 同步失败 */
    private static final Integer SYNC_STATUS_FAILED = 3;
    /** messageCenter__c.messageType__c：1 = 待办事项 */
    private static final Integer MSG_TYPE_TODO = 1;

    /** 消息中心业务类型（entityType），取自配置集 entityType.MessageCenter；不可用时失败通知降级为不发送 */
    private static Long MESSAGE_CENTER_BUSI_TYPE;

    private static boolean configLoaded = false;

    /** 延迟加载配置（不在 static 块里做 SDK 查询，避免类初始化阶段类加载失败）；只执行一次。 */
    private static void ensureConfigLoaded() {
        if (configLoaded) {
            return;
        }
        configLoaded = true;
        PiConfig.Entry c = PiConfig.get("MYSE-04");
        if (c != null) {
            PI_URL = c.getUrl();
            API_TOKEN = c.getToken();
        }
        // 消息中心业务类型：单独 try，失败只降级（不影响转单主流程）
        try {
            Map<String, String> configSet = CustomConfigService.instance().getConfigSet("entityType");
            String mcKey = configSet == null ? null : configSet.get("MessageCenter");
            MESSAGE_CENTER_BUSI_TYPE = (mcKey == null || mcKey.trim().isEmpty()) ? null : Long.parseLong(mcKey.trim());
            if (MESSAGE_CENTER_BUSI_TYPE == null) {
                logger.warn("MYSE-04 配置集 entityType 缺少 MessageCenter，转单失败通知将不发送");
            }
        } catch (Exception e) {
            MESSAGE_CENTER_BUSI_TYPE = null;
            logger.warn("MYSE-04 读取 MessageCenter 业务类型失败，转单失败通知将不发送: " + e.getMessage());
        }
    }

    /****
     *
     * @param param
     * @throws ScriptBusinessException
     */
    @Override
    public void execute(String param) throws ScriptBusinessException {
        Long quoteId=Long.parseLong(param);
        try {
            ensureConfigLoaded();
            // 1) 加载报价单主记录
            Quote__c quote = new Quote__c();
            quote.setId(quoteId);
            quote = XObjectService.instance().get(quote, true);

            // 2) 查明细（XOQL 取 JSONObject，确保拿到 model.jar 可能缺 getter 的字段）
            String detailSql = "SELECT id, posicion__c, catalogNumber__c, quantity__c, requestedDeliveryDate__c,shipToCode__c "
                    + "FROM quoteDetail__c WHERE quoteId__c=" + quoteId;
            logger.info("MYSE-04 明细SQL: " + detailSql);
            QueryResult<JSONObject> detailRes = XoqlService.instance().query(detailSql);
            List<JSONObject> details = detailRes.getRecords() != null ? detailRes.getRecords() : Collections.emptyList();
            logger.info("MYSE-04 明细数: " + details.size());

            // 3) 反查产品名称（MATNR）与客户代码（kunnr）
            Map<Long, String> productNameMap = queryProductNames(details);
            String kunnr = padKunnr(queryKunnr(quote.getPurchaserId__c()));

            // 4) 构建 SAP 转单报文并出站
            JSONObject sapPayload = build(quote, details, productNameMap, kunnr);
            logger.info("MYSE-04 SAP报文: " + sapPayload.toJSONString());

            JSONObject piResponse = HttpUtil.post(PI_URL, sapPayload, API_TOKEN);

            // 5) SAP 结果处理：失败 → 回写状态 + 发失败通知（含 SAP 报错原因）；成功 → 回写状态 + 关掉待办
            if (piResponse == null) {
                // 出站异常（PI 无响应/网关不通）：也按失败处理，提示语与 SAP 业务失败区分开
                logger.error("MYSE-04 SAP PI 返回为空（出站异常）quoteId=" + quoteId);
                handleTransferFailed(quote, quoteId, "SAP PI 返回为空（出站异常），请稍后重试");
                return;
            }
            JSONObject respPedido = piResponse.getJSONObject("RESP_PEDIDO");
            if (respPedido == null) {
                logger.error("MYSE-04 SAP 返回缺少 RESP_PEDIDO 节点: " + piResponse.toJSONString());
                handleTransferFailed(quote, quoteId, "SAP 返回报文异常（缺少 RESP_PEDIDO）");
                return;
            }
            String returnCode = respPedido.getString("RETURNCODE");
            if ("E".equals(returnCode)) {
                // ★转单失败：回写报价单状态 + 发失败通知（带 SAP 报错原因）
                String errMsg = trimToNull(respPedido.getString("RETURNMESSAGE"));
                if (errMsg == null) {
                    errMsg = trimToNull(respPedido.getString("MESSAGE"));
                }
                if (errMsg == null) {
                    errMsg = "SAP 未返回具体错误信息";
                }
                logger.error("MYSE-04 SAP转单失败 quoteId=" + quoteId + " RETURNCODE=" + returnCode
                        + " RETURNMESSAGE=" + errMsg);
                handleTransferFailed(quote, quoteId, errMsg);
                return;
            }

            // 成功：回写状态 + 把待确认的待办标记为已处理
            Quote__c upQuote = new Quote__c();
            upQuote.setId(quoteId);
            upQuote.setQuoteStatus__c(QUOTE_STATUS_CREATING_ORDER);
            upQuote.setStatusDate__c(new Date().getTime());

            //当前操作人
            Long userId = ScriptRuntimeContext.instance().getUserId();
            QueryResult<JSONObject> request=XoqlService.instance().query("select name from user where id="+userId,true);
            if (request.getRecords().size()>0) {
                upQuote.setVerifier__c(request.getRecords().get(0).getString("name"));
            }
            XObjectService.instance().update(upQuote, true);

            String sql = "select id from messageCenter__c where isHandle__c=2 and todoType__c=2 and messageType__c=1 and dataId__c='"
                    + quote.getQuoteNumberAutoGenerate__c() + "'";
            logger.info("sql:" + sql);
            QueryResult<MessageCenter__c> res = XObjectService.instance().query(sql, true);
            logger.info("res:" + res.getRecords());
            if (!res.getRecords().isEmpty()) {
                MessageCenter__c upMsg = res.getRecords().get(0);
                upMsg.setIsHandle__c(1);
                XObjectService.instance().update(upMsg, true);
            }
        } catch (ApiEntityServiceException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 转单失败处理：回写报价单状态 + 写同步信息 + 给报价单所有人发一条失败通知（含 SAP 报错原因）。
     *
     * <p>回写字段：</p>
     * <ul>
     *   <li>{@code quoteStatus__c = 2}（<b>等待确认</b>）—— 从"正在创建订单"回滚，用户可重新发起转单。
     *       老板 2026-10-17 确认：失败不用"创建失败"(7)，回到"等待确认"更便于用户重试</li>
     *   <li>{@code syncStatus__c = 3}（同步失败）</li>
     *   <li>{@code syncDescription__c} = SAP 报错原因</li>
     *   <li>{@code statusDate__c} = 当前时间</li>
     * </ul>
     *
     * <p><b>容错</b>：状态回写与通知发送各自独立 try-catch，任一失败只记日志，
     * 不抛异常（FutureTask 抛异常会被平台反复重试，状态已经改过了没必要重试）。</p>
     *
     * @param quote    报价单主记录（用于取账户、所有人、单号）
     * @param quoteId  报价单 id
     * @param errMsg   SAP 返回的报错原因（已 trim，为空时用兜底文案）
     */
    private void handleTransferFailed(Quote__c quote, Long quoteId, String errMsg) {
        String msg = trimToNull(errMsg) == null ? "SAP 转单失败" : errMsg.trim();

        // 1) 回写报价单状态：回滚到"等待确认" + 同步失败 + 记录 SAP 报错原因
        try {
            Quote__c failQuote = new Quote__c();
            failQuote.setId(quoteId);
            failQuote.setQuoteStatus__c(QUOTE_STATUS_WAITING_CONFIRM);
            failQuote.setSyncStatus__c(SYNC_STATUS_FAILED);
            failQuote.setSyncDescription__c(msg);
            failQuote.setStatusDate__c(new Date().getTime());
            OperateResult r = XObjectService.instance().update(failQuote, true);
            logger.info("MYSE-04 失败状态已回写 quoteId=" + quoteId
                    + " quoteStatus__c=" + QUOTE_STATUS_WAITING_CONFIRM
                    + " syncStatus__c=" + SYNC_STATUS_FAILED
                    + " success=" + (r != null && Boolean.TRUE.equals(r.getSuccess())));
        } catch (Exception e) {
            logger.error("MYSE-04 回写失败状态异常 quoteId=" + quoteId, e);
        }

        // 2) 给报价单所有人发失败通知，内容带 SAP 报错原因
        try {
            if (MESSAGE_CENTER_BUSI_TYPE == null) {
                logger.warn("MYSE-04 未配置 MessageCenter 业务类型，跳过失败通知（状态已回写）quoteId=" + quoteId);
                return;
            }
            //当前操作人
            Long ownerId = ScriptRuntimeContext.instance().getUserId();
            Long accountId = quote == null ? null : quote.getPurchaserId__c();
            String quoteNumber = quote == null ? "" : nullSafe(quote.getQuoteNumberAutoGenerate__c());
            if (ownerId == null || accountId == null) {
                logger.warn("MYSE-04 报价单所有人或账户为空，跳过失败通知 quoteId=" + quoteId
                        + " ownerId=" + ownerId + " accountId=" + accountId);
                return;
            }
            String title = "【转单失败】报价单:" + quoteNumber + " 转单失败";
            String content = "报价单 " + quoteNumber + " 同步 SAP 转单失败。\nSAP 返回原因：" + msg;

            MessageCenter__c mc = new MessageCenter__c();
            mc.setEntityType(MESSAGE_CENTER_BUSI_TYPE);
            // name（标题）为必填字段，不填会导致消息记录插入失败
            mc.setName(title);
            mc.setAccount__c(accountId);
            mc.setPublishTime__c(new Date().getTime());
            mc.setContent__c(content);
            mc.setMessageType__c(MSG_TYPE_TODO);
            mc.setDataId__c(quoteNumber);
            mc.setObjectKey__c(quoteNumber);
            mc.setOwnerId(ownerId);
            OperateResult nr = XObjectService.instance().insert(mc, true);
            if (nr == null || !Boolean.TRUE.equals(nr.getSuccess())) {
                logger.warn("MYSE-04 失败通知写入失败 quoteId=" + quoteId
                        + " err=" + (nr == null ? "null" : nr.getErrorMessage()));
            } else {
                logger.info("MYSE-04 失败通知已发送 quoteId=" + quoteId
                        + " ownerId=" + ownerId + " title=" + title + " 内容=" + content);
            }
        } catch (Exception e) {
            logger.error("MYSE-04 发送失败通知异常 quoteId=" + quoteId, e);
        }
    }

    /**
     * 构建 SAP 转单接口报文（结构沿用原 build，仅把占位假值替换为报价单真实数据）
     *
     * @param quote          报价单主记录
     * @param details        明细列表（JSONObject，含全部字段）
     * @param productNameMap 产品 id → productName 映射（物料号 MATNR）
     * @param kunnr          客户代码（account.kunnr__c），用作售达方/送达方
     */
    private static JSONObject build(Quote__c quote, List<JSONObject> details,
                                    Map<Long, String> productNameMap, String kunnr) {
        JSONObject root = new JSONObject();

        // ===================== 顶层字段 =====================
        root.put("CUSTOMERID", nullSafe(kunnr));                                       // 售达方客户代码 → account.kunnr__c
        root.put("QUOTATION", nullSafe(quote.getAttribute("quoteNumberAutoGenerate__c")));         // 报价单号(SAP) → quoteNumberAutoGenerate__c

        if("ESPA".equals(quote.getPmsFlag__c())){ //是特价申请单
            root.put("PONUMBER",quote.getPmsId__c());
            if(StringUtils.isNotBlank(quote.getToPurchaseOrder__c())) {// 采购单编号
                root.put("CUSTOMER_PO",quote.getToPurchaseOrder__c());
            }else{
                root.put("CUSTOMER_PO",quote.getPurchaseOrder__c());
            }
        }else{
            if(StringUtils.isNotBlank(quote.getToPurchaseOrder__c())) {// 采购单编号
                root.put("PONUMBER",quote.getToPurchaseOrder__c());
            }else{
                root.put("PONUMBER",quote.getPurchaseOrder__c());
            }
        }

        root.put("REQUIREDDELIVERYDATE", "");                                          // 期望发货日期（头级暂无，留空）
        root.put("REQ_DATE", "");                                                      // 请求日期（暂空）
        root.put("REQ_TIME", "");                                                      // 请求时间（暂空）
        root.put("SHIPTOPARTY", nullSafe(quote.getShipToCode__c()));                                      // 送达方客户代码 → account.kunnr__c
        root.put("YYSTAGE", "");                                                       // 业务阶段（暂空）

        // ===================== ZITEMDEVDAT（明细行）=====================
        JSONArray zItemDevDatItems = new JSONArray();
        for (JSONObject d : details) {
            JSONObject zItemDevDatItem = new JSONObject();
            Long productId = d.getLong("catalogNumber__c");
            zItemDevDatItem.put("POSNR", toInt(d.get("posicion__c")));                  // 行号 → posicion__c
            zItemDevDatItem.put("MATNR", productNameMap.getOrDefault(productId, ""));   // 物料号 → productName
            zItemDevDatItem.put("EDATU", formatDateSAP(d.getLong("requestedDeliveryDate__c"))); // 交货日期
            zItemDevDatItem.put("YYCRITICAL", "");
            zItemDevDatItem.put("DLV_GROUP", "000");
            zItemDevDatItem.put("ZTESTREPORT", "");
            zItemDevDatItem.put("CUSTOMERID", nullSafe(padKunnr(d.getString("shipToCode__c"))));
            zItemDevDatItems.add(zItemDevDatItem);
        }
        JSONObject zItemDevDat = new JSONObject();
        zItemDevDat.put("item", zItemDevDatItems);
        root.put("ZITEMDEVDAT", zItemDevDat);

        return root;
    }

    // ===================== 辅助查询方法 =====================

    /**
     * 批量反查产品名称：catalogNumber__c(product id) → product.productName
     */
    private Map<Long, String> queryProductNames(List<JSONObject> details) {
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
            logger.warn("MYSE-04 反查产品名称失败", e);
        }
        return map;
    }

    /**
     * 查客户代码：account.id → kunnr__c（SAP 客户编号）
     */
    private String queryKunnr(Long accountId) {
        if (accountId == null) return "";
        try {
            String sql = "SELECT id, kunnr__c FROM account WHERE id = " + accountId + " LIMIT 1";
            QueryResult<JSONObject> res = XoqlService.instance().query(sql);
            if (res != null && res.getRecords() != null && !res.getRecords().isEmpty()) {
                JSONObject row = (JSONObject) res.getRecords().get(0);
                return row.getString("kunnr__c");
            }
        } catch (Exception e) {
            logger.warn("MYSE-04 查客户代码失败 accountId=" + accountId, e);
        }
        return "";
    }

    // ===================== 工具方法 =====================

    private static Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }
}
