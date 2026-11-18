package other.xsy.myse.apis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.*;
import com.rkhd.platform.sdk.data.model.Account;
import com.rkhd.platform.sdk.data.model.AccountSalesOrg__c;
import com.rkhd.platform.sdk.data.model.Department;
import com.rkhd.platform.sdk.data.model.ShipToParty__c;
import com.rkhd.platform.sdk.exception.ApiEntityServiceException;
import com.rkhd.platform.sdk.http.RkhdHttpClient;
import com.rkhd.platform.sdk.http.RkhdHttpData;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.BatchOperateResult;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.model.XObject;
import com.rkhd.platform.sdk.service.CustomConfigService;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;
import other.xsy.myse.utils.HttpUtil;

import static other.xsy.myse.utils.JsonValues.trimToNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MYSE-13 客户主数据推送
 * <p>
 * 接收 SAP IDoc（Message type: DEBMAS）推送客户主数据。
 * 方向：SAP → mySE（被动接收）
 * </p>
 *
 * <p><b>销售易 API 路径</b>：/rest/data/v2.0/scripts/api/myse/master-data/customer</p>
 *
 * <p><b>业务说明</b>：SAP 客户主数据新建或变更时触发，mySE 侧创建或更新客户档案，
 * 包含客户基础信息（名称、地址、联系方式）、销售组织属性（交货条件、运输条件）、
 * 合作伙伴功能（收货方等）。其中 CUST.SalesArea[] 映射到独立的<b>客户销售组织</b>
 * （accountSalesOrg__c）对象，按 kunnr 整组替换（先删后插）。</p>
 *
 * <p><b>数据结构</b>：
 * - header：客户通用主数据（对应 DEBMAS General 段）→ account 主对象
 * - SalesArea：销售组织维度的客户数据（对应 DEBMAS SalesOrg 段，可多条）→ accountSalesOrg__c
 * </p>
 *
 */
@RestApi(baseUrl = "/myse")
public class Myse13CustomerMasterApi {

    private static final Logger logger = LoggerFactory.getLogger();

    // 销售组织全量缓存（saleorg__c 数据量小，一次性全量加载后解析零查询）
    // key：name（销售组织代码，如 N001）→ Saleorg__c.id
    private static final Map<String, Long> SALES_ORG_BY_NAME = new ConcurrentHashMap<>();
    // key：companyCode__c（公司代码）→ Saleorg__c.id
    private static final Map<String, Long> SALES_ORG_BY_CODE = new ConcurrentHashMap<>();
    // 全量缓存加载时间戳（0=未加载）；带 TTL，保证销售组织主数据新增后能自动刷新
    private static volatile long SALES_ORG_LOADED_AT = 0L;
    private static final long SALES_ORG_CACHE_TTL = 30L * 60L * 1000L;
    // 全量查询上限（saleorg__c 实际仅几十条，留足余量）
    private static final int SALES_ORG_MAX_ROWS = 1000;

    private static Long ParentDepartId = 4364614113724426L;

    // 实体业务类型 id 缓存（运行时从元数据接口获取，避免硬编码租户专属 id）
    private static final Map<String, Long> BUSI_TYPE_CACHE = new ConcurrentHashMap<>();

    // 平台批量写接口分批大小（防单批过大被平台限制）
    private static final int BATCH_SIZE = 100;

     static void resolveMessageCenterEntityType() {
        try {
            Map<String, String> cfg = CustomConfigService.instance().getConfigSet("entityType");
            String v = (cfg == null) ? null : cfg.get("ParentDepartId");
            if (v != null && !v.trim().isEmpty()) {
                ParentDepartId=Long.parseLong(v);
            }
        } catch (Exception e) {
            logger.warn(" 读取自定义配置 entityType.MessageCenter 失败，使用内置默认值", e);
        }
    }

    @RestMapping(value = "/master-data/customer", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name="json") String body) {
        try {
            if (body == null || body.isEmpty()) {
                return HttpUtil.fail("PARAM_EMPTY", "请求体不能为空").toJSONString();
            }

            JSONObject requestBody = JSON.parseObject(body);
            // 大报文（50 条客户）不再整包打日志，只记条数，避免日志序列化耗时与日志膨胀
            JSONArray custArray = requestBody.getJSONArray("CUST");
            if (custArray == null || custArray.isEmpty()) {
                return HttpUtil.fail("PARAM_ERROR", "CUST 数组不能为空").toJSONString();
            }
            logger.info("MYSE-13 客户主数据推送: total=" + custArray.size());

            List<String> successes = new ArrayList<>();
            List<String> failures = new ArrayList<>();

            // ===== Phase 1: 收集 KUNNR，剔除空值/重复，批量预查已有账户 =====
            List<JSONObject> validCusts = new ArrayList<>();
            List<String> kunnrs = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < custArray.size(); i++) {
                JSONObject cust = custArray.getJSONObject(i);

                String kunnr = trimToNull(cust.getString("KUNNR"));
                if (kunnr == null) {
                    failures.add("index=" + i + ": KUNNR 不能为空");
                    continue;
                }
                if (!seen.add(kunnr)) {
                    failures.add("KUNNR=" + kunnr + ": 本批次重复推送，仅处理首条");
                    continue;
                }
                validCusts.add(cust);
                kunnrs.add(kunnr);

                if(cust.getJSONArray("Partny_function")!=null) {
                    for (Object partnyFunction : cust.getJSONArray("Partny_function")) {
                        JSONObject partny=(JSONObject) partnyFunction;
                        kunnrs.add(partny.getString("F_KUNNR"));
                    }
                }
            }

            Map<String, Long> accountIdByKunnr = findAccountIdsByKunnrs(kunnrs);

            // ===== Phase 2: 构建账户对象（新/旧两组），单条校验失败不影响其他 =====
            List<Account> toInsert = new ArrayList<>();
            List<Account> toUpdate = new ArrayList<>();
            List<String> insertKunnrs = new ArrayList<>();
            List<String> updateKunnrs = new ArrayList<>();
            for (JSONObject cust : validCusts) {
                String kunnr = trimToNull(cust.getString("KUNNR"));
                Long existingId = accountIdByKunnr.get(kunnr);
                try {
                    Account account = buildAccount(cust, existingId);
                    if (existingId != null) {
                        toUpdate.add(account);
                        updateKunnrs.add(kunnr);
                    } else {
                        toInsert.add(account);
                        insertKunnrs.add(kunnr);
                    }
                } catch (Exception e) {
                    failures.add("KUNNR=" + kunnr + ": " + e.getMessage());
                }
            }

            // ===== Phase 3: 批量写账户 =====
            // 新建：一批 insert(List)，逐条归集 dataId；首次推送成功 → 同步创建外部部门 department
            List<Department> deptsToInsert = new ArrayList<>();
            if (!toInsert.isEmpty()) {
                BatchOperateResult br = null;
                try {
                    br = XObjectService.instance().insert(toInsert, true,true);
                } catch (Exception e) {
                    logger.error("MYSE-13 批量新建客户异常", e);
                }
                Long busiType = resolveBusinessType("department");

                List<OperateResult> ops = br == null ? null : br.getOperateResults();
                for (int i = 0; i < toInsert.size(); i++) {
                    OperateResult op = (ops != null && i < ops.size()) ? ops.get(i) : null;
                    if (op != null && Boolean.TRUE.equals(op.getSuccess()) && op.getDataId() != null) {
                        accountIdByKunnr.put(insertKunnrs.get(i), op.getDataId());
                        successes.add("KUNNR=" + insertKunnrs.get(i) + " -> accountId=" + op.getDataId());
                        // 首次推送（创建）成功 → 同步创建外部部门
                        deptsToInsert.add(buildFirstPushDepartment(
                                toInsert.get(i), insertKunnrs.get(i), op.getDataId(),busiType));
                    } else {
                        String errMsg = op == null ? "批量结果缺失" : op.getErrorMessage();
                        failures.add("KUNNR=" + insertKunnrs.get(i) + ": 保存客户失败: " + errMsg);
                    }
                }
            }
            // 更新：一批 update(List)
            if (!toUpdate.isEmpty()) {
                BatchOperateResult br = null;
                try {
                    br = XObjectService.instance().update(toUpdate);
                } catch (Exception e) {
                    logger.error("MYSE-13 批量更新客户异常", e);
                }
                List<OperateResult> ops = br == null ? null : br.getOperateResults();
                for (int i = 0; i < toUpdate.size(); i++) {
                    OperateResult op = (ops != null && i < ops.size()) ? ops.get(i) : null;
                    if (op == null || Boolean.TRUE.equals(op.getSuccess())) {
                        successes.add("KUNNR=" + updateKunnrs.get(i) + " -> accountId=" + accountIdByKunnr.get(updateKunnrs.get(i)));
                    } else {
                        logger.info("更新客户失败:"+op.getErrorMessage());
                        failures.add("KUNNR=" + updateKunnrs.get(i) + ": 更新客户失败: " + op.getErrorMessage());
                        // 主记录更新失败则不再同步其子记录，保持数据一致
                        accountIdByKunnr.remove(updateKunnrs.get(i));
                    }
                }
            }

            // ===== Phase 4: 批量删除旧客户销售组织（accountSalesOrg__c 仍为整组替换） =====
            // 注意：发货地址 shipToParty__c【不再整组删除】，改为按 (账户 + F_KUNNR) 匹配更新，见 Phase 5/6
            List<Long> allAccountIds = new ArrayList<>(new LinkedHashSet<>(accountIdByKunnr.values()));
            if (!allAccountIds.isEmpty()) {
                try {
                    List<Long> oldOrgIds = flatten(querySubIdsByAccountIds("accountSalesOrg__c", allAccountIds));
                    batchDeleteStubs(orgStubs(oldOrgIds), "旧客户销售组织");
                } catch (Exception e) {
                    logger.error("MYSE-13 批量删除旧客户销售组织失败", e);
                }
            }

            // ===== Phase 5: 预查已有发货地址（按 账户+F_KUNNR 建索引）+ 构建新增/更新记录 =====
            // key = accountId + "#" + F_KUNNR（kunnr__c），value = 该键下已有记录 id（升序）
            Map<String, List<Long>> existingSpByKey = queryExistingShipToParties(allAccountIds);
            Set<String> matchedSpKeys = new LinkedHashSet<>();
            List<Long> spIdsToMarkDeleted = new ArrayList<>();   // 重复/SAP 未下发的旧记录 → 标记删除

            List<AccountSalesOrg__c> orgsToInsert = new ArrayList<>();
            List<ShipToParty__c> spsToInsert = new ArrayList<>();
            List<ShipToParty__c> spsToUpdate = new ArrayList<>();
            for (JSONObject cust : validCusts) {
                String kunnr = trimToNull(cust.getString("KUNNR"));
                Long accountId = accountIdByKunnr.get(kunnr);
                if (accountId == null) {
                    continue; // 主记录失败的客户跳过子表
                }
                try {
                    orgsToInsert.addAll(buildSalesOrgs(accountId, cust));
                } catch (Exception e) {
                    logger.error("MYSE-13 构建客户销售组织失败 KUNNR=" + kunnr, e);
                }
                try {
                    for (ShipToParty__c sp : buildShipToParties(accountId, cust, accountIdByKunnr,
                            existingSpByKey, matchedSpKeys, spIdsToMarkDeleted)) {
                        // 构建时命中已有记录会回填 id → 有 id 走更新，无 id 走新增
                        if (sp.getId() != null) {
                            spsToUpdate.add(sp);
                        } else {
                            spsToInsert.add(sp);
                        }
                    }
                } catch (Exception e) {
                    logger.error("MYSE-13 构建发货地址失败 KUNNR=" + kunnr, e);
                }
            }

            // SAP 本次未下发的已有发货地址 → 标记删除（不物理删除）
            for (Map.Entry<String, List<Long>> e : existingSpByKey.entrySet()) {
                if (!matchedSpKeys.contains(e.getKey())) {
                    spIdsToMarkDeleted.addAll(e.getValue());
                }
            }

            // ===== Phase 6: 批量写子记录 =====
            // 外部部门：仅首次推送新建客户时创建（Phase 3 已收集），失败不影响主流程
            int deptCount = batchInsert(deptsToInsert, "外部部门");
            int orgCount = batchInsert(orgsToInsert, "客户销售组织");
            int spInsertCount = batchInsert(spsToInsert, "发货地址");
            int spUpdateCount = batchUpdate(spsToUpdate, "发货地址");
            int spDeletedCount = batchMarkDeleted(spIdsToMarkDeleted, "发货地址");
            logger.info("MYSE-13 子记录同步完成: 外部部门=" + deptCount
                    + ", salesOrg插入=" + orgCount
                    + ", shipTo新增=" + spInsertCount + ", shipTo更新=" + spUpdateCount
                    + ", shipTo标记删除=" + spDeletedCount);

            JSONObject data = new JSONObject();
            data.put("total", custArray.size());
            data.put("successCount", successes.size());
            data.put("failCount", failures.size());
            data.put("successes", successes);
            data.put("failures", failures);

            logger.info("MYSE-13 客户主数据接收成功: total=" + custArray.size()
                    + ", success=" + successes.size() + ", fail=" + failures.size());
            return HttpUtil.success(data).toJSONString();

        } catch (Exception e) {
            logger.error("MYSE-13 客户主数据处理异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    /**
     * 构建 account 主记录对象（不落库）。existingId 非空为更新，否则为新建。
     * 校验失败抛 IllegalArgumentException，由调用方归集到 failures。
     */
    private Account buildAccount(JSONObject cust, Long existingId) {
        String kunnr = trimToNull(cust.getString("KUNNR"));
        if (kunnr == null || kunnr.isEmpty()) {
            throw new IllegalArgumentException("KUNNR 不能为空");
        }

        Account account = new Account();
        if (existingId != null) {
            account.setId(existingId);
        } else {
            // 标准对象 Account 创建时必须设置默认业务类型
            account.setEntityType(-11010000100001L);
        }

        // 客户名称：NAME1（中文名称，必填）
        String name1 = trimToNull(cust.getString("NAME1"));
        if (name1 == null || name1.isEmpty()) {
            throw new IllegalArgumentException("NAME1 客户名称不能为空");
        }
        account.setAccountName(name1);

        // 根级基础字段
        setAttr(account, "kunnr__c", kunnr);
        setAttr(account, "ktokd__c", cust.getString("KTOKD"));
        setAttr(account, "land1__c", cust.getString("LAND1"));
        setAttr(account, "lzone__c", cust.getString("LZONE"));
        setAttr(account, "spras__c", cust.getString("SPRAS"));

        // 名称3/名称4 拼接为完整英文名
        String name3 = trimToNull(cust.getString("NAME3"));
        String name4 = trimToNull(cust.getString("NAME4"));
        String fullName3 = (name3 == null ? "" : name3) + (name4 == null ? "" : name4);
        setAttr(account, "name3__c", fullName3.isEmpty() ? null : fullName3);
        setAttr(account, "stcd3__c", cust.getString("STCD3"));
        setAttr(account, "region__c", cust.getString("REGIO"));
        setAttr(account, "ort01__c", cust.getString("ORT01"));
        setAttr(account, "stras__c", cust.getString("STRAS"));
        setAttr(account, "patlz__c", cust.getString("PSTLZ"));
        setAttr(account, "sort1__c", cust.getString("SORT1"));
        setAttr(account, "zkunnr__c", cust.getString("ZKUNNR"));
        setAttr(account, "zname1__c", cust.getString("ZNAME1"));
        setAttr(account, "zstras__c", cust.getString("ZSTRAS"));
        setAttr(account, "stcd1__c", cust.getString("STCD1"));
        setAttr(account, "mailName__c", cust.getString("MAILNAME"));
        setAttr(account, "zipcode__c", cust.getString("ZIPCODE"));
        setAttr(account, "conper__c", cust.getString("CONPER"));

        // 映射图中存在、入参可能包含的其他根级字段
        setAttr(account, "aufsd__c", cust.getString("AUFSD"));
        setAttr(account, "faksd__c", cust.getString("FAKSD"));
        setAttr(account, "lifsd__c", cust.getString("LIFSD"));
        setAttr(account, "BRAN1__c", cust.getString("BRAN1"));
        setAttr(account, "businessId__c", cust.getString("BUSINESS_ID"));
        setAttr(account, "myseAdmin__c", cust.getString("MYSE_ADMIN"));
        setAttr(account, "mySeAdminPhone__c", cust.getString("MYSE_ADMIN_MOBILE"));
        setAttr(account, "newCode__c", cust.getString("NEWCODE"));
        setAttr(account, "uwaer__c", cust.getString("UWAER"));
        setAttr(account, "ykatr6__c", cust.getString("YKATR6"));
        setAttr(account, "ykatr7__c", cust.getString("YKATR7"));
        setAttr(account, "zflag__c", cust.getString("ZFLAG"));

        // 电话：TEL 为移动电话，TELF1/TEL_NUMBER 为首选电话号码
        setAttr(account, "tel__c", cust.getString("TEL"));
        String telF1 = firstNonEmpty(cust.getString("TELF1"), cust.getString("TEL_NUMBER"));
        setAttr(account, "telF1__c", telF1);
        setAttr(account, "fax", cust.getString("FAX_NUMBER"));

        // 银行：根级不存在时取 Bank 嵌套对象（Bank 可能是对象或数组）
        String bankA = firstNonEmpty(getStr(cust, "BANKA"), nestedBankValue(cust, "B_BANKA"));
        String bankN = firstNonEmpty(getStr(cust, "BANKN"), nestedBankValue(cust, "B_BANKN"));
        setAttr(account, "bankA__c", bankA);
        setAttr(account, "bankN__c", bankN);

        setAttr(account,"cassd__c",cust.getString("CASSD"));
        setAttr(account,"loevm__c", cust.getString("LOEVM"));

        return account;
    }

    /**
     * 一次 XOQL 批量反查已有账户：kunnr__c IN (...) → Map<kunnr, accountId>。
     */
    private Map<String, Long> findAccountIdsByKunnrs(List<String> kunnrs) {
        Map<String, Long> map = new HashMap<>();
        if (kunnrs == null || kunnrs.isEmpty()) {
            return map;
        }
        StringBuilder in = new StringBuilder();
        for (String k : kunnrs) {
            if (in.length() > 0) {
                in.append(",");
            }
            in.append("'").append(escape(k)).append("'");
        }
        String xoql = "SELECT id, kunnr__c FROM account WHERE kunnr__c IN (" + in + ")";
        try {
            QueryResult<JSONObject> qr = XoqlService.instance().query(xoql);
            if (qr != null && qr.getRecords() != null) {
                for (Object o : qr.getRecords()) {
                    if (o instanceof JSONObject) {
                        JSONObject j = (JSONObject) o;
                        Object id = j.get("id");
                        Object k = j.get("kunnr__c");
                        if (id != null && k != null) {
                            map.put(k.toString().trim(), Long.parseLong(id.toString()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("MYSE-13 批量反查账户失败，将全部按新建处理: " + e.getMessage());
        }
        return map;
    }

    /**
     * 首次推送（客户创建成功）时构建外部部门 department（。
     */
    public Department buildFirstPushDepartment(Account account, String kunnr, Long accountId,Long busiType) {
        Department dept = new Department();
        dept.setEntityType(busiType);
        dept.setDepartName(account.getAccountName());
        dept.setDepartCode(kunnr);
        dept.setParentDepartId(ParentDepartId);
        dept.setSpecialType(1);
        dept.setDepartType(2);
        dept.setActiveFlg(Boolean.TRUE);
        dept.setAttribute("relationAccount", accountId);
        return dept;
    }

    /**
     * 构建 CUST.SalesArea[] → accountSalesOrg__c 记录列表（不落库，由调用方批量插入）。
     */
    private List<AccountSalesOrg__c> buildSalesOrgs(Long accountId, JSONObject cust) {
        List<AccountSalesOrg__c> list = new ArrayList<>();
        JSONArray salesArea = cust.getJSONArray("SalesArea");
        if (salesArea == null || salesArea.isEmpty()) {
            return list;
        }
        Long busiType = resolveBusinessType("accountSalesOrg__c");
        for (int i = 0; i < salesArea.size(); i++) {
            JSONObject item = salesArea.getJSONObject(i);
            if (item == null) {
                continue;
            }
            AccountSalesOrg__c org = new AccountSalesOrg__c();
            org.setPurchaseId__c(accountId);                 // 关联回客户主记录
            if (busiType != null) {
                org.setEntityType(busiType);
            }

            // 销售组织（S_VKORG，如 N001）→ salesOrg__c（Long lookup，反查 Saleorg__c.id）
            String vkorg = trimToNull(item.getString("S_VKORG"));
            if (vkorg != null) {
                org.setName(cust.get("KUNNR") + "-" + vkorg);
                Long salesOrgId = resolveSaleOrg(vkorg);
                if (salesOrgId != null) {
                    org.setSalesOrg__c(salesOrgId);
                } else {
                    logger.warn("MYSE-13 未找到销售组织 Saleorg__c: code=" + vkorg);
                }
            } else {
                org.setName(cust.get("KUNNR") + "-salesorg" + i);
            }

            // 分销渠道（S_VTWEG）→ disChannel__c（Integer）
            Integer vtweg = parseInteger(item.getString("S_VTWEG"));
            if (vtweg != null) {
                org.setDisChannel__c(vtweg);
            }

            // 其余销售组织维度字段（SAP S_* → CRM s_*）
            setAttr(org, "s_Spart__c", item.getString("S_SPART"));
            setAttr(org, "s_Bzirk__c", item.getString("S_BZIRK"));
            setAttr(org, "s_Vkbur__c", item.getString("S_VKBUR"));
            setAttr(org, "s_Vkgrp__c", item.getString("S_VKGRP"));
            setAttr(org, "s_Kdgrp__c", item.getString("S_KDGRP"));
            setAttr(org, "s_Waers__c", item.getString("S_WAERS"));
            setAttr(org, "s_Zterm__c", item.getString("S_ZTERM"));
            setAttr(org, "s_Kalks__c", item.getString("S_KALKS"));
            setAttr(org, "s_Pltyp__c", item.getString("S_PLTYP"));
            setAttr(org, "s_Lprio__c", item.getString("S_LPRIO"));
            setAttr(org, "s_Vsbed__c", item.getString("S_VSBED"));
            setAttr(org, "s_Vwerk__c", item.getString("S_VWERK"));
            setAttr(org, "s_Antlf__c", item.getString("S_ANTLF"));
            setAttr(org, "s_Untto__c", item.getString("S_UNTTO"));
            setAttr(org, "s_Uebto__c", item.getString("S_UEBTO"));
            setAttr(org, "s_Inco1__c", item.getString("S_INCO1"));
            setAttr(org, "s_Kkber__c", item.getString("S_KKBER"));
            setAttr(org, "s_Kabss__c", item.getString("S_KABSS"));
            setAttr(org, "s_Ktgrd__c", item.getString("S_KTGRD"));
            setAttr(org, "s_Awahr__c", item.getString("S_AWAHR"));
            setAttr(org, "s_Kzazu__c", item.getString("S_KZAZU"));
            setAttr(org, "s_Taxkd__c", item.getString("S_TAXKD"));
            setAttr(org, "s_Aufsd__c", item.getString("S_AUFSD"));
            setAttr(org, "s_Faksd__c", item.getString("S_FAKSD"));
            setAttr(org, "s_Lifsd__c", item.getString("S_LIFSD"));
            setAttr(org, "s_Cassd__c", item.getString("S_CASSD"));
            setAttr(org, "s_Kztlf__c", item.getString("S_KZTLF"));
            setAttr(org, "s_Zflag__c", item.getString("S_ZFLAG"));
            setAttr(org, "s_Loevm__c", item.getString("S_LOEVM"));

            list.add(org);
        }
        return list;
    }

    /**
     * 构建 CUST.Partny_function[] → shipToParty__c 记录列表（不落库，由调用方按 id 分流新增/更新）。
     * <b>只处理 parvw=SH（发货方）</b>，WE（收货方）及其余功能（RE/RG/AG 等）全部跳过，
     * 避免同一客户堆出大量重复地址。
     * <p>
     * <b>唯一标识 = 账户(purchaseId__c) + F_KUNNR(kunnr__c)</b>：
     * 命中已有记录 → 回填 id 走<b>更新</b>；未命中 → 走新增。
     * 同一客户下同一 F_KUNNR 只保留一条（SAP 常按 VKORG/VTWEG/SPART 组合下发多行，
     * 同一 F_KUNNR 的重复行只留一条，后到的覆盖先到的）。
     * </p>
     *
     * @param existingSpByKey     预查的已有发货地址索引 key=accountId#F_KUNNR → id 列表（升序）
     * @param matchedSpKeys       （出参）本次命中的键，未命中的旧记录由调用方标记删除
     * @param spIdsToMarkDeleted  （出参）同一键下的历史重复记录 → 标记删除
     */
    private List<ShipToParty__c> buildShipToParties(Long accountId, JSONObject cust,
                                                    Map<String, Long> accountIdByKunnr,
                                                    Map<String, List<Long>> existingSpByKey,
                                                    Set<String> matchedSpKeys,
                                                    List<Long> spIdsToMarkDeleted) {
        List<ShipToParty__c> list = new ArrayList<>();
        JSONArray pf = cust.getJSONArray("Partny_function");
        if (pf == null || pf.isEmpty()) {
            return list;
        }
        Long busiType = resolveBusinessType("shipToParty__c");
        int skipped = 0;
        int noKeySkipped = 0;
        int dupSkipped = 0;
        // 同一 (账户 + F_KUNNR) 在本次报文内去重：后到的覆盖先到的，保证唯一键只产出一条
        Map<String, ShipToParty__c> byKey = new LinkedHashMap<>();
        for (int i = 0; i < pf.size(); i++) {
            JSONObject item = pf.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String parvw = getStr(item, "PARVW", "F_PARVW", "parvw");
            // 只保留 SH（发货方）：WE 及其余功能全部跳过
            if (parvw == null || !"SH".equalsIgnoreCase(parvw.trim())) {
                skipped++;
                continue;
            }
            // 唯一标识 F_KUNNR：缺失则无法参与匹配更新，跳过（否则每次推送都会新增重复记录）
            String kunn2 = getStr(item, "KUNN2", "F_KUNNR", "kunn2");
            if (kunn2 == null || kunn2.trim().isEmpty()) {
                noKeySkipped++;
                continue;
            }
            String spKey = accountId + "#" + kunn2.trim();

            ShipToParty__c sp = new ShipToParty__c();
            sp.setPurchaseId__c(accountId);                 // 关联回客户主记录
            if (busiType != null) {
                sp.setEntityType(busiType);
            }

            // 唯一标识：SAP F_KUNNR → kunnr__c（发货地址代码文本）
            sp.setKunnr__c(kunn2.trim());

            // 功能标识（PARVW，如 WE/SH）
            sp.setF_Parvw__c(parvw.trim());

            // 销售组织（VKORG）→ f_Vkorg__c（Long lookup，反查 Saleorg__c.id）
            String vkorg = getStr(item, "VKORG", "F_VKORG", "vkorg");
            if (vkorg != null) {
                Long salesOrgId = resolveSaleOrg(vkorg);
                if (salesOrgId != null) {
                    sp.setF_Vkorg__c(salesOrgId);
                } else {
                    logger.warn("MYSE-13 未找到合作伙伴销售组织 Saleorg__c: code=" + vkorg);
                }
            }

            // 分销渠道（VTWEG）→ f_Vtweg__c（Integer）
            Integer vtweg = parseInteger(getStr(item, "VTWEG", "F_VTWEG", "vtweg"));
            if (vtweg != null) {
                sp.setF_Vtweg__c(vtweg);
            }

            // 产品组（SPART）→ f_Spart__c（String）
            String spart = getStr(item, "SPART", "F_SPART", "spart");
            if (spart != null) {
                sp.setF_Spart__c(spart);
            }

            // 发货地址文本（shipToAddress__c）：按设计该字段为地址代码(customId__c)派生的展示值，
            // 不接受自由文本（写入后平台仍显示默认占位）。本接口暂留空，地址后续由人工维护或
            // 通过 customId__c（地址代码 lookup）补全。故此处不设置 shipToAddress__c。

            // 名称：客户号 - 功能码 - 合作伙伴客户号（F_KUNNR 已保证非空）
            StringBuilder nm = new StringBuilder();
            nm.append(cust.get("KUNNR")).append("-").append(parvw.trim()).append("-").append(kunn2.trim());
            Long customId = accountIdByKunnr.get(kunn2.trim());
            if (customId != null) {
                sp.setCustomId__c(customId);   // 送达方（lookup account）
            }
            sp.setName(nm.toString());

            // 匹配已有记录：命中 → 回填 id 走更新；同一键下多余的（历史重复）→ 标记删除
            List<Long> oldIds = existingSpByKey == null ? null : existingSpByKey.get(spKey);
            if (oldIds != null && !oldIds.isEmpty()) {
                sp.setId(oldIds.get(0));
                if (oldIds.size() > 1) {
                    spIdsToMarkDeleted.addAll(oldIds.subList(1, oldIds.size()));
                }
            }
            // 正常记录显式置为未删除（可复活此前被标记删除的）
            sp.setAttribute("isDeleted__c", 2);
            matchedSpKeys.add(spKey);
            if (byKey.containsKey(spKey)) {
                dupSkipped++;   // 同一 F_KUNNR 的 SAP 重复行，后到的覆盖先到的
            }
            byKey.put(spKey, sp);
        }
        // ===== 补建「自己客户号」默认发货地址 =====
        // 本次 SH 发货地址里若没有一条 kunnr__c(F_KUNNR) == 客户自己 KUNNR 的，
        // 默认补建一条「送达方=自己」的发货地址。参与唯一键匹配，幂等不产生重复。
        String selfKunnr = trimToNull(cust.getString("KUNNR"));
        if (selfKunnr != null) {
            String selfKey = accountId + "#" + selfKunnr;
            if (!byKey.containsKey(selfKey)) {
                ShipToParty__c selfSp = new ShipToParty__c();
                selfSp.setPurchaseId__c(accountId);
                if (busiType != null) {
                    selfSp.setEntityType(busiType);
                }
                selfSp.setKunnr__c(selfKunnr);
                selfSp.setF_Parvw__c("SH");
                Long selfAccountId = accountIdByKunnr.get(selfKunnr);
                if (selfAccountId == null) {
                    selfAccountId = accountId;
                }
                selfSp.setCustomId__c(selfAccountId);   // 送达方 = 自己
                selfSp.setName(selfKunnr + "-SH-" + selfKunnr);
                selfSp.setAttribute("isDeleted__c", 2);
                // 参与唯一键匹配：已有则回填 id 走更新，多余历史重复标记删除
                List<Long> selfOldIds = existingSpByKey == null ? null : existingSpByKey.get(selfKey);
                if (selfOldIds != null && !selfOldIds.isEmpty()) {
                    selfSp.setId(selfOldIds.get(0));
                    if (selfOldIds.size() > 1) {
                        spIdsToMarkDeleted.addAll(selfOldIds.subList(1, selfOldIds.size()));
                    }
                }
                matchedSpKeys.add(selfKey);
                byKey.put(selfKey, selfSp);
                logger.info("MYSE-13 KUNNR=" + selfKunnr + " 本次未下发自己客户号发货地址，补建默认地址");
            }
        }

        list.addAll(byKey.values());
        if (skipped > 0) {
            logger.info("MYSE-13 KUNNR=" + cust.get("KUNNR") + " 跳过非 SH 合作伙伴功能 " + skipped
                    + " 条（只落发货方 SH）");
        }
        if (dupSkipped > 0) {
            logger.info("MYSE-13 KUNNR=" + cust.get("KUNNR") + " F_KUNNR 重复行去重 " + dupSkipped + " 条");
        }
        if (noKeySkipped > 0) {
            logger.warn("MYSE-13 KUNNR=" + cust.get("KUNNR") + " 跳过缺少 KUNN2/F_KUNNR 的合作伙伴功能 "
                    + noKeySkipped + " 条（无唯一标识，无法匹配更新）");
        }
        return list;
    }

    /**
     * 平台内 XoqlService.query 对自定义子对象在当前 API 上下文无法读取，
     * 故改用 RkhdHttpClient 调用平台内部 XOQL（系统鉴权）批量查询旧子记录：
     * purchaseId__c IN (accountIds) → Map<accountId, List<子记录id>>。
     */
    private Map<Long, List<Long>> querySubIdsByAccountIds(String api, List<Long> accountIds) {
        Map<Long, List<Long>> map = new HashMap<>();
        if (accountIds == null || accountIds.isEmpty()) {
            return map;
        }
        StringBuilder in = new StringBuilder();
        for (Long id : accountIds) {
            if (in.length() > 0) {
                in.append(",");
            }
            in.append(id);
        }
        String xoql = "SELECT id, purchaseId__c FROM " + api + " WHERE purchaseId__c IN (" + in + ")";
        try {
            RkhdHttpData httpData = RkhdHttpData.newBuilder()
                    .callString("/rest/data/v2.0/query/xoql")
                    .callType("POST")
                    .formData("xoql", xoql)
                    .build();
            String respStr = RkhdHttpClient.instance().performRequest(httpData);
            if (respStr != null && !respStr.trim().isEmpty()) {
                JSONObject resp = JSON.parseObject(respStr);
                JSONArray records = extractRecords(resp);
                if (records != null) {
                    for (int i = 0; i < records.size(); i++) {
                        JSONObject j = records.getJSONObject(i);
                        Object id = j.get("id");
                        Object pid = j.get("purchaseId__c");
                        if (id != null && pid != null) {
                            Long aid = Long.parseLong(pid.toString());
                            List<Long> l = map.get(aid);
                            if (l == null) {
                                l = new ArrayList<>();
                                map.put(aid, l);
                            }
                            l.add(Long.parseLong(id.toString()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("MYSE-13 批量查询旧子记录失败 api=" + api + " : " + e.getMessage());
        }
        return map;
    }

    /** 把 Map<accountId, List<id>> 展平成 id 列表。 */
    private List<Long> flatten(Map<Long, List<Long>> map) {
        List<Long> all = new ArrayList<>();
        if (map != null) {
            for (List<Long> l : map.values()) {
                all.addAll(l);
            }
        }
        return all;
    }

    /**
     * 分批批量插入子记录。单条失败仅记日志（保持原"子表失败不影响主记录"语义）。
     * @return 成功条数
     */
    private <T extends XObject> int batchInsert(List<T> list, String label) {
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int success = 0;
        for (int i = 0; i < list.size(); i += BATCH_SIZE) {
            List<T> part = new ArrayList<>(list.subList(i, Math.min(i + BATCH_SIZE, list.size())));
            try {
                BatchOperateResult br = XObjectService.instance().insert(part, true,true);
                List<OperateResult> ops = br == null ? null : br.getOperateResults();
                if (ops != null) {
                    for (OperateResult op : ops) {
                        if (op != null && Boolean.TRUE.equals(op.getSuccess())) {
                            success++;
                        } else if (op != null) {
                            logger.error("MYSE-13 插入" + label + "失败: " + op.getErrorMessage());
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("MYSE-13 批量插入" + label + "异常: " + e.getMessage(), e);
            }
        }
        return success;
    }

    /**
     * 分批批量删除子记录 stub。单条失败仅记日志。
     * 注意：不能用反射构造（沙盒安全管理器禁止 getDeclaredConstructor → AccessControlException），
     * stub 由调用方直接 new 具体模型类构建（见 orgStubs / spStubs）。
     * @return 删除条数
     */
    private <T extends XObject> int batchDeleteStubs(List<T> stubs, String label) {
        if (stubs == null || stubs.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (int i = 0; i < stubs.size(); i += BATCH_SIZE) {
            List<T> part = new ArrayList<>(stubs.subList(i, Math.min(i + BATCH_SIZE, stubs.size())));
            try {
                BatchOperateResult br = XObjectService.instance().delete(part,true);
                List<OperateResult> ops = br == null ? null : br.getOperateResults();
                if (ops != null) {
                    for (OperateResult op : ops) {
                        if (op != null && Boolean.TRUE.equals(op.getSuccess())) {
                            deleted++;
                        } else if (op != null) {
                            logger.error("MYSE-13 删除" + label + "失败: " + op.getErrorMessage());
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("MYSE-13 批量删除" + label + "异常: " + e.getMessage(), e);
            }
        }
        logger.info("MYSE-13 删除" + label + "成功 数量=" + deleted);
        return deleted;
    }

    /**
     * 分批批量更新子记录（记录需已回填 id）。单条失败仅记日志。
     * @return 成功条数
     */
    private <T extends XObject> int batchUpdate(List<T> list, String label) {
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int success = 0;
        for (int i = 0; i < list.size(); i += BATCH_SIZE) {
            List<T> part = new ArrayList<>(list.subList(i, Math.min(i + BATCH_SIZE, list.size())));
            try {
                BatchOperateResult br = XObjectService.instance().update(part);
                List<OperateResult> ops = br == null ? null : br.getOperateResults();
                if (ops != null) {
                    for (OperateResult op : ops) {
                        if (op != null && Boolean.TRUE.equals(op.getSuccess())) {
                            success++;
                        } else if (op != null) {
                            logger.error("MYSE-13 更新" + label + "失败: " + op.getErrorMessage());
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("MYSE-13 批量更新" + label + "异常: " + e.getMessage(), e);
            }
        }
        logger.info("MYSE-13 更新" + label + "成功 数量=" + success);
        return success;
    }

    /**
     * 标记删除发货地址（<b>不物理删除</b>）：仅置 isDeleted__c = 1。
     * 适用：SAP 本次未下发的旧记录 + 同一唯一键下的历史重复记录。
     */
    private int batchMarkDeleted(List<Long> ids, String label) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<ShipToParty__c> stubs = new ArrayList<>();
        for (Long id : ids) {
            ShipToParty__c stub = new ShipToParty__c();
            stub.setId(id);
            stub.setAttribute("isDeleted__c", 1);
            stubs.add(stub);
        }
        return batchUpdate(stubs, label + "(标记删除)");
    }

    /**
     * 预查已有发货地址，按<b>唯一键（账户 + F_KUNNR）</b>建索引。
     * <p>
     * 与 querySubIdsByAccountIds 同样走 RkhdHttpClient 内部 XOQL（自定义子对象在当前 API 上下文读不到）。
     * </p>
     *
     * @return key = accountId + "#" + kunnr__c；value = 记录 id 列表（升序，首条为更新目标，其余视为重复）
     */
    private Map<String, List<Long>> queryExistingShipToParties(List<Long> accountIds) {
        Map<String, List<Long>> map = new LinkedHashMap<>();
        if (accountIds == null || accountIds.isEmpty()) {
            return map;
        }
        StringBuilder in = new StringBuilder();
        for (Long id : accountIds) {
            if (in.length() > 0) {
                in.append(",");
            }
            in.append(id);
        }
        String xoql = "SELECT id, purchaseId__c, kunnr__c FROM shipToParty__c"
                + " WHERE purchaseId__c IN (" + in + ")";
        try {
            RkhdHttpData httpData = RkhdHttpData.newBuilder()
                    .callString("/rest/data/v2.0/query/xoql")
                    .callType("POST")
                    .formData("xoql", xoql)
                    .build();
            String respStr = RkhdHttpClient.instance().performRequest(httpData);
            if (respStr != null && !respStr.trim().isEmpty()) {
                JSONObject resp = JSON.parseObject(respStr);
                JSONArray records = extractRecords(resp);
                if (records != null) {
                    for (int i = 0; i < records.size(); i++) {
                        JSONObject j = records.getJSONObject(i);
                        Object id = j.get("id");
                        Object pid = j.get("purchaseId__c");
                        if (id == null || pid == null) {
                            continue;
                        }
                        Object kn = j.get("kunnr__c");
                        String kunnr = (kn == null) ? "" : kn.toString().trim();
                        if (kunnr.isEmpty()) {
                            // 无唯一标识的历史数据：不参与匹配，也不自动标记删除（避免误伤人工数据）
                            logger.warn("MYSE-13 已有发货地址缺少 kunnr__c，不参与匹配 id=" + id);
                            continue;
                        }
                        String key = pid.toString().trim() + "#" + kunnr;
                        List<Long> l = map.get(key);
                        if (l == null) {
                            l = new ArrayList<>();
                            map.put(key, l);
                        }
                        l.add(Long.parseLong(id.toString()));
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("MYSE-13 预查已有发货地址失败: " + e.getMessage());
        }
        // 升序：首条作为更新目标，其余为历史重复（由 buildShipToParties 收集标记删除）
        for (List<Long> l : map.values()) {
            Collections.sort(l);
        }
        logger.info("MYSE-13 已有发货地址唯一键数=" + map.size());
        return map;
    }

    /** 按 id 构建客户销售组织删除 stub 列表。 */
    private List<AccountSalesOrg__c> orgStubs(List<Long> ids) {
        List<AccountSalesOrg__c> stubs = new ArrayList<>();
        for (Long id : ids) {
            AccountSalesOrg__c stub = new AccountSalesOrg__c();
            stub.setId(id);
            stubs.add(stub);
        }
        return stubs;
    }

    // ==================== 以下为工具方法 ====================

    /**
     * 大小写兼容地写入对象字段（值为 null/空白时跳过，不入库）。
     */
    private void setAttr(XObject obj, String field, String value) {
        if (value == null) {
            return;
        }
        value = value.trim();
        if (value.isEmpty()) {
            return;
        }
        obj.setAttribute(field, value);
    }

    private String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return null;
    }

    private String nestedBankValue(JSONObject cust, String key) {
        // Bank 在 SAP 报文中可能是对象，也可能是数组（多银行账户）；两种形态都兼容，取首条
        Object bankObj = cust.get("Bank");
        if (bankObj == null) {
            return null;
        }
        JSONObject bank = null;
        if (bankObj instanceof JSONObject) {
            bank = (JSONObject) bankObj;
        } else if (bankObj instanceof JSONArray) {
            JSONArray arr = (JSONArray) bankObj;
            if (!arr.isEmpty()) {
                Object first = arr.get(0);
                if (first instanceof JSONObject) {
                    bank = (JSONObject) first;
                }
            }
        }
        if (bank == null) {
            return null;
        }
        String v = bank.getString(key);
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    /**
     * 大小写兼容地读取 JSON 对象中的字符串字段（SAP 报文可能大写也可能小写）。
     * 依次尝试候选 key，返回第一个非空值；都不存在返回 null。
     */
    private String getStr(JSONObject obj, String... candidates) {
        if (obj == null) {
            return null;
        }
        for (String c : candidates) {
            Object v = obj.get(c);
            if (v != null) {
                String s = v.toString().trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    /**
     * 销售组织全量缓存预热。saleorg__c 是主数据小表（几十条），一次全量拉取后
     * 建立 name / companyCode__c 两个索引，后续 resolveSaleOrg 全部走内存、零查询。
     * <p>
     * 静态缓存跨请求复用；带 30 分钟 TTL，保证销售组织主数据新增/变更后可自动刷新。
     * 加载失败时同样打上时间戳（本轮不再重试，避免失败场景反复查询把配额打爆），
     * 该轮销售组织字段留空并记日志，不影响客户主记录与其余子表落库。
     */
    private void ensureSaleOrgCache() {
        long now = System.currentTimeMillis();
        if (SALES_ORG_LOADED_AT > 0L && (now - SALES_ORG_LOADED_AT) < SALES_ORG_CACHE_TTL) {
            return;
        }

        synchronized (Myse13CustomerMasterApi.class) {
            // 双重检查：并发请求下只加载一次
            now = System.currentTimeMillis();
            if (SALES_ORG_LOADED_AT > 0L && (now - SALES_ORG_LOADED_AT) < SALES_ORG_CACHE_TTL) {
                return;
            }
            Map<String, Long> byName = new HashMap<>();
            Map<String, Long> byCode = new HashMap<>();
            String xoql = "SELECT id, name, companyCode__c FROM saleorg__c LIMIT " + SALES_ORG_MAX_ROWS;
            boolean ok = loadSaleOrgByInternalXoql(xoql, byName, byCode);
            if (!ok) {
                ok = loadSaleOrgByXoqlService(xoql, byName, byCode);
            }
            if (ok && (!byName.isEmpty() || !byCode.isEmpty())) {
                SALES_ORG_BY_NAME.clear();
                SALES_ORG_BY_CODE.clear();
                SALES_ORG_BY_NAME.putAll(byName);
                SALES_ORG_BY_CODE.putAll(byCode);
                SALES_ORG_LOADED_AT = System.currentTimeMillis();
                logger.info("MYSE-13 销售组织全量缓存加载完成: name索引=" + byName.size()
                        + ", companyCode索引=" + byCode.size());
            } else {
                // 加载失败也打时间戳，本轮（TTL 内）不再重试，防止每次请求都查
                SALES_ORG_LOADED_AT = System.currentTimeMillis();
                logger.error("MYSE-13 销售组织全量缓存加载失败，本轮销售组织字段将留空");
            }
        }
    }

    /**
     * 通道一：RkhdHttpClient 调平台内部 XOQL（系统鉴权，可绕过 API 上下文对象级权限隔离）。
     * @return 是否查询成功（成功但 0 条也返回 true，由调用方按条数判断）
     */
    private boolean loadSaleOrgByInternalXoql(String xoql, Map<String, Long> byName, Map<String, Long> byCode) {
        try {
            RkhdHttpData httpData = RkhdHttpData.newBuilder()
                    .callString("/rest/data/v2.0/query/xoql")
                    .callType("POST")
                    .formData("xoql", xoql)
                    .build();
            String respStr = RkhdHttpClient.instance().performRequest(httpData);
            if (respStr == null || respStr.trim().isEmpty()) {
                return false;
            }
            JSONObject resp = JSON.parseObject(respStr);
            JSONArray records = extractRecords(resp);
            if (records == null) {
                return false;
            }
            collectSaleOrgRecords(records, byName, byCode);
            return true;
        } catch (Exception e) {
            logger.warn("MYSE-13 内部XOQL加载销售组织失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 通道二：XoqlService 直接查询（内部 XOQL 不可用时的兜底）。
     */
    private boolean loadSaleOrgByXoqlService(String xoql, Map<String, Long> byName, Map<String, Long> byCode) {
        try {
            QueryResult<JSONObject> qr = XoqlService.instance().query(xoql);
            if (qr == null || qr.getRecords() == null) {
                return false;
            }
            JSONArray records = new JSONArray();
            for (Object o : qr.getRecords()) {
                if (o instanceof JSONObject) {
                    records.add((JSONObject) o);
                }
            }
            collectSaleOrgRecords(records, byName, byCode);
            return true;
        } catch (Exception e) {
            logger.warn("MYSE-13 XoqlService加载销售组织失败: " + e.getMessage());
            return false;
        }
    }

    /** 把 XOQL 返回的记录整理进两个索引（id 可能是字符串，统一 Long.parseLong 兼容）。 */
    private void collectSaleOrgRecords(JSONArray records, Map<String, Long> byName, Map<String, Long> byCode) {
        for (int i = 0; i < records.size(); i++) {
            JSONObject j = records.getJSONObject(i);
            if (j == null) {
                continue;
            }
            Long id = null;
            try {
                Object rid = j.get("id");
                if (rid != null) {
                    id = Long.parseLong(rid.toString().trim());
                }
            } catch (Exception e) {
                id = null;
            }
            if (id == null) {
                continue;
            }
            String name = trimToNull(j.getString("name"));
            if (name != null) {
                byName.put(name, id);
            }
            String companyCode = trimToNull(j.getString("companyCode__c"));
            if (companyCode != null) {
                byCode.put(companyCode, id);
            }
        }
    }

    /**
     * 按销售组织代码（如 N001）解析 Saleorg__c.id：优先 name 索引，其次 companyCode__c 索引。
     * <p>
     * 走全量缓存（ensureSaleOrgCache），<b>不再逐码查库</b>，因此无论本批 50 条客户出现多少个
     * 不同 VKORG，销售组织解析的查询次数恒为 0（缓存未加载/过期时为 1）。
     * 未命中返回 null（业务上允许 salesOrg__c 留空，由数据完整性问题单独跟踪日志）。
     */
    private Long resolveSaleOrg(String code) {
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        code = code.trim();
        ensureSaleOrgCache();
        Long id = SALES_ORG_BY_NAME.get(code);
        if (id == null) {
            id = SALES_ORG_BY_CODE.get(code);
        }
        if (id == null) {
            logger.warn("MYSE-13 未命中销售组织缓存: code=" + code
                    + "（请确认 saleorg__c 存在该 name/companyCode__c）");
        }
        return id;
    }

    /**
     * 运行时获取自定义对象的业务类型 id（租户专属，不硬编码）。通过元数据接口查询，带静态缓存。
     * 若元数据接口在当前上下文不可达则返回 null（交由平台使用默认业务类型）。
     */
    private Long resolveBusinessType(String entityApiName) {
        if (BUSI_TYPE_CACHE.containsKey(entityApiName)) {
            return BUSI_TYPE_CACHE.get(entityApiName);
        }
        Long id = null;
        try {
            RkhdHttpData httpData = RkhdHttpData.newBuilder()
                    .callString("/rest/metadata/v3.1/xobjects/" + entityApiName + "/busiTypes")
                    .callType("GET")
                    .build();
            String respStr = RkhdHttpClient.instance().performRequest(httpData);
            if (respStr != null && !respStr.trim().isEmpty()) {
                JSONObject resp = JSON.parseObject(respStr);
                JSONArray arr = null;
                if (resp.containsKey("data") && resp.get("data") instanceof JSONArray) {
                    arr = (JSONArray) resp.get("data");
                } else if (resp.containsKey("records") && resp.get("records") instanceof JSONArray) {
                    arr = (JSONArray) resp.get("records");
                } else if (resp.containsKey("data") && resp.get("data") instanceof JSONObject) {
                    JSONObject d = resp.getJSONObject("data");
                    if (d.containsKey("records") && d.get("records") instanceof JSONArray) {
                        arr = (JSONArray) d.get("records");
                    }
                }
                if (arr != null && !arr.isEmpty()) {
                    Object bid = arr.getJSONObject(0).get("id");
                    if (bid != null) {
                        id = Long.parseLong(bid.toString());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("MYSE-13 解析业务类型失败 entity=" + entityApiName + " : " + e.getMessage());
        }
        BUSI_TYPE_CACHE.put(entityApiName, id);
        return id;
    }

    /**
     * 从 XOQL REST 响应中提取 records 数组，兼容多种返回结构。
     */
    private JSONArray extractRecords(JSONObject resp) {
        if (resp == null) {
            return null;
        }
        if (resp.containsKey("records") && resp.get("records") instanceof JSONArray) {
            return (JSONArray) resp.get("records");
        }
        if (resp.containsKey("data")) {
            Object d = resp.get("data");
            if (d instanceof JSONArray) {
                return (JSONArray) d;
            }
            if (d instanceof JSONObject) {
                JSONObject dj = (JSONObject) d;
                if (dj.containsKey("records") && dj.get("records") instanceof JSONArray) {
                    return (JSONArray) dj.get("records");
                }
            }
        }
        return null;
    }

    private Integer parseInteger(String v) {
        if (v == null) {
            return null;
        }
        v = v.trim();
        if (v.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String escape(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("'", "\\'");
    }

}
