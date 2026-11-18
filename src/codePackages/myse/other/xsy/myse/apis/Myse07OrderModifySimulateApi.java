package other.xsy.myse.apis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.data.model.Account;
import com.rkhd.platform.sdk.exception.ApiEntityServiceException;
import com.rkhd.platform.sdk.service.XObjectService;
import other.xsy.myse.utils.HttpUtil;
import other.xsy.myse.utils.PiConfig;

import java.util.*;

import static other.xsy.myse.utils.JsonValues.nullSafe;
import static other.xsy.myse.utils.JsonValues.trimToNull;

/**
 * MYSE-07 模拟修改销售订单（dry-run，仅校验不落库）
 * <p>
 * 调用 SAP BAPI：Z_BAPI_ORDERMODIF_VALIDATION　方向：mySE → PI → SAP
 * </p>
 *
 * <p><b>销售易 API 路径</b>：/rest/data/v2.0/scripts/api/myse/order/modify/simulate</p>
 *
 * <p><b>入参</b>：
 * <pre>
 * {
 *   "id": 1234567890123456,   // 订单(order__c) 的 id，放顶层或 main 里均可
 *   "main": { "requireDeliverydate__c": 1783821500000, "purchaseId__c": 4400183696671784 },
 *   "lines": [ { "posicion__c": 1, "requestedDeliveryDate__c": 1783821500000 } ]
 * }
 * </pre>
 * <b>main</b>：只传改动字段，非空字段覆盖库值，未传字段保留库里现有的。
 * <br/><b>lines 语义</b>：前端<b>只传修改过的行</b>，未修改的行不下发 SAP；不传 lines 表示本次只改表头。
 * 每个 line 至少带 id 或 posicion__c 用于匹配库行，其余只传改动字段（缺失字段自动取库值）。
 * </p>
 *
 * <p><b>明细下发两种场景</b>：
 * <table border="1">
 *   <tr><th>场景</th><th>触发条件</th><th>下发内容</th><th>CUSTOMERID 取值</th></tr>
 *   <tr><td>A 整单改发货地址</td><td>main 里同时有 <b>shipTo__c</b> 和 <b>kunnr</b>，且前端<b>不传 lines</b></td>
 *       <td>库里<b>全量明细</b></td><td>统一取 <b>main.kunnr</b></td></tr>
 *   <tr><td>B 行级修改</td><td>其余情况</td><td>只下发前端传的 lines（不传则一行不发）</td>
 *       <td>行上的 <b>kunnr</b>（前端 lines 自带；未传时回退库行 join 出的值，<b>不做额外库表反查</b>）</td></tr>
 * </table>
 * </p>
 *
 * <p><b>处理流程</b>：校验入参 → 查订单头 → 用 main 覆盖头字段 → 按 lines 合并明细 → 校验账户启用状态
 * → 封装报文 → 出站 PI → 返回 SAP 响应。</p>
 *
 * <p><b>SAP 报文结构</b>：
 * <pre>
 * {
 *   "ZHEADER_IN": {
 *     "COUNTRY": "CN",                  // 国家（固定 CN，order__c 无源字段）
 *     "ORDERNUMBER": "2000015469",      // SAP 订单号 → order__c.orderNumberAutoGenerate__c
 *     "REQUIREDDELIVERYDATE": "",       // 整单期望发货日期 → order__c.requireDeliverydate__c
 *     "MODIFICATION": ""                // 修改标识（无源，留空）
 *   },
 *   "ZITEM_IN": {
 *     "item": [
 *       {
 *         "ITEM_NUMBER": "000010",      // 行号 → orderDetail__c.posicion__c
 *         "MODIFICATION_STATUS": "U",   // 行修改状态（固定 U=修改）
 *         "REQUIREDDELIVERYDATE": "",   // 行期望发货日期 → orderDetail__c.requestedDeliveryDate__c
 *         "CUSTOMERID": ""              // 客户代码 → 明细发货地址信息(customerId__c).kunnr__c
 *       }
 *     ]
 *   }
 * }
 * </pre>
 * </p>
 *
 * <p>报文构建 / 内部 XOQL / 明细摘要 / 日志工具见 {@link OrderModifyApiBase}（与 MYSE-08 共用）。</p>
 */
@RestApi(baseUrl = "/myse")
public class Myse07OrderModifySimulateApi extends OrderModifyApiBase {

    /** 日志标识 */
    private static final String TAG = "MYSE-07";

    private static String PI_URL;
    private static String API_TOKEN;

    @Override
    protected String tag() {
        return TAG;
    }

    /** 延迟加载 PI 配置（不在 static 块里做 SDK 查询，避免类初始化阶段类加载失败）。 */
    private void ensureConfigLoaded() {
        if (PI_URL == null) {
            PiConfig.Entry c = PiConfig.get("MYSE-07");
            if (c != null) {
                PI_URL = c.getUrl();
                API_TOKEN = c.getToken();
            }
        }
    }

    // ===================== 接口入口 =====================

    @RestMapping(value = "/order/modify/simulate", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        long start = System.currentTimeMillis();
        try {
            if (body == null || body.isEmpty()) {
                logger.warn(TAG + " 请求体为空，忽略");
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }
            logger.info(TAG + " 入参: " + abbreviate(body));

            JSONObject req = JSON.parseObject(body);
            ensureConfigLoaded();

            // 入参 main（前端草稿，只含改动字段；id 可能放 main 里，也可能放顶层）
            JSONObject main = req.getJSONObject("main");

            // 1) 订单 id（必填）
            Long orderId = toLong(req.get("id"));
            if (orderId == null && main != null) {
                orderId = toLong(main.get("id"));
            }
            if (orderId == null) {
                logger.warn(TAG + " 参数校验失败: 订单id(id)缺失或非法, 入参=" + abbreviate(body));
                return HttpUtil.fail("PARAM_ERROR", "订单id(id)不能为空").toJSONString();
            }
            if (PI_URL == null || PI_URL.trim().isEmpty()) {
                logger.error(TAG + " 配置缺失: systemConfig__c(MYSE-07) 未配置 PI 地址, orderId=" + orderId);
                return HttpUtil.fail("CONFIG_ERROR", "未找到 MYSE-07 的 systemConfig 配置").toJSONString();
            }
            logger.info(TAG + " 参数校验通过: orderId=" + orderId
                    + ", main字段数=" + (main == null ? 0 : main.size())
                    + ", lines行数=" + (req.getJSONArray("lines") == null ? 0 : req.getJSONArray("lines").size()));

            // 2) 加载订单主记录（内部 XOQL，绕过 API 上下文对象级权限隔离，按字段名读取避免缺 getter 编译问题）
            JSONObject order = loadOrder(orderId);
            if (order == null) {
                logger.warn(TAG + " 订单不存在, order__c id=" + orderId);
                return HttpUtil.fail("ORDER_NOT_FOUND", "未找到订单 order__c id=" + orderId).toJSONString();
            }

            // 2.1) 前端传了 main 时，以前端字段为准覆盖库值，未传字段保持库里现有的
            if (main != null && !main.isEmpty()) {
                normalizeLookup(main, "purchaseId__c");
                order = mergeJson(order, main);
                logger.info(TAG + " 已合并 main: main字段数=" + main.size()
                        + ", 订单号=" + nullSafe(order.getString("orderNumberAutoGenerate__c"))
                        + ", 客户=" + toLong(order.get("purchaseId__c")));
            }

            // 3) 加载订单明细
            List<JSONObject> dbDetails = loadOrderDetails(orderId);
            JSONArray linesArr = req.getJSONArray("lines");

            // 3.1) 场景 A：main 同时带了发货地址 id(shipTo__c) 和发货地址代码(kunnr)，且前端未传 lines
            //      → 本次是「整单换发货地址」，明细不用前端传，直接下发库里全量行，
            //        且每行的 CUSTOMERID 统一取 main.kunnr（不再用各行自己的地址代码）
            String mainKunnr = main == null ? null : trimToNull(main.getString("kunnr"));
            Long mainShipToId = main == null ? null : toLong(main.get("shipTo__c"));
            boolean shipToOnlyChange = mainKunnr != null && mainShipToId != null
                    && (linesArr == null || linesArr.isEmpty());

            List<JSONObject> details;
            if (shipToOnlyChange) {
                details = new ArrayList<>();
                for (JSONObject d : dbDetails) {
                    if (d != null) {
                        d.put("kunnr", mainKunnr);   // CUSTOMERID 统一用 main 里的发货地址代码
                        details.add(d);
                    }
                }
                logger.info(TAG + " 场景[整单改发货地址]: main.shipTo__c=" + mainShipToId + ", main.kunnr=" + mainKunnr
                        + ", 前端未传 lines → 下发库里全量明细 " + details.size() + " 行，CUSTOMERID 统一取 main.kunnr");
            } else {
                // 3.2) 场景 B：常规行级修改 —— 只下发前端传的行
                //      命中库行 → 字段级覆盖（前端没带的字段保留库值）；未命中 → 视为新行；前端没传 → 一行都不发
                details = mergeDetails(dbDetails, linesArr);
                logger.info(TAG + " 场景[行级修改]: 待下发明细(" + details.size() + "/" + (linesArr == null ? 0 : linesArr.size())
                        + "，库=" + dbDetails.size() + "): " + describeDetails(details));
            }

            // 3.3) CUSTOMERID 直接取行上的 kunnr（前端 lines 自带），不做库表反查
            warnIfKunnrMissing(details);

            // 4) 账户启用状态校验（停用账户不允许做订单修改）
            String accountCheck = checkAccountEnabled(order);
            if (accountCheck != null) {
                return accountCheck;
            }

            // 5) 封装 SAP 报文
            JSONObject payload = build(order, details);
            logger.info(TAG + " SAP报文: " + abbreviate(payload.toJSONString()));

            // 6) 出站调用 SAP PI
            JSONObject piResponse = HttpUtil.post(PI_URL, payload, API_TOKEN);
            if (piResponse == null) {
                logger.error(TAG + " SAP PI 未返回响应, orderId=" + orderId + ", 下发行数=" + details.size());
                return HttpUtil.fail("SAP_NO_RESPONSE", "SAP 未返回响应，请稍后重试").toJSONString();
            }

            String respText = piResponse.toString();
            logger.info(TAG + " 模拟修改校验完成: orderId=" + orderId + ", 下发行数=" + details.size()
                    + ", 耗时=" + (System.currentTimeMillis() - start) + "ms"
                    + ", SAP响应=" + abbreviate(respText));
            return respText;

        } catch (Exception e) {
            logger.error(TAG + " 执行异常, 耗时=" + (System.currentTimeMillis() - start) + "ms", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    // ===================== 账户校验 =====================

    /**
     * 校验订单所属账户是否启用。
     *
     * @return null = 校验通过可继续；非 null = 需要直接返回给前端的错误报文
     */
    private String checkAccountEnabled(JSONObject order) throws ApiEntityServiceException {
        Long accountId = toLong(order.get("purchaseId__c"));
        if (accountId == null) {
            logger.warn(TAG + " 订单未关联客户(purchaseId__c 为空)，跳过账户启用校验");
            return null;
        }
        Account stub = new Account();
        stub.setId(accountId);
        Account account = XObjectService.instance().get(stub, true);
        if (account == null) {
            logger.warn(TAG + " 未找到账户 account id=" + accountId + "，无法校验启用状态");
            return HttpUtil.fail("ACCOUNT_NOT_FOUND", "未找到订单所属客户").toJSONString();
        }
        Boolean enabled = account.getIsEnable__c();
        if (enabled == null) {
            // 字段未维护：按启用处理，避免历史数据被误拦（会打 warn 便于排查）
            logger.warn(TAG + " 账户 isEnable__c 未维护，按启用处理: accountId=" + accountId);
            return null;
        }
        if (!enabled) {
            logger.warn(TAG + " 账户未启用，不允许操作: accountId=" + accountId);
            JSONObject resp = new JSONObject();
            resp.put("RETURNCODE", "401");
            resp.put("RETURNMESSAGE", "您的账户权限已被停用");
            return resp.toString();
        }
        logger.info(TAG + " 账户启用校验通过: accountId=" + accountId);
        return null;
    }

    // ===================== 数据加载（内部 XOQL，系统鉴权）=====================

    /** 查订单头：SAP 订单号 + 整单期望发货日期 + 客户（供账户启用校验）。 */
    private JSONObject loadOrder(Long orderId) {
        JSONArray recs = queryInternal(
                "SELECT id, orderNumberAutoGenerate__c, requireDeliverydate__c, purchaseId__c"
                        + " FROM order__c WHERE id = " + orderId + " LIMIT 1");
        if (recs != null && !recs.isEmpty()) {
            return recs.getJSONObject(0);
        }
        return null;
    }

    /** 查订单全部明细（作为 lines 合并时的库值兜底）。 */
    private List<JSONObject> loadOrderDetails(Long orderId) {
        JSONArray recs = queryInternal(
                "SELECT id, posicion__c, requestedDeliveryDate__c, quantity__c, "
                        + "catelogNumber__c, shipToAddress__c, customerId__c.kunnr__c kunnr"
                        + " FROM orderDetail__c WHERE order__c = " + orderId);
        if (recs == null || recs.isEmpty()) {
            return Collections.emptyList();
        }
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < recs.size(); i++) {
            list.add(recs.getJSONObject(i));
        }
        return list;
    }

    // ===================== 前端草稿合并 =====================

    /**
     * 字段级合并：以库记录为底，前端传来的非空字段覆盖；前端未传/传 null 的字段保持库值。
     */
    private static JSONObject mergeJson(JSONObject base, JSONObject override) {
        JSONObject merged = base == null ? new JSONObject() : (JSONObject) base.clone();
        if (override == null) {
            return merged;
        }
        for (Map.Entry<String, Object> e : override.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                merged.put(e.getKey(), e.getValue());
            }
        }
        return merged;
    }

    /**
     * 明细合并（<b>只下发前端传来的行</b>）：优先按明细 id 匹配库行，其次按行号 posicion__c。
     * <ul>
     *   <li>前端传了、库里有 → 字段级覆盖（前端没带的字段保留库值，如 posicion__c / catelogNumber__c / quantity__c / kunnr）</li>
     *   <li>前端传了、库里没有 → 视为新行，直接用前端行</li>
     *   <li><b>库里有、前端没传 → 不下发</b>（前端只会传修改过的行，未修改的行不发给 SAP）</li>
     *   <li>前端没传 lines → 一行都不发（本次只改表头）</li>
     * </ul>
     */
    private static List<JSONObject> mergeDetails(List<JSONObject> dbList, JSONArray lines) {
        List<JSONObject> result = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            return result;   // 前端未传行 → 不发任何明细行
        }
        List<JSONObject> db = dbList == null ? Collections.emptyList() : dbList;
        // 库明细按 id 建索引（id 优先匹配）
        Map<Long, JSONObject> byId = new LinkedHashMap<>();
        // 库明细按行号建索引（id 匹配不上时兜底）
        Map<String, JSONObject> byPos = new LinkedHashMap<>();
        for (JSONObject d : db) {
            if (d == null) {
                continue;
            }
            Long id = toLong(d.get("id"));
            if (id != null) {
                byId.put(id, d);
            }
            String p = trimToNull(d.getString("posicion__c"));
            if (p != null) {
                byPos.put(p, d);
            }
        }

        for (int i = 0; i < lines.size(); i++) {
            JSONObject line = lines.getJSONObject(i);
            if (line == null) {
                continue;
            }
            JSONObject base = null;
            Long id = toLong(line.get("id"));
            if (id != null && byId.containsKey(id)) {
                base = byId.remove(id);
                String p = trimToNull(base.getString("posicion__c"));
                if (p != null) {
                    byPos.remove(p);
                }
            } else {
                String p = trimToNull(line.getString("posicion__c"));
                if (p != null && byPos.containsKey(p)) {
                    base = byPos.remove(p);
                    Long baseId = toLong(base.get("id"));
                    if (baseId != null) {
                        byId.remove(baseId);
                    }
                }
            }
            if (base == null) {
                logger.warn(TAG + " 明细行未匹配到库记录，按新增行下发: 行号="
                        + trimToNull(line.getString("posicion__c")) + ", 前端id=" + id);
            }
            result.add(base == null ? line : mergeJson(base, line));
        }
        return result;
    }

    /**
     * 下发前检查：行上没有 kunnr（客户代码）时打 warn —— CUSTOMERID 会下发空串，SAP 侧大概率会报错。
     * 正常场景前端会在 lines 里自带 kunnr；场景 A（整单改发货地址）由 main.kunnr 统一覆盖。
     */
    private void warnIfKunnrMissing(List<JSONObject> details) {
        if (details == null || details.isEmpty()) {
            return;
        }
        for (JSONObject d : details) {
            if (d == null) {
                continue;
            }
            if (trimToNull(d.getString("kunnr")) == null) {
                logger.warn(TAG + " 明细行缺客户代码(kunnr)，CUSTOMERID 将下发空串: id=" + toLong(d.get("id"))
                        + ", 行号=" + nullSafe(d.getString("posicion__c")));
            }
        }
    }

    /** lookup 字段可能是 {"id":..} 结构，统一规整为标量 id。 */
    private static void normalizeLookup(JSONObject o, String key) {
        if (o == null || key == null) {
            return;
        }
        Object v = o.get(key);
        if (v instanceof JSONObject) {
            Object id = ((JSONObject) v).get("id");
            if (id != null) {
                o.put(key, id);
            }
        }
    }
}