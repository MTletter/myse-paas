package other.myse.home.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.data.model.User;
import com.rkhd.platform.sdk.exception.ApiEntityServiceException;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.service.XObjectService;
import other.myse.home.utils.HttpUtil;
import other.myse.home.utils.PermissionChangeGuard;

/**
 * 协议确认接口
 * <p>
 * 前端协议弹窗用户点击"确认"后调用，将用户的弹窗标记（isAgreementPopup__c）更新为否（0）。
 * </p>
 *
 * <p>销售易 API 路径：/rest/data/v2.0/scripts/api/home/agreement/confirm</p>
 */
@RestApi(baseUrl = "/home")
public class AgreementConfirmApi {
    private static final Logger logger = LoggerFactory.getLogger();

    /**
     * 确认协议，关闭弹窗标记
     *
     * <p>请求 JSON：{"userId": 12345}</p>
     *
     * <p>响应 JSON：
     * <pre>
     * 成功: {"success": true, "data": "协议确认成功"}
     * 失败: {"success": false, "errorCode": "...", "errorMessage": "..."}
     * </pre>
     * </p>
     */
    @RestMapping(value = "/agreement/confirm", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        try {
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = PermissionChangeGuard.checkPermissionChanged("AgreementConfirmApi");
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

            logger.info("Agreement 确认协议, userId=" + userId);

            // 更新用户弹窗标记为否（0）
            User user = new User();
            user.setId(userId);
            user.setAttribute("isAgreementPopup__c", 0);

            OperateResult result = XObjectService.instance().update(user, true);

            if (result.getSuccess()) {
                logger.info("Agreement 更新成功, userId=" + userId);
                return HttpUtil.success("协议确认成功").toJSONString();
            } else {
                logger.error("Agreement 更新失败: " + result.getErrorMessage());
                return HttpUtil.fail("UPDATE_FAILED", result.getErrorMessage()).toJSONString();
            }

        } catch (ApiEntityServiceException e) {
            logger.error("Agreement DML 异常", e);
            return HttpUtil.fail("DML_ERROR", e.getMessage()).toJSONString();
        } catch (Exception e) {
            logger.error("Agreement 执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }
}
