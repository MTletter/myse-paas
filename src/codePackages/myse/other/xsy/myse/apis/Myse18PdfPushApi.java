package other.xsy.myse.apis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.data.model.Delivery__c;
import com.rkhd.platform.sdk.data.model.Order__c;
import com.rkhd.platform.sdk.data.model.ProformaInvoice__c;
import com.rkhd.platform.sdk.data.model.Quote__c;
import com.rkhd.platform.sdk.exception.ApiEntityServiceException;
import com.rkhd.platform.sdk.exception.CustomConfigException;
import com.rkhd.platform.sdk.http.RkhdBlob;
import com.rkhd.platform.sdk.http.RkhdHttpClient;
import com.rkhd.platform.sdk.http.RkhdHttpData;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.model.XObject;
import com.rkhd.platform.sdk.service.CustomConfigService;
import com.rkhd.platform.sdk.service.XObjectService;
import other.xsy.myse.utils.HttpUtil;
import other.xsy.myse.utils.PdfDecoder;

import java.io.IOException;
import java.util.*;

/**
 * MYSE-18 推送 PDF 文档
 * <p>
 * 接收 SAP 推送的 PDF 文档（报价单、订单、形式发票、交货单）。
 * 方向：SAP → mySE（被动接收）
 * </p>
 *
 * <p><b>销售易 API 路径</b>：/rest/data/v2.0/scripts/api/myse/document/pdf</p>
 *
 * <p><b>处理流程</b>：
 * <ol>
 *   <li>解析 base64 报文 → 业务单元(bu) / 单据类型(dt) / 单据号(dk) / 时间戳(ts) / PDF 字节</li>
 *   <li>PDF 上传文件服务拿 fileId</li>
 *   <li>按单据类型定位业务记录：<b>查不到则先创建一条只带编号的空单据，再把附件挂上去</b></li>
 * </ol>
 * 单据类型：Q=报价单 / P=订单 / I=形式发票 / D=交货单
 * </p>
 *
 * <p><b>日志规范</b>：入参（超长截断）+ 出参各一条，每次 XOQL 打完整 SQL，
 * 创建/挂载打关键 id，不打中间对象全量。</p>
 */
@RestApi(baseUrl = "/myse")
public class Myse18PdfPushApi {

    private static final Logger logger = LoggerFactory.getLogger();

    private static final String TAG = "MYSE-18";

    /** 入参日志最大长度：PDF base64 很长，超出截断 */
    private static final int MAX_LOG_LEN = 1000;

    /** 各单据业务类型（entityType），取自租户元数据 busiTypeId */
    private static final Long ORDER_BUSI_TYPE;
    private static final Long QUOTE_BUSI_TYPE;
    private static final Long PROFORMA_INVOICE_BUSI_TYPE;
    private static final Long DELIVERY_BUSI_TYPE;

    static {
        try {
            // 获取对象实例
            CustomConfigService instance = CustomConfigService.instance();
            // 获取配置集,返回此配置集中的所有配置项
            Map<String, String> configSet = instance.getConfigSet("entityType");
            ORDER_BUSI_TYPE = Long.parseLong(configSet.get("ORDER_BUSI_TYPE"));
            QUOTE_BUSI_TYPE = Long.parseLong(configSet.get("QUOTE_BUSI_TYPE"));
            PROFORMA_INVOICE_BUSI_TYPE = Long.parseLong(configSet.get("PROFORMA_INVOICE_BUSI_TYPE"));
            DELIVERY_BUSI_TYPE = Long.parseLong(configSet.get("DELIVERY_BUSI_TYPE"));
        } catch (CustomConfigException e) {
            throw new RuntimeException(e);
        }
    }

    @RestMapping(value = "/document/pdf", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        String resp;
        try {
            logger.info(TAG + " 入参: " + (body != null && body.length() > MAX_LOG_LEN
                    ? body.substring(0, MAX_LOG_LEN) + "...(len=" + body.length() + ")" : body));
            resp = doPush(body);
        } catch (Exception e) {
            logger.error(TAG + " 处理异常: " + e.getMessage(), e);
            resp = HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
        logger.info(TAG + " 出参: " + resp);
        return resp;
    }

    private String doPush(String body) throws Exception {
        if (body == null || body.isEmpty()) {
            return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
        }
        JSONObject file = JSON.parseObject(body);

        PdfDecoder.ParsedInput data = PdfDecoder.decodeWithParams(file.getString("file"));
        String bu = data.getBusinessUnit();   // "MySECn"
        String dt = data.getDocumentType();   // "P"
        String dk = data.getDocumentKey();    // "2003598901"
        String ts = data.getTimestamp();      // "2012041:120000"
        byte[] pdf = data.getPdf();           // 真·PDF 字节
        logger.info(TAG + " 解析结果: bu=" + bu + " dt=" + dt + " dk=" + dk
                + " ts=" + ts + " pdfSize=" + (pdf == null ? 0 : pdf.length));

        // ===== 1. PDF 上传文件服务 =====
        Long fileId = uploadPdf(dk, pdf);

        // ===== 2. 按单据类型挂载（查不到则先创建空单据）=====
        if (dt.startsWith("Q")) {
            attachQuote(dk, fileId);
        } else if (dt.startsWith("P")) {
            if("P_ZBAP".equals(dt)) {
                attachOrder(dk, fileId, "prePayFile__c");
            }else {
                attachOrder(dk, fileId, "orderFile__c");
            }
        } else if (dt.startsWith("I")) {
            attachProformaInvoice(dk, fileId);
        } else if (dt.startsWith("D")) {
            attachDelivery(dk, fileId);
        } else {
            logger.warn(TAG + " 未知单据类型，附件未挂载 dt=" + dt + " dk=" + dk + " fileId=" + fileId);
        }
        return HttpUtil.success("PDF document push processed").toJSONString();
    }

    // ===================== PDF 上传 =====================

    private Long uploadPdf(String dk, byte[] pdf) throws IOException {
        RkhdBlob rkhdBlob = new RkhdBlob();
        rkhdBlob.setFileName(dk + ".pdf");
        rkhdBlob.setBytes(pdf);

        RkhdHttpData rkhdData = new RkhdHttpData();
        rkhdData.setCall_type("POST");
        rkhdData.setCallString("/rest/file/v2.0/file/batch");
        rkhdData.putFormData("files", rkhdBlob);
        rkhdData.putFormData("isImage", false);
        rkhdData.putFormData("needFileId", true);
        rkhdData.putHeader("Content-Type", "multipart/form-data");

        String response = RkhdHttpClient.instance().performRequest(rkhdData);
        JSONObject json = JSONObject.parseObject(response);
        Long fileId = json.getJSONArray("result").getJSONObject(0).getLong("fileId");
        logger.info(TAG + " 附件上传成功 fileId=" + fileId + " fileName=" + dk + ".pdf");
        return fileId;
    }

    // ===================== 各单据挂载 =====================

    /** 报价单：按 quoteNumberAutoGenerate__c 定位，不存在则创建 */
    private void attachQuote(String dk, Long fileId) throws Exception {
        String sql = "select id from quote__c where quoteNumberAutoGenerate__c='" + dk + "'";
        logger.info(TAG + " SQL: " + sql);
        Long id = firstId(XObjectService.instance().query(sql, true));
        if (id == null) {
            logger.info(TAG + " 报价单不存在，先创建 quoteNumber=" + dk);
            Quote__c add = new Quote__c();
            add.setEntityType(QUOTE_BUSI_TYPE);
            add.setQuoteNumberAutoGenerate__c(dk);
            id = XObjectService.instance().insert(add, true).getDataId();
            logger.info(TAG + " 报价单创建结果 id=" + id);
        }
        if (id == null) {
            logger.error(TAG + " 报价单定位失败，附件未挂载 dk=" + dk);
            return;
        }

        List<Map> fileList = new ArrayList<Map>();
        fileList.add(newFileMap(fileId));

        Quote__c up = new Quote__c();
        up.setId(id);
        up.setQuoteDocument__c(fileList);
        XObjectService.instance().update(up, true);
        logger.info(TAG + " 报价单附件挂载完成 quoteId=" + id + " fileId=" + fileId
                + " 附件数=" + fileList.size());
    }

    /** 订单：按 orderNumberAutoGenerate__c 定位，不存在则创建 */
    private void attachOrder(String dk, Long fileId,String apiKey) throws Exception {
        String sql = "select id from order__c where orderNumberAutoGenerate__c='" + dk + "'";
        logger.info(TAG + " SQL: " + sql);
        Long id = firstId(XObjectService.instance().query(sql, true));
        if (id == null) {
            logger.info(TAG + " 订单不存在，先创建 orderNumber=" + dk);
            Order__c add = new Order__c();
            add.setEntityType(ORDER_BUSI_TYPE);
            add.setOrderNumberAutoGenerate__c(dk);
            id = XObjectService.instance().insert(add, true).getDataId();
            logger.info(TAG + " 订单创建结果 id=" + id);
        }
        if (id == null) {
            logger.error(TAG + " 订单定位失败，附件未挂载 dk=" + dk);
            return;
        }

        List<Map> fileList = new ArrayList<>();

        fileList.add(newFileMap(fileId));

        Order__c up = new Order__c();
        up.setId(id);
        up.setAttribute(apiKey,fileList);
        XObjectService.instance().update(up, true);
        logger.info(TAG + " 订单附件挂载完成 orderId=" + id + " fileId=" + fileId
                + " 附件数=" + fileList.size());
    }

    /** 形式发票：按 name 定位，不存在则创建（name 即形式发票号，无其它编号字段） */
    private void attachProformaInvoice(String dk, Long fileId) throws Exception {
        String sql = "select id from proformaInvoice__c where name='" + dk + "'";
        logger.info(TAG + " SQL: " + sql);
        Long id = firstId(XObjectService.instance().query(sql, true));
        if (id == null) {
            logger.info(TAG + " 形式发票不存在，先创建 invoiceNo=" + dk);
            ProformaInvoice__c add = new ProformaInvoice__c();
            add.setEntityType(PROFORMA_INVOICE_BUSI_TYPE);
            add.setName(dk);
            id = XObjectService.instance().insert(add, true).getDataId();
            logger.info(TAG + " 形式发票创建结果 id=" + id);
        }
        if (id == null) {
            logger.error(TAG + " 形式发票定位失败，附件未挂载 dk=" + dk);
            return;
        }

        List<Map> fileList = new ArrayList<Map>();

        fileList.add(newFileMap(fileId));

        ProformaInvoice__c up = new ProformaInvoice__c();
        up.setId(id);
        up.setDocument__c(fileList);
        XObjectService.instance().update(up, true);
        logger.info(TAG + " 形式发票附件挂载完成 invoiceId=" + id + " fileId=" + fileId
                + " 附件数=" + fileList.size());
    }

    /**
     * 交货单：按 name 定位，不存在则创建。
     * 新建只写编号字段 delivery__c（发货单号），name 由平台自动生成。
     */
    private void attachDelivery(String dk, Long fileId) throws Exception {
        String sql = "select id from delivery__c where name='" + dk + "'";
        logger.info(TAG + " SQL: " + sql);
        Long id = firstId(XObjectService.instance().query(sql, true));
        if (id == null) {
            logger.info(TAG + " 交货单不存在，先创建 dn=" + dk);
            Delivery__c add = new Delivery__c();
            add.setEntityType(DELIVERY_BUSI_TYPE);
            add.setName(dk);
            id = XObjectService.instance().insert(add, true).getDataId();
            logger.info(TAG + " 交货单创建结果 id=" + id);
        }
        if (id == null) {
            logger.error(TAG + " 交货单定位失败，附件未挂载 dk=" + dk);
            return;
        }

        // Delivery__c 模型未提供 document__c 的 setter/getter，走 setAttribute 覆盖式写入
        List<Map> fileList = new ArrayList<Map>();
        fileList.add(newFileMap(fileId));

        Delivery__c up = new Delivery__c();
        up.setId(id);
        up.setAttribute("deliveryFile__c", fileList);
        XObjectService.instance().update(up, true);
        logger.info(TAG + " 交货单附件挂载完成 deliveryId=" + id + " fileId=" + fileId);
    }

    // ===================== 工具方法 =====================

    /** 取 XOQL 结果首条记录的 id；无记录返回 null */
    private Long firstId(QueryResult<?> res) {
        if (res == null || res.getRecords() == null || res.getRecords().isEmpty()) {
            return null;
        }
        Object first = res.getRecords().get(0);
        return first instanceof XObject ? ((XObject) first).getId() : null;
    }

    /** 附件引用结构：{"id": fileId} */
    private Map newFileMap(Long fileId) {
        Map map = new HashMap();
        map.put("id", fileId);
        return map;
    }

}
