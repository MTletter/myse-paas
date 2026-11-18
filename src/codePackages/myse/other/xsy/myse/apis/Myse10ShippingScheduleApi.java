package other.xsy.myse.apis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import other.xsy.myse.utils.HttpUtil;
import other.xsy.myse.utils.PiConfig;

/**
 * MYSE-10 查询 Shipping Schedule
 * <p>
 * 调用 SAP BAPI：Z_ORDER_STAT_SHIP_SCHED_NEW
 * 方向：mySE → PI → SAP
 * </p>
 *
 * <p><b>销售易 API 路径</b>：/rest/data/v2.0/scripts/api/myse/order/shipping-schedule</p>
 *
 * <p><b>业务说明</b>：查询销售订单指定行的物流配送计划。</p>
 */
@RestApi(baseUrl = "/myse")
public class Myse10ShippingScheduleApi {

    private static final Logger logger = LoggerFactory.getLogger();

    private static  String PI_URL ;

    private static  String API_TOKEN;

    /** 延迟加载 PI 配置（不在 static 块里做 SDK 查询，避免类初始化阶段类加载失败）。 */
    private static void ensureConfigLoaded() {
        if (PI_URL == null) {
            PiConfig.Entry c = PiConfig.get("MYSE-10");
            if (c != null) {
                PI_URL = c.getUrl();
                API_TOKEN = c.getToken();
            }
        }
    }

    @RestMapping(value = "/order/shipping-schedule", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name="json") String body) {
        try {
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            JSONObject requestBody = JSON.parseObject(body);
            logger.info("MYSE-10 请求: " + requestBody.toJSONString());
            ensureConfigLoaded();

            JSONObject piResponse = HttpUtil.post(PI_URL, requestBody, API_TOKEN);

            if (piResponse.containsKey("error")) {
                logger.error("MYSE-10 SAP PI 调用失败: " + piResponse.toJSONString());
                return HttpUtil.fail("PI_ERROR", piResponse.getString("message")).toJSONString();
            }

            logger.info("MYSE-10 成功");
            return piResponse.toJSONString();

        } catch (Exception e) {
            logger.error("MYSE-10 执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }
}
