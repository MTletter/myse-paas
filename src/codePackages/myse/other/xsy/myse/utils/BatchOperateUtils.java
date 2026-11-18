package other.xsy.myse.utils;

import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.BatchOperateResult;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.model.XObject;
import com.rkhd.platform.sdk.service.XObjectService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台批量写操作的通用封装（批量 insert / delete）。
 *
 * <p><b>解决的关键坑</b>：平台要求<b>同一个 List 里所有对象的 apiKey 必须一致</b>，
 * 否则整批直接抛 {@code xObject's apiKey must be same in the list} →
 * <b>这一批一条都插不进去</b>，而且批量接口常常只返回整批级错误，不告诉你哪条有问题。</p>
 *
 * <p>典型踩坑现场（2026-11-04）：发货明细 deliveryDetail__c 与装箱明细 packingDetail__c
 * 被放进同一个 List 批量插入 → 整批失败 → 明细 0 条入库，但接口仍返回 success。</p>
 *
 * <p>因此这里的实现是：<b>先按 apiKey 分组，每组单独分批提交</b>，
 * 并把整批级错误与逐条错误都收集起来（可随出参返回，不用翻平台日志）。</p>
 */
public final class BatchOperateUtils {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 默认分批大小（每批多少条提交一次） */
    public static final int DEFAULT_BATCH_SIZE = 200;

    private BatchOperateUtils() {
    }

    /**
     * 批量插入（按 apiKey 分组 + 分批提交）。
     *
     * @param list   待插入对象（可混放多种 apiKey，内部会分组）
     * @param label  日志里的业务名（如「发货单明细」）
     * @param errors 错误信息收集器，可为 null
     * @return 成功条数
     */
    public static int batchInsert(List<XObject> list, String label, List<String> errors) {
        return batchInsert(list, label, errors, DEFAULT_BATCH_SIZE);
    }

    public static int batchInsert(List<XObject> list, String label, List<String> errors, int batchSize) {
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int success = 0;
        for (Map.Entry<String, List<XObject>> en : groupByApiKey(list).entrySet()) {
            List<XObject> group = en.getValue();
            for (int i = 0; i < group.size(); i += batchSize) {
                List<XObject> part = new ArrayList<>(group.subList(i, Math.min(i + batchSize, group.size())));
                try {
                    BatchOperateResult br = XObjectService.instance().insert(part, true, true);
                    // ⚠️ 整批级错误：operateResults 可能为 null，只有 success/errorMessage 带信息
                    if (br == null || !Boolean.TRUE.equals(br.getSuccess())) {
                        addError(errors, "批量插入" + label + "[" + en.getKey() + "]整批失败: "
                                + (br == null ? "无返回" : br.getErrorMessage()));
                        continue;
                    }
                    List<OperateResult> ops = br.getOperateResults();
                    if (ops != null) {
                        for (OperateResult op : ops) {
                            if (op != null && Boolean.TRUE.equals(op.getSuccess())) {
                                success++;
                            } else if (op != null) {
                                addError(errors, "批量插入" + label + "[" + en.getKey() + "]失败: "
                                        + op.getErrorMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    addError(errors, "批量插入" + label + "[" + en.getKey() + "]异常: " + e.getMessage());
                    logger.error("批量插入" + label + "[" + en.getKey() + "]异常", e);
                }
            }
        }
        return success;
    }

    /**
     * 批量删除（按 apiKey 分组 + 分批提交）。
     *
     * @param stubs  待删除的 stub 对象（只设了 id）
     * @param label  日志里的业务名
     * @param errors 错误信息收集器，可为 null
     * @return 删除条数
     */
    public static int batchDelete(List<XObject> stubs, String label, List<String> errors) {
        return batchDelete(stubs, label, errors, DEFAULT_BATCH_SIZE);
    }

    public static int batchDelete(List<XObject> stubs, String label, List<String> errors, int batchSize) {
        if (stubs == null || stubs.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (Map.Entry<String, List<XObject>> en : groupByApiKey(stubs).entrySet()) {
            List<XObject> group = en.getValue();
            for (int i = 0; i < group.size(); i += batchSize) {
                List<XObject> part = new ArrayList<>(group.subList(i, Math.min(i + batchSize, group.size())));
                try {
                    BatchOperateResult br = XObjectService.instance().delete(part);
                    if (br == null || !Boolean.TRUE.equals(br.getSuccess())) {
                        addError(errors, "批量删除" + label + "[" + en.getKey() + "]整批失败: "
                                + (br == null ? "无返回" : br.getErrorMessage()));
                        continue;
                    }
                    List<OperateResult> ops = br.getOperateResults();
                    if (ops != null) {
                        for (OperateResult op : ops) {
                            if (op != null && Boolean.TRUE.equals(op.getSuccess())) {
                                deleted++;
                            } else if (op != null) {
                                addError(errors, "批量删除" + label + "[" + en.getKey() + "]失败: "
                                        + op.getErrorMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    addError(errors, "批量删除" + label + "[" + en.getKey() + "]异常: " + e.getMessage());
                    logger.error("批量删除" + label + "[" + en.getKey() + "]异常", e);
                }
            }
        }
        return deleted;
    }

    /**
     * 按 apiKey 分组（平台批量接口要求同批 apiKey 一致）。
     *
     * @return apiKey → 对象列表（保持插入顺序）
     */
    public static Map<String, List<XObject>> groupByApiKey(List<XObject> list) {
        Map<String, List<XObject>> groups = new LinkedHashMap<>();
        if (list == null) {
            return groups;
        }
        for (XObject o : list) {
            if (o == null) {
                continue;
            }
            String key = o.getApiKey();
            if (key == null || key.trim().isEmpty()) {
                key = "_unknown";
            }
            List<XObject> g = groups.get(key);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(key, g);
            }
            g.add(o);
        }
        return groups;
    }

    /** 收集错误信息（随出参返回，避免只能翻平台日志） */
    public static void addError(List<String> errors, String msg) {
        logger.error(msg);
        if (errors != null && msg != null) {
            errors.add(msg);
        }
    }
}
