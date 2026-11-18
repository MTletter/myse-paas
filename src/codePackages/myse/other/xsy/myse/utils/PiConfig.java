package other.xsy.myse.utils;

import com.rkhd.platform.sdk.data.model.SystemConfig__c;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.XObjectService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SAP PI 出站配置（systemConfig__c）统一懒加载。
 *
 * <p>原先每个接口各写一份 ensureConfigLoaded / static 块查同一张表，这里集中为一处：
 * 按 sys_number__c（如 MYSE-01 / MYSE-SCHNEIDER）加载 domain+url 与 token，成功后进程内缓存。</p>
 *
 * <p><b>必须懒加载</b>：不在 static 块里做 SDK 查询，避免类初始化阶段 SDK 上下文未就绪导致类加载失败。
 * 查不到或异常时返回 null 且不缓存（下次调用重试），与各接口原有行为一致。</p>
 */
public final class PiConfig {

    private static final Logger logger = LoggerFactory.getLogger();

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<String, Entry>();

    /** 一条出站配置：完整 URL（domain + url）与 Authorization token。 */
    public static final class Entry {
        private final String url;
        private final String token;

        private Entry(String url, String token) {
            this.url = url;
            this.token = token;
        }

        public String getUrl() {
            return url;
        }

        public String getToken() {
            return token;
        }
    }

    /** 按 sys_number__c 加载配置；未配置/查询异常返回 null（只记日志不抛出）。 */
    public static Entry get(String sysNumber) {
        Entry cached = CACHE.get(sysNumber);
        if (cached != null) {
            return cached;
        }
        try {
            String sql = "select sys_domain__c,sys_url__c,sys_token__c from systemConfig__c where sys_number__c='" + sysNumber + "'";
            QueryResult<SystemConfig__c> res = XObjectService.instance().query(sql, true);
            if (res != null && res.getRecords() != null && !res.getRecords().isEmpty()) {
                SystemConfig__c row = res.getRecords().get(0);
                Entry entry = new Entry(row.getSys_domain__c() + row.getSys_url__c(), row.getSys_token__c());
                CACHE.put(sysNumber, entry);
                return entry;
            }
            logger.error("PiConfig 未配置 systemConfig__c(sys_number__c=" + sysNumber + ")，PI 地址/token 为空");
        } catch (Exception e) {
            logger.error("PiConfig 读取 systemConfig__c(" + sysNumber + ") 失败(后续PI调用将失败)", e);
        }
        return null;
    }

    private PiConfig() {
    }
}