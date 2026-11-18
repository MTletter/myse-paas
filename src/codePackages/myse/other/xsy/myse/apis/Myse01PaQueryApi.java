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
import other.xsy.myse.utils.StringUtil;

import java.util.Objects;

/**
 * MYSE-01 P&A 价格货期查询
 * <p>
 * 调用 SAP BAPI：Z_BAPI_PAMULTIPLE
 * 方向：mySE → PI → SAP
 * </p>
 *
 * <p><b>销售易 API 路径</b>：/rest/data/v2.0/scripts/api/myse/pa/query</p>
 *
 * <p><b>业务说明</b>：查询产品可用价格及货期信息，支持多物料批量查询，返回阶梯价、货币、可用量、承诺交期等。</p>
 */
@RestApi(baseUrl = "/myse")
public class Myse01PaQueryApi  {

    private static final Logger logger = LoggerFactory.getLogger();

    private static  String PI_URL ;

    private static  String API_TOKEN;

    /**
     * 延迟加载 PI 配置（不在 static 块里做 SDK 查询，避免类初始化阶段类加载失败）。
     */
    private void ensureConfigLoaded() {
        if (PI_URL == null) {
            PiConfig.Entry c = PiConfig.get("MYSE-01");
            if (c != null) {
                PI_URL = c.getUrl();
                API_TOKEN = c.getToken();
            }
        }
    }

    @RestMapping(value = "/pa/query", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name="json") String body) {
        try {
            // 从 Request 中获取 request body
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            JSONObject requestBody = JSON.parseObject(body);
            logger.info("MYSE-01 请求: " + requestBody.toJSONString());
            ensureConfigLoaded();

            JSONObject header=requestBody.getJSONObject("HEADER_IN");
            header.put("PURCHASERID",StringUtil.padKunnr(header.getString("PURCHASERID")));
            JSONObject piResponse = HttpUtil.post(PI_URL, requestBody,API_TOKEN);
            logger.info("MYSE-01 PI_URL=" + PI_URL + " piResponse=" + piResponse.toJSONString());

            if (piResponse.containsKey("error")) {
                // 出站调用本身失败（DNS/连接/超时/非JSON等）
                logger.error("MYSE-01 出站调用失败: " + piResponse.toJSONString());
                return piResponse.toJSONString();
            }

            if (Objects.equals(piResponse.getJSONObject("RESULT").getString("RETURNCODE"),"E")) {
                logger.error("MYSE-01 SAP PI 调用失败: " + piResponse.toJSONString());
                return piResponse.toJSONString();
            }

            logger.info("MYSE-01 成功");
            return piResponse.toJSONString();

        } catch (Exception e) {
            logger.error("MYSE-01 执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getClass().getName() + ": " + e.getMessage()).toJSONString();
        }
    }
}
