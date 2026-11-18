package other.myse.general.batchjob;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.data.model.ShipToParty__c;
import com.rkhd.platform.sdk.exception.ApiEntityServiceException;
import com.rkhd.platform.sdk.exception.BatchJobException;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.BatchOperateResult;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.model.XObject;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;
import com.rkhd.platform.sdk.task.BatchJobPro;
import com.rkhd.platform.sdk.task.param.BatchJobData;
import com.rkhd.platform.sdk.task.param.BatchJobDataBuilder;
import com.rkhd.platform.sdk.task.param.BatchJobParam;
import com.rkhd.platform.sdk.task.param.PrepareParam;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发货地址信息（shipToParty__c）—— 补齐「送达方」为空的历史脏数据。
 *
 * <p><b>背景</b>：早期 SAP 推送只写了送达方（customId__c），没写发货地址代码（kunnr__c），
 * 且部分记录的送达方根本没落库。沙盒里有一批记录送达方为空，需要回填。</p>
 *
 * <p><b>匹配口径</b>（老板 2026-11-03 定）：</p>
 * <ul>
 *   <li>发货地址的 <b>customItem10__c</b> 存的就是送达方的<b>客户号</b>；</li>
 *   <li>拿这个客户号去<b>客户表 account</b> 里找 <b>kunnr__c</b> 相同的客户；</li>
 *   <li>找到后把该客户的 id 回填到发货地址的 <b>customId__c（送达方）</b>。</li>
 * </ul>
 *
 * <p><b>一个客户号对应多个客户时取 id 最小</b>（与 Myse13 发货地址唯一键口径一致），
 * 并把所有候选记进日志，便于事后核对。</p>
 *
 * <p><b>客户表里查不到就跳过</b>（多半是客户还没建档），记日志计数，不报错中断。</p>
 *
 * <p><b>支持 dryRun</b>：传参 {@code dryRun=true} 时只统计不回填，用于先看能修多少。</p>
 */
public class ShipToPartyFillAccountBatchJob implements BatchJobPro<XObject> {

    private static final Logger logger = LoggerFactory.getLogger();
    private static final String TAG = "[发货地址-回填送达方] ";

    /** 单次 IN 查询的客户号个数 */
    private static final int IN_BATCH = 200;
    /** 单次批量更新的条数 */
    private static final int UPDATE_BATCH = 200;
    /** 同步入口每页拉取的条数 */
    private static final int PAGE_SIZE = 500;
    /** 同步入口最多拉取的总行数（保护，避免异常情况下无限翻页） */
    private static final int MAX_FETCH_ROWS = 500000;

    /** 跨批次缓存：客户号 → 客户 id（同一客户号可能被多条发货地址引用，避免反复查） */
    private static final Map<String, Long> ACCOUNT_ID_BY_KUNNR = new HashMap<>();
    /** 一个客户号对应多个客户时的候选清单：客户号 → [客户id:客户名] */
    private static final Map<String, List<String>> MULTI_ACCOUNT = new HashMap<>();

    // ---- 统计（跨批次累计）----
    private static int statScanned = 0;
    private static int statNoKey = 0;
    private static int statNoAccount = 0;
    private static int statMatched = 0;
    private static int statUpdated = 0;
    private static int statUpdateFailed = 0;

    @Override
    public BatchJobData prepare(PrepareParam prepareParam) throws BatchJobException {
        // 只处理「送达方为空」的记录
        String sql = "select id,customItem10__c,purchaseId__c from shipToParty__c where customId__c is null";
        logger.info(TAG + "待处理记录查询: " + sql);
        return new BatchJobDataBuilder()
                .setAdmin(true)
                .setSql(sql)
                .setApiKey("shipToParty__c")
                .buildQueryData();
    }

    @Override
    public void execute(List<XObject> list, BatchJobParam batchJobParam) {
        if (list == null || list.isEmpty()) {
            return;
        }
        boolean dryRun = "true".equalsIgnoreCase(batchJobParam.get("dryRun"));
        // 转成统一的行结构，与同步入口共用同一份处理逻辑
        List<String[]> rows = new ArrayList<>();
        for (XObject x : list) {
            rows.add(new String[]{String.valueOf(x.getId()),
                    readString(x.getAttribute("customItem10__c"))});
        }
        processRows(rows, dryRun);
    }

    /**
     * 同步执行（不依赖平台批处理调度）：分页查全部待处理 → 匹配客户 → 批量回填。
     *
     * <p>沙盒里批处理框架是后台调度/手动触发，接口提交后不会立刻执行；
     * 这个入口让调用方一次调用就能跑完（结果直接返回）。</p>
     *
     * @param dryRun true = 只统计不回填
     * @return 统计信息
     */
    public static Map<String, Object> runSync(boolean dryRun) {
        resetStat();
        // ===== 1. 分页查全部「送达方为空」的记录 =====
        List<String[]> rows = new ArrayList<>();
        int offset = 0;
        while (offset < MAX_FETCH_ROWS) {
            String sql = "select id,customItem10__c,purchaseId__c from shipToParty__c"
                    + " where customId__c is null order by id limit " + offset + ", " + PAGE_SIZE;
            List<JSONObject> recs;
            try {
                QueryResult<JSONObject> rt = XoqlService.instance().query(sql, true);
                recs = (rt == null) ? null : rt.getRecords();
            } catch (ApiEntityServiceException e) {
                logger.error(TAG + "查询待处理记录失败 sql=" + sql, e);
                break;
            }
            int n = (recs == null) ? 0 : recs.size();
            if (n == 0) {
                break;
            }
            for (JSONObject rec : recs) {
                if (rec == null) {
                    continue;
                }
                rows.add(new String[]{readString(rec.get("id")), readString(rec.get("customItem10__c"))});
            }
            offset += n;
            if (n < PAGE_SIZE) {
                break;
            }
        }
        logger.info(TAG + "待处理记录 " + rows.size() + " 条" + (dryRun ? "（dryRun）" : ""));
        processRows(rows, dryRun);
        return statResult(dryRun);
    }

    /** 统计结果 */
    private static Map<String, Object> statResult(boolean dryRun) {
        Map<String, Object> m = new HashMap<>();
        m.put("dryRun", dryRun);
        m.put("scanned", statScanned);
        m.put("matched", statMatched);
        m.put("updated", statUpdated);
        m.put("updateFailed", statUpdateFailed);
        m.put("noCustomerKey", statNoKey);
        m.put("accountNotFound", statNoAccount);
        m.put("multiAccountKeys", MULTI_ACCOUNT.size());
        return m;
    }

    /** 清空统计与缓存（同步入口每次调用前重置） */
    private static void resetStat() {
        statScanned = 0;
        statNoKey = 0;
        statNoAccount = 0;
        statMatched = 0;
        statUpdated = 0;
        statUpdateFailed = 0;
        ACCOUNT_ID_BY_KUNNR.clear();
        MULTI_ACCOUNT.clear();
    }

    /**
     * 核心处理：匹配客户 → 构造更新 → 批量写入。
     *
     * @param rows   每项 = {记录id, 客户号(customItem10__c)}
     * @param dryRun true = 只统计不回填
     */
    private static void processRows(List<String[]> rows, boolean dryRun) {
        statScanned += rows.size();

        // ===== 1. 收集客户号 =====
        Set<String> keys = new LinkedHashSet<>();
        for (String[] r : rows) {
            if (r[1] != null && !r[1].isEmpty()) {
                keys.add(r[1]);
            }
        }

        // ===== 2. 查客户（只查缓存里没有的）=====
        Set<String> missing = new LinkedHashSet<>();
        for (String k : keys) {
            if (!ACCOUNT_ID_BY_KUNNR.containsKey(k)) {
                missing.add(k);
            }
        }
        if (!missing.isEmpty()) {
            loadAccounts(new ArrayList<>(missing));
        }

        // ===== 3. 构造更新对象 =====
        List<ShipToParty__c> toUpdate = new ArrayList<>();
        for (String[] r : rows) {
            String k = r[1];
            if (k == null || k.isEmpty()) {
                statNoKey++;
                continue;
            }
            Long accountId = ACCOUNT_ID_BY_KUNNR.get(k);
            if (accountId == null) {
                // 客户表里没有这个客户号 → 跳过（多半客户还没建档）
                statNoAccount++;
                continue;
            }
            statMatched++;
            ShipToParty__c sp = new ShipToParty__c();
            sp.setId(toLong(r[0]));
            sp.setCustomId__c(accountId);
            toUpdate.add(sp);
        }

        if (dryRun) {
            logger.info(TAG + "[dryRun] 可回填 " + toUpdate.size() + " 条，不执行写入");
            return;
        }
        if (toUpdate.isEmpty()) {
            return;
        }

        // ===== 4. 批量更新 =====
        for (int i = 0; i < toUpdate.size(); i += UPDATE_BATCH) {
            List<ShipToParty__c> part = toUpdate.subList(i, Math.min(i + UPDATE_BATCH, toUpdate.size()));
            try {
                BatchOperateResult br = XObjectService.instance().update(part, true, true);
                List<OperateResult> ops = br == null ? null : br.getOperateResults();
                for (int k = 0; k < part.size(); k++) {
                    OperateResult op = (ops != null && k < ops.size()) ? ops.get(k) : null;
                    if (op != null && Boolean.TRUE.equals(op.getSuccess())) {
                        statUpdated++;
                    } else {
                        statUpdateFailed++;
                        logger.error(TAG + "回填失败 id=" + part.get(k).getId()
                                + " err=" + (op == null ? "无返回" : op.getErrorMessage()));
                    }
                }
            } catch (ApiEntityServiceException e) {
                statUpdateFailed += part.size();
                logger.error(TAG + "批量更新异常", e);
            }
        }
        logger.info(TAG + "处理完成: 扫描=" + rows.size() + ", 可回填=" + toUpdate.size()
                + ", 累计成功=" + statUpdated + ", 累计失败=" + statUpdateFailed);
    }

    @Override
    public void finish(BatchJobParam batchJobParam) {
        logger.info(TAG + "===== 汇总 =====");
        logger.info(TAG + "扫描总数   : " + statScanned);
        logger.info(TAG + "成功回填   : " + statUpdated);
        logger.info(TAG + "回填失败   : " + statUpdateFailed);
        logger.info(TAG + "客户号为空 : " + statNoKey);
        logger.info(TAG + "客户表查不到: " + statNoAccount + "（客户未建档，需客户同步后再跑）");
        if (!MULTI_ACCOUNT.isEmpty()) {
            logger.info(TAG + "客户号对应多个客户（取 id 最小）：" + MULTI_ACCOUNT.size() + " 个");
            for (Map.Entry<String, List<String>> e : MULTI_ACCOUNT.entrySet()) {
                logger.info(TAG + "   客户号 " + e.getKey() + " → " + e.getValue());
            }
        }
    }

    /**
     * 批量查客户：客户号 → 客户 id。
     *
     * <p>走内部 XOQL（系统鉴权，绕过对象级权限隔离），一次 IN 查一批，
     * 避免逐条查把查询数打爆。</p>
     */
    private static void loadAccounts(List<String> keys) {
        for (int i = 0; i < keys.size(); i += IN_BATCH) {
            List<String> part = keys.subList(i, Math.min(i + IN_BATCH, keys.size()));
            StringBuilder sb = new StringBuilder();
            for (String k : part) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append("'").append(k.replace("'", "")).append("'");
            }
            String sql = "select id,accountName,kunnr__c from account where kunnr__c in (" + sb + ")";
            try {
                QueryResult<JSONObject> rt = XoqlService.instance().query(sql, true);
                if (rt == null || rt.getRecords() == null) {
                    continue;
                }
                // 同一客户号可能对应多个客户 → 先收集候选，最后统一取 id 最小
                Map<String, List<Object[]>> candidates = new HashMap<>();
                for (JSONObject rec : rt.getRecords()) {
                    if (rec == null) {
                        continue;
                    }
                    String k = readString(rec.get("kunnr__c"));
                    Long id = toLong(rec.get("id"));
                    if (k == null || id == null) {
                        continue;
                    }
                    candidates.computeIfAbsent(k, x -> new ArrayList<>()).add(new Object[]{id, rec.getString("accountName")});
                }
                for (Map.Entry<String, List<Object[]>> en : candidates.entrySet()) {
                    String k = en.getKey();
                    List<Object[]> list = en.getValue();
                    long minId = Long.MAX_VALUE;
                    Long chosen = null;
                    List<String> desc = new ArrayList<>();
                    for (Object[] o : list) {
                        Long id = (Long) o[0];
                        desc.add(id + ":" + o[1]);
                        if (id < minId) {
                            minId = id;
                            chosen = id;
                        }
                    }
                    if (list.size() > 1) {
                        MULTI_ACCOUNT.put(k, desc);
                        logger.warn(TAG + "客户号 " + k + " 对应 " + list.size() + " 个客户，取 id 最小: " + chosen);
                    }
                    ACCOUNT_ID_BY_KUNNR.put(k, chosen);
                }
            } catch (ApiEntityServiceException e) {
                logger.error(TAG + "查客户失败 sql=" + sql, e);
            }
        }
    }

    /**
     * 取属性文本：兼容字符串 / 数字 / 数组 / 对象几种返回形态。
     *
     * <p>XOQL 对不同字段、不同通道返回的形态不统一（有的返回 label 数组），
     * 这里统一归一成一个字符串。</p>
     */
    private static String readString(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String) {
            return ((String) v).trim();
        }
        if (v instanceof Number) {
            return String.valueOf(v);
        }
        if (v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            return arr.isEmpty() ? null : readString(arr.get(0));
        }
        if (v instanceof JSONObject) {
            JSONObject jo = (JSONObject) v;
            Object s = jo.get("value") != null ? jo.get("value") : jo.get("name");
            return s == null ? null : readString(s);
        }
        return String.valueOf(v).trim();
    }

    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof JSONObject) {
            return toLong(((JSONObject) v).get("id"));
        }
        if (v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            return arr.isEmpty() ? null : toLong(arr.get(0));
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
