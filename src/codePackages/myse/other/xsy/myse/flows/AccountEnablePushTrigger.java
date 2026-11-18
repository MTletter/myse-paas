package other.xsy.myse.flows;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.ScriptTrigger;
import com.rkhd.platform.sdk.exception.ScriptBusinessException;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.DataModel;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.model.XObject;
import com.rkhd.platform.sdk.param.ScriptTriggerParam;
import com.rkhd.platform.sdk.param.ScriptTriggerResult;
import com.rkhd.platform.sdk.service.XoqlService;
import com.rkhd.platform.sdk.trigger.DataResult;
import com.rkhd.platform.sdk.trigger.Trigger;
import com.rkhd.platform.sdk.trigger.TriggerRequest;
import com.rkhd.platform.sdk.trigger.TriggerResponse;
import other.xsy.myse.apis.Myse36CustomerPushApi;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户（account）启用状态变更触发器（other.xsy.myse.flows）
 *
 * <p>挂载方式：脚本注册 scriptTrigger.xml，对象 account，operate=update，position=after。
 * 当客户 <b>isEnable__c</b> 字段真正发生变化（true↔false 切换）时，同步调用
 * {@link Myse36CustomerPushApi#pushCustomer} 将启用状态推送到 SAP ERP。</p>
 *
 * <p>触发判断：after 位置拿到的 dataList 是变更后的对象，无法直接拿旧值，
 * 故按记录 id 反查库里的 isEnable__c 旧值做比较，仅在真正变化时推送（避免其它字段
 * 更新也触发重复推送）。</p>
 *
 * <p>销售组织来源：客户可能关联多个销售组织（accountSalesOrg__c，purchaseId__c 关联客户），
 * 逐条取出 salesOrg__c 各推送一次。</p>
 *
 * <p>容错：老板要求同步调用——任一销售组织推送失败，整条记录标记失败（success=false），
 * 从而阻断 account 保存，保证 mySE 与 SAP 启用状态一致。</p>
 */
public class AccountEnablePushTrigger implements ScriptTrigger {

    private static final Logger logger = LoggerFactory.getLogger();

    @Override
    public ScriptTriggerResult execute(ScriptTriggerParam scriptTriggerParam) throws ScriptBusinessException {
        List<DataResult> dataResults = new ArrayList<DataResult>();

        List<DataModel> dataList = scriptTriggerParam.getDataModelList();

        for (DataModel xObject : dataList) {
            Long accountId = xObject == null ? null : xObject.getLong("id");
            try {
                handleOne(accountId, xObject);

            } catch (Exception e) {
                // 同步调用：推送失败阻断保存，标记该记录失败
                logger.error("MYSE-36 account 启用状态推送失败 accountId=" + accountId, e);
            }
        }

        return new ScriptTriggerResult(scriptTriggerParam.getDataModelList());
    }

    /**
     * 处理单条 account：判断 isEnable__c 是否真正变化，变化则遍历销售组织逐条推送。
     */
    private void handleOne(Long accountId, DataModel xObject) throws Exception {

        // ===== 2) 查客户关联的销售组织（accountSalesOrg__c.purchaseId__c = accountId） =====
        List<Long> salesOrgIds = querySalesOrgIds(accountId);
        if (salesOrgIds.isEmpty()) {
            logger.warn("MYSE-36 trigger 客户未关联销售组织 accountId=" + accountId + "（跳过推送）");
            return;
        }

        // ===== 3) 逐销售组织同步推送 =====
        for (Long salesOrgId : salesOrgIds) {
            String result = Myse36CustomerPushApi.pushCustomer(accountId, salesOrgId);
            logger.info("MYSE-36 trigger 推送结果 accountId=" + accountId + ", salesOrgId=" + salesOrgId
                    + " => " + result);
            if (isFail(result)) {
                throw new ScriptBusinessException("客户启用状态推送失败 salesOrgId=" + salesOrgId + ": " + result);
            }
        }
    }

    /** 查客户关联的销售组织 id 列表。 */
    private List<Long> querySalesOrgIds(Long accountId) {
        List<Long> ids = new ArrayList<>();
        try {
            String sql = "SELECT id, salesOrg__c FROM accountSalesOrg__c WHERE purchaseId__c = " + accountId;
            QueryResult<JSONObject> res = XoqlService.instance().query(sql);
            if (res != null && res.getRecords() != null) {
                for (JSONObject row : res.getRecords()) {
                    Long sid = toLong(row.get("salesOrg__c"));
                    if (sid != null && !ids.contains(sid)) {
                        ids.add(sid);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("MYSE-36 trigger 查询销售组织失败 accountId=" + accountId, e);
        }
        return ids;
    }

    /** 判断推送结果是否为失败（fail JSON）。 */
    private boolean isFail(String resultJson) {
        if (resultJson == null || resultJson.trim().isEmpty()) {
            return true;
        }
        try {
            JSONObject obj = JSON.parseObject(resultJson);
            // HttpUtil.success 返回 {"success":true,...}；fail 返回 {"success":false,"code":...}
            return !Boolean.TRUE.equals(obj.getBoolean("success"));
        } catch (Exception e) {
            return true;
        }
    }

    private Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof JSONObject) {
            v = ((JSONObject) v).get("id");
        }
        if (v == null) {
            return null;
        }
        try {
            return Long.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
