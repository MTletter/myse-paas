package other.myse.messagecenter.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.data.model.MessageCenter__c;
import com.rkhd.platform.sdk.exception.CacheException;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.model.XObject;
import com.rkhd.platform.sdk.service.CacheService;
import com.rkhd.platform.sdk.service.XObjectService;
import other.myse.messagecenter.utils.HttpUtil;
import other.myse.messagecenter.utils.PermissionChangeGuard;

import java.util.Arrays;
import java.util.List;

/**
 * 消息中心「标记已读」接口
 * <p>
 * 前端传入消息中心记录的数据 id（dataId / id），将 isRead__c 更新为 1（已读）。
 * </p>
 * <p>销售易 API 路径：POST /rest/data/v2.0/scripts/api/messagecenter/read</p>
 */
@RestApi(baseUrl = "/messagecenter")
public class MessageCenterReadApi {

    private static final Logger logger = LoggerFactory.getLogger();

    @RestMapping(value = "/read", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        try {
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = PermissionChangeGuard.checkPermissionChanged("MessageCenterReadApi");
            if (guard != null) {
                return guard;
            }
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            JSONObject requestBody = JSON.parseObject(body);
            Long id = null;
            if (requestBody.containsKey("id") && requestBody.get("id") != null) {
                id = requestBody.getLong("id");
            }
            if (id == null || id <= 0) {
                return HttpUtil.fail("PARAM_INVALID", "缺少有效的消息id").toJSONString();
            }


            MessageCenter__c obj = new MessageCenter__c();
            obj.setId(id);
            obj.setAttribute("isRead__c", 1);

            OperateResult result = XObjectService.instance().update(obj);
            if (result == null || !Boolean.TRUE.equals(result.getSuccess())) {
                String err = result == null ? "null" : (result.getErrorMessage() != null ? result.getErrorMessage() : "更新失败");
                logger.error("标记已读失败 id=" + id + " err=" + err);
                return HttpUtil.fail("UPDATE_FAIL", err).toJSONString();
            }

            JSONObject data = new JSONObject();
            data.put("id", String.valueOf(id));
            data.put("isRead", 1);
            data.put("isReadName", "是");

            logger.info("标记已读成功 id=" + id);
            return HttpUtil.success(data).toJSONString();

        } catch (Exception e) {
            logger.error("标记已读执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

}
