package other.xsy.myse.apis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.XoqlService;
import other.xsy.myse.utils.HttpUtil;
import other.xsy.myse.utils.PiConfig;

/**
 * MYSE-36 客户数据推送
 * <p>
 * mySE 将客户启用状态推送至 SAP ERP。
 * 方向：mySE → PI → SAP
 * </p>
 *
 * <p><b>销售易 API 路径</b>：/rest/data/v2.0/scripts/api/myse/customer/push</p>
 *
 * <p><b>业务说明</b>：前端传客户对象 id 和销售组织 id（salesOrgId），
 * 后端按 id 查 account 取 kunnr__c 填入 SAP 入参 KUNNR（客户ID）、
 * isEnable__c 映射 ACTIVE（启用传 X，否则传空），
 * 再按 salesOrgId 查 saleorg__c.name（销售组织编码）填入 VKORG。</p>
 *
 * <p><b>SAP 入参示例</b>：</p>
 * <pre>
 * {
 *   "IT_DATA": {
 *     "item": {
 *       "KUNNR": "658216",
 *       "VKORG": "SP01",
 *       "ACTIVE": "X"
 *     }
 *   }
 * }
 * </pre>
 */
@RestApi(baseUrl = "/myse")
public class Myse36CustomerPushApi {

    private static final Logger logger = LoggerFactory.getLogger();

    private static  String PI_URL ;

    private static  String API_TOKEN;

    /** 延迟加载 PI 配置（不在 static 块里做 SDK 查询，避免类初始化阶段类加载失败）。 */
    private static void ensureConfigLoaded() {
        if (PI_URL == null) {
            PiConfig.Entry c = PiConfig.get("MYSE-36");
            if (c != null) {
                PI_URL = c.getUrl();
                API_TOKEN = c.getToken();
            }
        }
    }

    @RestMapping(value = "/customer/push", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name="json") String body) {
        try {
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            JSONObject requestBody = JSON.parseObject(body);

            // ===== 1) 前端传客户对象 id 和销售组织 id =====
            Long accountId = requestBody.getLong("id");
            Long salesOrgId = requestBody.getLong("salesOrgId");
            if (accountId == null) {
                return HttpUtil.fail("PARAM_ERROR", "客户id(id)不能为空").toJSONString();
            }
            if (salesOrgId == null) {
                return HttpUtil.fail("PARAM_ERROR", "销售组织id(salesOrgId)不能为空").toJSONString();
            }
            logger.info("MYSE-36 客户数据推送请求 accountId=" + accountId + ", salesOrgId=" + salesOrgId);

            return pushCustomer(accountId, salesOrgId);
        } catch (Exception e) {
            logger.error("MYSE-36 客户数据推送异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    /**
     * 推送单个客户到 SAP（供 REST 接口与 account trigger 复用）。
     *
     * <p>返回 success 或 fail 的 JSON 字符串；trigger 侧按是否含 {@code "success":false} / code 判断成败。</p>
     *
     * @param accountId  客户对象 id
     * @param salesOrgId 销售组织 id（saleorg__c）
     * @return success/fail JSON 字符串
     */
    public static String pushCustomer(Long accountId, Long salesOrgId) {
        try {
            ensureConfigLoaded();
            if (accountId == null) {
                return HttpUtil.fail("PARAM_ERROR", "客户id(id)不能为空").toJSONString();
            }
            if (salesOrgId == null) {
                return HttpUtil.fail("PARAM_ERROR", "销售组织id(salesOrgId)不能为空").toJSONString();
            }

            // ===== 2) 查客户：kunnr__c（客户ID）、isEnable__c（是否启用） =====
            JSONObject account = queryAccount(accountId);
            if (account == null) {
                return HttpUtil.fail("PARAM_ERROR", "客户不存在 accountId=" + accountId).toJSONString();
            }
            String kunnr = nullSafe(account.getString("kunnr__c"));
            if (kunnr.isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "客户未维护客户ID(kunnr__c)，无法推送").toJSONString();
            }
            String active = isTruthy(account.get("isEnable__c")) ? "X" : "";

            // ===== 3) 查销售组织编码（salesOrgId → saleorg__c.name） =====
            String vkorg = querySalesOrgName(salesOrgId);
            if (vkorg.isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "销售组织不存在 salesOrgId=" + salesOrgId).toJSONString();
            }

            // ===== 4) 组装 SAP 入参 =====
            JSONObject itData = new JSONObject();
            itData.put("item", buildItem(kunnr, vkorg, active));
            JSONObject sapPayload = new JSONObject();
            sapPayload.put("IT_DATA", itData);
            logger.info("MYSE-36 SAP报文: " + sapPayload.toJSONString());

            // ===== 5) 出站调用 SAP PI =====
            JSONObject piResponse = HttpUtil.post(PI_URL, sapPayload, API_TOKEN);
            logger.info("MYSE-36 piResponse: " + piResponse.toJSONString());

            // ===== 6) 调用失败（含网关/出站异常） =====
            if (piResponse == null || piResponse.containsKey("error")) {
                String errMsg = piResponse == null ? "SAP PI 返回为空" : piResponse.getString("error");
                logger.error("MYSE-36 SAP PI 调用失败: " + errMsg);
                return HttpUtil.fail("OUTBOUND_ERROR", "SAP PI 调用失败: " + errMsg).toJSONString();
            }

            // ===== 6.5) SAP 业务失败（RETURNCODE=E） =====
            String returnCode = piResponse.getString("RETURNCODE");
            if ("E".equalsIgnoreCase(returnCode)) {
                String errMsg = piResponse.getString("RETURNMESSAGE");
                logger.error("MYSE-36 SAP返回失败: " + piResponse.toJSONString());
                return HttpUtil.fail("PI_ERROR",
                        (errMsg == null || errMsg.isEmpty()) ? "SAP 客户推送失败" : errMsg).toJSONString();
            }

            return HttpUtil.success(piResponse).toJSONString();
        } catch (Exception e) {
            logger.error("MYSE-36 客户数据推送异常 accountId=" + accountId + ", salesOrgId=" + salesOrgId, e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    /**
     * 查客户：account.id → kunnr__c / isEnable__c。
     */
    private static JSONObject queryAccount(Long accountId) {
        try {
            String sql = "SELECT id, kunnr__c, isEnable__c FROM account WHERE id = " + accountId + " LIMIT 1";
            QueryResult<JSONObject> res = XoqlService.instance().query(sql);
            if (res != null && res.getRecords() != null && !res.getRecords().isEmpty()) {
                return (JSONObject) res.getRecords().get(0);
            }
        } catch (Exception e) {
            logger.error("MYSE-36 查询客户失败 accountId=" + accountId, e);
        }
        return null;
    }

    /**
     * 查销售组织编码：salesOrgId → saleorg__c.name（VKORG，如 SP01）。
     */
    private static String querySalesOrgName(Long salesOrgId) {
        try {
            String sql = "SELECT id, name FROM saleorg__c WHERE id = " + salesOrgId + " LIMIT 1";
            QueryResult<JSONObject> res = XoqlService.instance().query(sql);
            if (res != null && res.getRecords() != null && !res.getRecords().isEmpty()) {
                JSONObject row = (JSONObject) res.getRecords().get(0);
                return nullSafe(row.getString("name"));
            }
        } catch (Exception e) {
            logger.error("MYSE-36 查询销售组织失败 salesOrgId=" + salesOrgId, e);
        }
        return "";
    }

    /**
     * 组装单条 SAP item：KUNNR（客户ID）、VKORG（销售组织）、ACTIVE（启用X/停用空）。
     */
    private static JSONObject buildItem(String kunnr, String vkorg, String active) {
        JSONObject item = new JSONObject();
        item.put("KUNNR", kunnr);       // 客户ID → account.kunnr__c
        item.put("VKORG", vkorg);       // 销售组织 → saleorg__c.name
        item.put("ACTIVE", active);     // 有效状态 → account.isEnable__c（启用传X，否则传空）
        return item;
    }

    /** isEnable__c 布尔值兼容判断（Boolean / "true" / "1" / "X"）。 */
    private static boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        String s = value.toString().trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "X".equalsIgnoreCase(s);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s.trim();
    }
}
