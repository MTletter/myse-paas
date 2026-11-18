package other.myse.general.batchjob;

import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.exception.BatchJobException;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.service.BatchJobProService;
import com.rkhd.platform.sdk.task.param.PrepareParam;

@RestApi(baseUrl = "/neocrm")
public class accountApi {
    private static final Logger logger = LoggerFactory.getLogger();

    @RestMapping(value = "/account/syncAccount", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        PrepareParam prepareParam = new PrepareParam();
        try {
            logger.debug("jobId:" + BatchJobProService.instance().addBatchJob(AccountSyncSAPBatchJob.class, 10, prepareParam));
        } catch (BatchJobException e) {
            throw new RuntimeException(e);
        }
        return "success";
    }
}
