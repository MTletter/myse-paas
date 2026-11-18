package other.xsy.myse.apis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.data.model.*;
import com.rkhd.platform.sdk.exception.ApiEntityServiceException;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.FutureTaskService;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;
import other.xsy.myse.utils.HttpUtil;
import other.xsy.myse.utils.StringUtil;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MYSE-04 报价单同步 SAP 触发转单
 * <p>
 * 业务逻辑调整（2026-07-31）：
 * 不再在 CRM 内部把报价单转成订单，而是把报价单数据同步给 SAP，
 * 由 SAP 完成转单，随后 SAP 通过 MYSE-15 把销售订单同步回 CRM。
 * </p>
 *
 * <p><b>方向</b>：mySE → PI → SAP（出站）</p>
 * <p><b>路径</b>：/myse/order/create-from-quotation</p>
 * <p><b>入参</b>：{"id": 报价单id}</p>
 */
@RestApi(baseUrl = "/myse")
public class Myse04QuotationToOrderApi {

    private static final Logger logger = LoggerFactory.getLogger();

    @RestMapping(value = "/order/create-from-quotation", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        try {
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            JSONObject requestBody = JSON.parseObject(body);
            Long quoteId = requestBody.getLong("id");
            if (quoteId == null) {
                return HttpUtil.fail("PARAM_ERROR", "报价单id(id)不能为空").toJSONString();
            }
            logger.info("MYSE-04 报价单同步SAP触发转单 请求 quoteId=" + quoteId);

            Quote__c queryQuote=new Quote__c();
            queryQuote.setId(quoteId);
            queryQuote=XObjectService.instance().get(queryQuote, true);

            Account account=new Account();
            account.setId(queryQuote.getPurchaserId__c());
            account=XObjectService.instance().get(account, true);
            if(!account.getIsEnable__c()){
                logger.info("账户未启用，不允许操作");
                JSONObject RESP_QUOTATION=new JSONObject();
                RESP_QUOTATION.put("RETURNCODE","401");
                RESP_QUOTATION.put("RETURNMESSAGE","您的账户权限已被停用");
                return RESP_QUOTATION.toString();
            }

            Quote__c quote=new Quote__c();
            quote.setId(quoteId);
            quote.setQuoteStatus__c(3);
            OperateResult res=XObjectService.instance().update(quote,true);
            logger.info("更新报价单状态创建订单中："+res.getSuccess());

            String messageId = FutureTaskService.instance().addFutureTask(qtTOsoFutureTask.class, quoteId.toString());

            JSONObject result=new JSONObject();
            JSONObject RESP_PEDIDO=new JSONObject();
            RESP_PEDIDO.put("RETURNCODE","0");
            result.put("RESP_PEDIDO",RESP_PEDIDO);

            Thread.sleep(3000);
            return result.toJSONString();

        } catch (Exception e) {
            logger.error("MYSE-04 执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

}
