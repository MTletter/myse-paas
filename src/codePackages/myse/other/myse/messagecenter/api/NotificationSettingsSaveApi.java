package other.myse.messagecenter.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
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
import other.myse.messagecenter.utils.HttpUtil;
import other.myse.messagecenter.utils.PermissionChangeGuard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 通知设置保存接口
 * <p>
 * 更新 User 表上的通知接收方式（notificationMethod__c）和接收邮箱（noticeEmail__c）。
 * </p>
 *
 * <p><b>销售易 API 路径</b>：POST /rest/data/v2.0/scripts/api/messagecenter/notification-settings/save</p>
 * <p><b>入参</b>：</p>
 * <pre>{
 *   userId: Long,                       // 必填
 *   notificationMethod: Integer[],      // 可选；支持 Integer[] / JSONArray / 逗号分隔字符串
 *                                       // 必含 1（站内信默认不可移除）；空/缺失 → [1]
 *   noticeEmail: String                 // 可选；null/空 → 写空字符串
 * }</pre>
 * <p><b>出参</b>：success=true / false，对应错误码</p>
 *
 * <p><b>错误码</b>：PARAM_EMPTY / PARAM_ERROR / USER_NOT_FOUND / UPDATE_FAILED / DML_ERROR / INTERNAL_ERROR</p>
 */
@RestApi(baseUrl = "/messagecenter")
public class NotificationSettingsSaveApi {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 通知方式码值 */
    private static final int METHOD_INTERNAL_MSG = 1;   // 站内信（默认锁定，不可移除）
    private static final int METHOD_EMAIL = 2;           // 电子邮件

    @RestMapping(value = "/notification-settings/save", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        try {
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = PermissionChangeGuard.checkPermissionChanged("NotificationSettingsSaveApi");
            if (guard != null) {
                return guard;
            }
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }
            JSONObject req = JSON.parseObject(body);

            // ===== 必填校验 =====
            Long userId = req.getLong("userId");
            if (userId == null) {
                return HttpUtil.fail("PARAM_ERROR", "userId 不能为空").toJSONString();
            }

            // ===== 解析 notificationMethod（站内信必含）=====
            Set<Integer> methods = parseNotificationMethod(req.get("notificationMethod"));
            if (!methods.contains(METHOD_INTERNAL_MSG)) {
                return HttpUtil.fail("PARAM_ERROR", "通知方式必须包含「站内信」(1)").toJSONString();
            }
            // 去重并保持顺序，写入时用 List<Integer>
            List<Integer> methodValue = new ArrayList<>(methods);

            // ===== 解析 noticeEmail（不校验格式，平台 email 字段约束）=====
            Object emailObj = req.get("noticeEmail");
            String noticeEmail = emailObj == null ? "" : String.valueOf(emailObj).trim();

            logger.info("NotificationSettingsSave userId=" + userId
                    + " notificationMethod=" + methodValue + " noticeEmail=" + noticeEmail);

            // ===== 更新 User =====
            User user = new User();
            user.setId(userId);
            user.setAttribute("notificationMethod__c", methodValue);
            user.setAttribute("noticeEmail__c", noticeEmail);

            OperateResult result;
            try {
                result = XObjectService.instance().update(user, true);
            } catch (ApiEntityServiceException e) {
                logger.error("NotificationSettingsSave DML 异常 userId=" + userId, e);
                return HttpUtil.fail("DML_ERROR", e.getMessage()).toJSONString();
            }
            if (!result.getSuccess()) {
                logger.error("NotificationSettingsSave 更新失败 userId=" + userId + ": " + result.getErrorMessage());
                return HttpUtil.fail("UPDATE_FAILED", result.getErrorMessage()).toJSONString();
            }

            logger.info("NotificationSettingsSave 更新成功 userId=" + userId);
            return HttpUtil.success("保存成功").toJSONString();

        } catch (Exception e) {
            logger.error("NotificationSettingsSave 执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    /**
     * 解析 notificationMethod 入参，兼容 Integer[] / JSONArray / 逗号分隔字符串 / 单值 Integer
     * 空/缺失 → 空集合（调用方再强制追加站内信 1）
     */
    private Set<Integer> parseNotificationMethod(Object raw) {
        Set<Integer> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            for (int i = 0; i < arr.size(); i++) {
                Integer v = toInt(arr.get(i));
                if (v != null) {
                    out.add(v);
                }
            }
        } else if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                Integer v = toInt(o);
                if (v != null) {
                    out.add(v);
                }
            }
        } else if (raw instanceof Integer[]) {
            for (Integer v : (Integer[]) raw) {
                if (v != null) {
                    out.add(v);
                }
            }
        } else {
            // 单值 / 字符串（可能为 "1" 或 "1,2"）
            String s = String.valueOf(raw).trim();
            if (s.isEmpty()) {
                return out;
            }
            if (s.contains(",")) {
                for (String t : s.split(",")) {
                    Integer v = toInt(t.trim());
                    if (v != null) {
                        out.add(v);
                    }
                }
            } else {
                Integer v = toInt(s);
                if (v != null) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private Integer toInt(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }


    public static void main(String[] args) {
        new NotificationSettingsSaveApi().execute("{\"userId\":\"4433780814545882\",\"notificationMethod\":[1,2],\"noticeEmail\":\"lichunlei@duluinfo.com\"}");
    }
}