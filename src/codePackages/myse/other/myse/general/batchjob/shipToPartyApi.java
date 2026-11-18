package other.myse.general.batchjob;

import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.exception.BatchJobException;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.service.BatchJobProService;
import com.rkhd.platform.sdk.task.param.PrepareParam;

import java.util.Map;

/**
 * 发货地址信息 —— 补齐「送达方」为空的历史脏数据（批处理入口）。
 *
 * <p><b>POST</b> {@code /rest/data/v2.0/scripts/api/neocrm/shipToParty/fillAccount}</p>
 *
 * <p>入参（可选）：</p>
 * <pre>
 * { "dryRun": "true" }   // 只统计不回填，先看能修多少条
 * </pre>
 *
 * <p>处理逻辑见 {@link ShipToPartyFillAccountBatchJob}：
 * 发货地址的 customItem10__c（客户号）→ 客户表 account.kunnr__c → 回填 customId__c（送达方）。</p>
 */
@RestApi(baseUrl = "/neocrm")
public class shipToPartyApi {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 子任务数据量（每批多少条交给 execute 处理） */
    private static final int SUB_JOB_SIZE = 200;

    /**
     * 同步执行回填（不依赖平台批处理调度）：一次调用跑完，结果直接返回。
     *
     * <p><b>POST</b> {@code /rest/data/v2.0/scripts/api/neocrm/shipToParty/fillAccountNow}</p>
     *
     * <p>入参（可选）：{@code { "dryRun": "true" }} → 只统计不回填。</p>
     *
     * <p>批处理入口（{@code /fillAccount}）适合正式环境跑大批量；
     * 这个同步入口适合沙盒验证和"点一下就跑完"的场景。</p>
     */
    @RestMapping(value = "/shipToParty/fillAccountNow", method = RequestMethod.POST)
    public String executeNow(@RestBeanParam(name = "json") String body) {
        boolean dryRun = false;
        if (body != null && !body.trim().isEmpty()) {
            try {
                JSONObject req = JSONObject.parseObject(body);
                String dry = req.getString("dryRun");
                if (dry != null && !"".equals(dry.trim())) {
                    dryRun = "true".equalsIgnoreCase(dry.trim()) || "1".equals(dry.trim());
                }
            } catch (Exception e) {
                logger.warn("解析入参失败，按默认（实际回填）处理: " + body, e);
            }
        }
        Map<String, Object> stat = ShipToPartyFillAccountBatchJob.runSync(dryRun);
        logger.info("发货地址回填送达方(同步)完成: " + stat);
        JSONObject res = new JSONObject();
        res.put("success", true);
        res.putAll(stat);
        return res.toJSONString();
    }

    @RestMapping(value = "/shipToParty/fillAccount", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        PrepareParam prepareParam = new PrepareParam();
        boolean dryRun = false;
        if (body != null && !body.trim().isEmpty()) {
            try {
                JSONObject req = JSONObject.parseObject(body);
                String dry = req.getString("dryRun");
                if (dry != null && !"".equals(dry.trim())) {
                    dryRun = "true".equalsIgnoreCase(dry.trim()) || "1".equals(dry.trim());
                    prepareParam.set("dryRun", String.valueOf(dryRun));
                }
            } catch (Exception e) {
                logger.warn("解析入参失败，按默认（实际回填）处理: " + body, e);
            }
        }
        try {
            String jobId = BatchJobProService.instance()
                    .addBatchJob(ShipToPartyFillAccountBatchJob.class, SUB_JOB_SIZE, prepareParam);
            logger.info("发货地址回填送达方批处理已提交: jobId=" + jobId + ", dryRun=" + dryRun);
            JSONObject res = new JSONObject();
            res.put("success", true);
            res.put("jobId", jobId);
            res.put("dryRun", dryRun);
            res.put("msg", dryRun
                    ? "已提交【试运行】批处理：只统计不回填，结果看平台批处理日志"
                    : "已提交批处理：发货地址送达方为空 → 按 customItem10__c 去客户表匹配并回填，结果看平台批处理日志");
            return res.toJSONString();
        } catch (BatchJobException e) {
            logger.error("提交批处理失败", e);
            throw new RuntimeException(e);
        }
    }
}
