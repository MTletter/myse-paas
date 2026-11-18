package other.myse.home.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.model.XObject;
import com.rkhd.platform.sdk.service.XObjectService;
import other.myse.home.utils.HttpUtil;
import other.myse.home.utils.PermissionChangeGuard;

import java.util.List;

/**
 * 协议弹窗检查接口
 * <p>
 * 前端调首页时传入 userId，后端查询用户弹窗标记（isAgreementPopup__c）。
 * 如需弹窗（值为 1），则查询协议书（agreement__c）中状态为"已发布"的最新记录，
 * 返回协议版本、协议正文、状态和生效日期。
 * </p>
 *
 * <p>销售易 API 路径：/rest/data/v2.0/scripts/api/home/agreement/check</p>
 */
@RestApi(baseUrl = "/home")
public class AgreementCheckApi {

    private static final Logger logger = LoggerFactory.getLogger();

    /**
     * 检查用户是否需要弹出协议书
     *
     * <p>请求 JSON：{"userId": 12345}</p>
     *
     * <p>响应 JSON：
     * <pre>
     * 成功: {"success": true, "data": {"needPopup": true, "agreement": { ... }}}
     * 失败: {"success": false, "errorCode": "...", "errorMessage": "..."}
     * </pre>
     * </p>
     */
    @RestMapping(value = "/agreement/check", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        try {
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = PermissionChangeGuard.checkPermissionChanged("AgreementCheckApi");
            if (guard != null) {
                return guard;
            }
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            JSONObject requestBody = JSON.parseObject(body);
            Long userId = requestBody.getLong("userId");
            if (userId == null) {
                return HttpUtil.fail("PARAM_MISSING", "userId 不能为空").toJSONString();
            }

            logger.info("AgreementCheck 检查弹窗, userId=" + userId);

            // 1. 查询用户弹窗标记
            QueryResult userResult = XObjectService.instance().query(
                "SELECT id, isAgreementPopup__c FROM User WHERE id = " + userId
            );
            List<XObject> userRecords = userResult.getRecords();

            if (userRecords == null || userRecords.isEmpty()) {
                return HttpUtil.fail("USER_NOT_FOUND", "用户不存在").toJSONString();
            }

            XObject user = userRecords.get(0);
            Object popupVal = user.getAttribute("isAgreementPopup__c");
            Integer needPopup = (popupVal != null) ? Integer.parseInt(popupVal.toString()) : 0;

            JSONObject response = new JSONObject();
            response.put("needPopup", needPopup == 1);
            response.put("agreement", null);

            // 2. 如果需要弹窗，查询已发布的协议书
            if (needPopup == 1) {
                QueryResult agreementResult = XObjectService.instance().query(
                    "SELECT id, agreement_version__c, agreement_content__c, " +
                    "agreement_status__c, effective_date__c " +
                    "FROM agreement__c " +
                    "WHERE id > 0 AND agreement_status__c = 1 " +
                    "ORDER BY id DESC LIMIT 1"
                );
                List<XObject> agreementRecords = agreementResult.getRecords();

                if (agreementRecords != null && !agreementRecords.isEmpty()) {
                    XObject agreement = agreementRecords.get(0);
                    JSONObject agreementData = new JSONObject();

                    Object idVal = agreement.getAttribute("id");
                    agreementData.put("id", idVal != null ? idVal.toString() : "");

                    Object verVal = agreement.getAttribute("agreement_version__c");
                    agreementData.put("version", verVal != null ? verVal.toString() : "");

                    Object contentVal = agreement.getAttribute("agreement_content__c");
                    agreementData.put("content", contentVal != null ? contentVal.toString() : "");

                    Object statusVal = agreement.getAttribute("agreement_status__c");
                    agreementData.put("status", statusVal != null ? Integer.parseInt(statusVal.toString()) : 0);

                    Object dateVal = agreement.getAttribute("effective_date__c");
                    agreementData.put("effectiveDate", dateVal != null ? dateVal.toString() : "");

                    response.put("agreement", agreementData);
                } else {
                    // 标记需要弹窗但没有找到已发布的协议书，降级为不弹窗
                    logger.warn("AgreementCheck 用户标记需要弹窗但未找到已发布协议, userId=" + userId);
                    response.put("needPopup", false);
                }
            }

            logger.info("AgreementCheck 完成, needPopup=" + response.getBoolean("needPopup"));
            return HttpUtil.success(response).toJSONString();

        } catch (Exception e) {
            logger.error("AgreementCheck 执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }
}
