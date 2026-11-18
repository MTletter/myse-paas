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
 * MYSE-20 查询客户默认 Sales Org
 * <p>
 * 根据客户账号（KUNNR）查询其在 SAP 中默认绑定的销售组织。
 * 方向：mySE → PI → SAP
 * </p>
 *
 * <p><b>销售易 API 路径</b>：/rest/data/v2.0/scripts/api/myse/customer/default-salesorg</p>
 *
 * <p><b>业务说明</b>：用于在报价单/订单创建前自动填充 Sales Org 字段。</p>
 */
@RestApi(baseUrl = "/myse")
public class Myse20DefaultSalesOrgApi {

    private static final Logger logger = LoggerFactory.getLogger();

    private static  String PI_URL ;

    private static  String API_TOKEN;

    /** 延迟加载 PI 配置（不在 static 块里做 SDK 查询，避免类初始化阶段类加载失败）。 */
    private static void ensureConfigLoaded() {
        if (PI_URL == null) {
            PiConfig.Entry c = PiConfig.get("MYSE-20");
            if (c != null) {
                PI_URL = c.getUrl();
                API_TOKEN = c.getToken();
            }
        }
    }

    @RestMapping(value = "/customer/default-salesorg", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name="json") String body) {
        try {
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            JSONObject requestBody = JSON.parseObject(body);
            logger.info("MYSE-20 查询客户默认SalesOrg请求: " + requestBody.toJSONString());
            ensureConfigLoaded();

            JSONObject piResponse = HttpUtil.post(PI_URL, requestBody, API_TOKEN);

            // 注：出站失败 / SAP RETURNCODE=E 的拦截暂不启用，响应直接透传给前端
            logger.info("MYSE-20 查询客户默认SalesOrg成功:"+piResponse);
            return piResponse.toString();

        } catch (Exception e) {
            logger.error("MYSE-20 查询客户默认SalesOrg异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }
}
