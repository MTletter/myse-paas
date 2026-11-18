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
 * MYSE-09 信用额度查询
 * <p>
 * 调用 SAP BAPI：Z_RFC_CALL_CREDIT_LIMITE_NEW
 * 方向：mySE → PI → SAP
 * </p>
 *
 * <p><b>销售易 API 路径</b>：/rest/data/v2.0/scripts/api/myse/credit/query</p>
 *
 * <p><b>业务说明</b>：查询客户信用额度及使用情况。</p>
 */
@RestApi(baseUrl = "/myse")
public class Myse09CreditQueryApi {

    private static final Logger logger = LoggerFactory.getLogger();

    private static  String PI_URL ;

    private static  String API_TOKEN;

    /** 延迟加载 PI 配置（不在 static 块里做 SDK 查询，避免类初始化阶段类加载失败）。 */
    private static void ensureConfigLoaded() {
        if (PI_URL == null) {
            PiConfig.Entry c = PiConfig.get("MYSE-09");
            if (c != null) {
                PI_URL = c.getUrl();
                API_TOKEN = c.getToken();
            }
        }
    }

    //入参:{
    //    "SALES_ORGANIZATION": "A001",
    //    "SOLD_TO_PARTNER": 160804,
    //    "ZCURRENCY": "RMB"
    //}
    //返回:{
    //    "CREDIT_EXPOSURE": 4345664.81,
    //    "CREDIT_LIMIT": 4000000,
    //    "CURRENCY": "RMB",
    //    "LASTPAYDATE": 20200228,
    //    "LASTPAYVALUE": 56869.59,
    //    "MESSAGE": {
    //        "RETURNCODE": "S"
    //    },
    //    "OPENDELIVERYVALUE": 880731.82,
    //    "OPENSALESORDERVALUE": 456595.99,
    //    "PERCENT": 999,
    //    "RECEIVABLES": 3008337
    //}
    @RestMapping(value = "/credit/query", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name="json") String body) {
        try {
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            JSONObject requestBody = JSON.parseObject(body);
            logger.info("MYSE-09 请求: " + requestBody.toJSONString());
            ensureConfigLoaded();

            JSONObject piResponse = HttpUtil.post(PI_URL, requestBody, API_TOKEN);

            if (piResponse.containsKey("MESSAGE")&&!piResponse.getJSONObject("MESSAGE").getString("RETURNCODE").equals("S")) {
                logger.error("MYSE-09 SAP PI 调用失败: " + piResponse.toJSONString());
                return HttpUtil.fail("PI_ERROR", piResponse.toJSONString()).toJSONString();
            }

            logger.info("MYSE-09 成功");
            return piResponse.toJSONString();

        } catch (Exception e) {
            logger.error("MYSE-09 执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }
}
