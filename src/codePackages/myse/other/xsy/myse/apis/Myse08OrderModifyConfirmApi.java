package other.xsy.myse.apis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import other.xsy.myse.utils.HttpUtil;
import other.xsy.myse.utils.PiConfig;

import java.util.*;

/**
 * MYSE-08 确认修改销售订单（落库 SAP）
 * <p>
 * 调用 SAP BAPI：Z_BAPI_ORDERMODIF_RELEASE　方向：mySE → PI → SAP
 * </p>
 *
 * <p><b>销售易 API 路径</b>：/rest/data/v2.0/scripts/api/myse/order/modify/</p>
 *
 * <p><b>入参</b>：
 * <pre>
 * {
 *   "id": 1234567890123456,               // 订单(order__c) 的 id，必填
 *   "lines": [ { "id": 1234331 }, { "id": 31235334 } ]   // 本次要下发的明细行(orderDetail__c)
 * }
 * </pre>
 * <b>lines 语义</b>：前端只传本次修改过的行，<b>只下发这些行</b>，未修改的行不下发 SAP；
 * 每项以元素的 <b>id</b> 作为 orderDetail__c 主键（也兼容元素直接是数字/字符串的写法）；
 * 不传或传空数组 → 只改表头，ZITEM_IN.item 为空数组；下发顺序与前端传入顺序一致。
 * </p>
 *
 * <p><b>处理流程</b>：校验入参 → 查订单头 → 按 lines 中的 id 查明细 → 封装报文 → 出站 PI → 返回 SAP 响应。</p>
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
 *         "ITEM_NUMBER": "000010",      // 行号 → orderDetail__c.posicion__c（补零到 6 位）
 *         "MODIFICATION_STATUS": "U",   // 行修改状态（固定 U=修改）
 *         "REQUIREDDELIVERYDATE": "",   // 行期望发货日期 → orderDetail__c.requestedDeliveryDate__c
 *         "CUSTOMERID": ""              // 客户代码 → orderDetail__c.customerId__c(发货地址信息).kunnr__c
 *       }
 *     ]
 *   }
 * }
 * </pre>
 * </p>
 *
 * <p>报文构建 / 内部 XOQL / 明细摘要 / 日志工具见 {@link OrderModifyApiBase}（与 MYSE-07 共用）。</p>
 */
@RestApi(baseUrl = "/myse")
public class Myse08OrderModifyConfirmApi extends OrderModifyApiBase {

    /** 日志标识 */
    private static final String TAG = "MYSE-08";

    private static String PI_URL;
    private static String API_TOKEN;

    @Override
    protected String tag() {
        return TAG;
    }

    /** 延迟加载 PI 配置（不在 static 块里做 SDK 查询，避免类初始化阶段类加载失败）。 */
    private void ensureConfigLoaded() {
        if (PI_URL == null) {
            PiConfig.Entry c = PiConfig.get("MYSE-08");
            if (c != null) {
                PI_URL = c.getUrl();
                API_TOKEN = c.getToken();
            }
        }
    }

    // ===================== 接口入口 =====================

    @RestMapping(value = "/order/modify/confirm", method = RequestMethod.POST)
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

            // 1) 订单 id（必填）
            Long orderId = toLong(req.get("id"));
            if (orderId == null) {
                logger.warn(TAG + " 参数校验失败: 订单id(id)缺失或非法, 入参=" + abbreviate(body));
                return HttpUtil.fail("PARAM_ERROR", "订单id(id)不能为空").toJSONString();
            }
            if (PI_URL == null || PI_URL.trim().isEmpty()) {
                logger.error(TAG + " 配置缺失: systemConfig__c(MYSE-08) 未配置 PI 地址, orderId=" + orderId);
                return HttpUtil.fail("CONFIG_ERROR", "未找到 MYSE-08 的 systemConfig 配置").toJSONString();
            }

            // 2) 本次要下发的明细行（可选，去重且保持前端传入顺序）
            LinkedHashSet<Long> itemIds = parseLineIds(req.get("lines"));
            logger.info(TAG + " 参数校验通过: orderId=" + orderId
                    + ", lines数=" + itemIds.size() + ", 明细id=" + abbreviate(itemIds.toString()));

            // 3) 加载订单主记录（内部 XOQL，绕过 API 上下文对象级权限隔离）
            JSONObject order = loadOrder(orderId);
            if (order == null) {
                logger.warn(TAG + " 订单不存在, order__c id=" + orderId);
                return HttpUtil.fail("ORDER_NOT_FOUND", "未找到订单 order__c id=" + orderId).toJSONString();
            }

            // 4) 只加载前端指定的明细行（未修改的行不下发 SAP）
            List<JSONObject> details = loadOrderDetails(orderId, itemIds);
            logger.info(TAG + " 待下发明细(" + details.size() + "/" + itemIds.size() + "): " + describeDetails(details));

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
            logger.info(TAG + " 确认修改完成: orderId=" + orderId + ", 下发行数=" + details.size()
                    + ", 耗时=" + (System.currentTimeMillis() - start) + "ms"
                    + ", SAP响应=" + abbreviate(respText));
            return respText;

        } catch (Exception e) {
            logger.error(TAG + " 执行异常, 耗时=" + (System.currentTimeMillis() - start) + "ms", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    // ===================== 数据加载（内部 XOQL，系统鉴权）=====================

    /** 查订单头：SAP 订单号 + 整单期望发货日期。 */
    private JSONObject loadOrder(Long orderId) {
        JSONArray recs = queryInternal(
                "SELECT id, orderNumberAutoGenerate__c, requireDeliverydate__c FROM order__c"
                        + " WHERE id = " + orderId + " LIMIT 1");
        if (recs != null && !recs.isEmpty()) {
            return recs.getJSONObject(0);
        }
        return null;
    }

    /**
     * 按明细 id 批量加载指定明细行（只加载前端传来的行）。
     * <p>
     * 附带 order__c = orderId 条件，防止误带其他订单的行；输出顺序保持前端传入顺序；
     * 查不到（不存在 / 不属于该订单）的 id 打 warn 跳过，不中断整单。
     * </p>
     */
    private List<JSONObject> loadOrderDetails(Long orderId, LinkedHashSet<Long> itemIds) {
        List<JSONObject> list = new ArrayList<>();
        if (itemIds == null || itemIds.isEmpty()) {
            logger.info(TAG + " 未传 lines，本次只改表头，不下发明细行");
            return list;
        }
        StringBuilder in = new StringBuilder();
        for (Long id : itemIds) {
            if (in.length() > 0) {
                in.append(",");
            }
            in.append(id);
        }
        JSONArray recs = queryInternal(
                "SELECT id, posicion__c, requestedDeliveryDate__c, customerId__c.kunnr__c kunnr"
                        + " FROM orderDetail__c WHERE order__c = " + orderId + " AND id IN (" + in + ")");
        if (recs == null || recs.isEmpty()) {
            logger.warn(TAG + " 未查到任何指定明细行, orderId=" + orderId + ", 明细id=" + abbreviate(itemIds.toString()));
            return list;
        }
        // 按 id 建索引后，按前端传入顺序输出
        Map<Long, JSONObject> byId = new LinkedHashMap<>();
        for (int i = 0; i < recs.size(); i++) {
            JSONObject r = recs.getJSONObject(i);
            Long id = toLong(r.get("id"));
            if (id != null) {
                byId.put(id, r);
            }
        }
        for (Long id : itemIds) {
            JSONObject r = byId.get(id);
            if (r != null) {
                list.add(r);
            } else {
                logger.warn(TAG + " 明细行不存在或不属于该订单，跳过: id=" + id + ", orderId=" + orderId);
            }
        }
        if (list.size() < itemIds.size()) {
            logger.warn(TAG + " 部分明细行未命中, 命中=" + list.size() + "/" + itemIds.size());
        }
        return list;
    }

    // ===================== 入参解析 =====================

    /**
     * 解析 lines 为明细行 id 集合（去重且保持传入顺序，下发顺序与前端一致）。
     * <p>支持三种写法：
     * <ul>
     *   <li>对象数组：{@code [{"id":123},{"id":456}]}（标准写法，取元素的 id）</li>
     *   <li>标量数组/单个：{@code [123,456]} 或 {@code 123}</li>
     *   <li>逗号串：{@code "123,456"}</li>
     * </ul>
     * 非法项跳过并打 warn 日志，不中断整单。
     */
    private LinkedHashSet<Long> parseLineIds(Object v) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (v == null) {
            return ids;
        }
        if (v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            for (int i = 0; i < arr.size(); i++) {
                Object one = arr.get(i);
                Long id = extractLineId(one);
                if (id != null) {
                    ids.add(id);
                } else {
                    logger.warn(TAG + " lines 第 " + i + " 项无有效 id，已跳过: " + abbreviate(String.valueOf(one)));
                }
            }
            return ids;
        }
        if (v instanceof Number) {
            ids.add(((Number) v).longValue());
            return ids;
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            return ids;
        }
        for (String part : s.split(",")) {
            Long id = toLong(part);
            if (id != null) {
                ids.add(id);
            } else {
                logger.warn(TAG + " lines 含非法值，已跳过: " + abbreviate(part));
            }
        }
        return ids;
    }

    /** 单个 lines 元素取 id：对象取 id 字段；标量直接转Long；非法返回 null。 */
    private Long extractLineId(Object one) {
        if (one == null) {
            return null;
        }
        if (one instanceof JSONObject) {
            return toLong(((JSONObject) one).get("id"));
        }
        return toLong(one);
    }
}