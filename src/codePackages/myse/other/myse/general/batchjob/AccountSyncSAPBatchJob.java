package other.myse.general.batchjob;

import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.data.model.Account;
import com.rkhd.platform.sdk.data.model.SystemConfig__c;
import com.rkhd.platform.sdk.exception.ApiEntityServiceException;
import com.rkhd.platform.sdk.exception.BatchJobException;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.BatchOperateResult;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.model.XObject;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;
import com.rkhd.platform.sdk.task.BatchJobPro;
import com.rkhd.platform.sdk.task.param.BatchJobData;
import com.rkhd.platform.sdk.task.param.BatchJobDataBuilder;
import com.rkhd.platform.sdk.task.param.BatchJobParam;
import com.rkhd.platform.sdk.task.param.PrepareParam;
import other.myse.general.utils.HttpUtil;
import other.myse.general.utils.StringUtil;

import java.util.ArrayList;
import java.util.List;

public class AccountSyncSAPBatchJob implements BatchJobPro<XObject> {
    private static final Logger logger = LoggerFactory.getLogger();

    private static String PI_URL;

    private static String API_TOKEN;


    static {
        if (StringUtil.isBlank(PI_URL)) {
            try {
                String sql = "select sys_domain__c,sys_url__c,sys_token__c from systemConfig__c where sys_number__c='MYSE-36'";
                QueryResult<SystemConfig__c> res = XObjectService.instance().query(sql, true);
                if (!res.getRecords().isEmpty()) {
                    PI_URL = res.getRecords().get(0).getSys_domain__c() + res.getRecords().get(0).getSys_url__c();
                    API_TOKEN = res.getRecords().get(0).getSys_token__c();
                }
            } catch (ApiEntityServiceException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public BatchJobData prepare(PrepareParam prepareParam) throws BatchJobException {
        String sql = "select id,kunnr__c from account where syncStatus__c is null limit 50000 ";
        logger.info(sql);
        return new BatchJobDataBuilder()
                .setAdmin(true).setSql(sql).setApiKey("account").buildQueryData();

    }

    @Override
    public void execute(List<XObject> list, BatchJobParam batchJobParam) {
        List<Account> acList=new ArrayList<>();

        for (XObject xObject : list) {
            try {
                String sql="select salesOrgName__c from accountSalesOrg__c where purchaseId__c="+xObject.getId()+" and salesOrg__c is not null group by salesOrgName__c";
                logger.info("sql:"+sql);

                QueryResult<JSONObject> res= XoqlService.instance().query(sql,true);

                for (JSONObject record : res.getRecords()) {
                    JSONObject sapPayload=new JSONObject();
                    JSONObject IT_DATA=new JSONObject();
                    JSONObject item=new JSONObject();
                    item.put("KUNNR",xObject.getAttribute("kunnr__c"));
                    item.put("VKORG",record.getString("salesOrgName__c"));
                    item.put("ACTIVE","X");
                    IT_DATA.put("item",item);
                    sapPayload.put("IT_DATA",IT_DATA);
                    JSONObject piResponse = HttpUtil.post(PI_URL, sapPayload, API_TOKEN);
                    logger.info("piResponse:"+piResponse);
                }

                Account acc=new Account();
                acc.setId(xObject.getId());
                acc.setSyncStatus__c(2);
                acList.add(acc);
            } catch (ApiEntityServiceException e) {
                throw new RuntimeException(e);
            }
        }

        BatchOperateResult req = null;
        try {
            req = XObjectService.instance().update(acList, true, true);
            logger.info("更新状态：" + req.getSuccess() + "==" + req.getErrorMessage() + "==" + req.getOperateResults());
        } catch (ApiEntityServiceException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public void finish(BatchJobParam batchJobParam) {
        logger.info("finish:PORequestBatchJob");
    }

    public static void main(String[] args) throws ApiEntityServiceException {
        String sql="select salesOrgName__c from accountSalesOrg__c where purchaseId__c=4488926697210845 and salesOrg__c is not null group by salesOrgName__c";
        logger.info("sql:"+sql);

        QueryResult<JSONObject> res= XoqlService.instance().query(sql,true);
        logger.info("res:"+res.getRecords());
    }

}
