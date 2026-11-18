package other.xsy.myse.apis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.exception.CustomConfigException;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.BatchOperateResult;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.model.XObject;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.service.CustomConfigService;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;
import com.rkhd.platform.sdk.http.RkhdHttpClient;
import com.rkhd.platform.sdk.http.RkhdHttpData;
import com.rkhd.platform.sdk.data.model.Product;
import com.rkhd.platform.sdk.data.model.ProductOrg__c;
import com.rkhd.platform.sdk.data.model.ProductPack__c;
import other.xsy.myse.utils.HttpUtil;
import static other.xsy.myse.utils.JsonValues.normalizeToArray;
import static other.xsy.myse.utils.JsonValues.asLongValue;
import other.xsy.myse.utils.QueryUtil;
import other.xsy.myse.utils.StringUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MYSE-11 物料主数据推送
 * <p>
 * 接收 SAP IDoc（Message type: ZGMATMAS）推送物料主数据。
 * 方向：SAP → mySE（被动接收）
 * </p>
 * <p><b>销售易 API 路径</b>：/rest/data/v2.0/scripts/api/myse/master-data/material</p>
 *
 */

@RestApi(baseUrl = "/myse")
public class Myse11MaterialMasterApi {

    private static final Logger logger = LoggerFactory.getLogger();

    /** 日志标识 */
    private static final String TAG = "MYSE-11";

    /** 子表批量写入单批最大条数 */
    private static final int CHILD_BATCH_SIZE = 200;

    /**
     * 产品业务类型（entityType）。取自租户现有产品的统一值。
     */
    private static final Long PRODUCT_BUSI_TYPE ;

    /**
     * 产品父节点（parentId）。租户所有产品共用同一根父节点。
     */
    private static final Long PRODUCT_PARENT_ID;

    /**
     * 产品组织（productOrg__c）业务类型（默认业务类型）。
     */
    private static final Long PRODUCT_ORG_BUSI_TYPE;

    // ==================== 分销渠道选项集缓存（唯一键归一用） ====================

    /** disChannel__c 的 label → value 映射（静态缓存） */
    private static final Map<String, Integer> DIS_CHANNEL_LABEL_TO_VALUE = new HashMap<String, Integer>();

    /** 选项集缓存加载时间戳（毫秒） */
    private static volatile long disChannelMapAt = 0L;

    /** 选项集缓存有效期（30 分钟） */
    private static final long PICK_MAP_TTL = 30L * 60L * 1000L;

    /**
     * 产品包装（productPack__c）业务类型（默认业务类型）。
     */
    private static final Long PRODUCT_PACK_BUSI_TYPE;

    static {
        try {
            // 获取对象实例
            CustomConfigService instance = CustomConfigService.instance();
            // 获取配置集,返回此配置集中的所有配置项
            Map<String, String> configSet = instance.getConfigSet("entityType");
            PRODUCT_BUSI_TYPE=Long.parseLong(configSet.get("PRODUCT_BUSI_TYPE"));
            PRODUCT_PARENT_ID=Long.parseLong(configSet.get("PRODUCT_PARENT_ID"));
            PRODUCT_ORG_BUSI_TYPE=Long.parseLong(configSet.get("PRODUCT_ORG_BUSI_TYPE"));
            PRODUCT_PACK_BUSI_TYPE=Long.parseLong(configSet.get("PRODUCT_PACK_BUSI_TYPE"));
        } catch (CustomConfigException e) {
            throw new RuntimeException(e);
        }
    }

    @RestMapping(value = "/master-data/material", method = RequestMethod.POST)
    public String execute(@RestBeanParam(name = "json") String body) {
        try {
            if (body == null || body.isEmpty()) {
                logger.warn(TAG + " 请求体为空，忽略");
                return HttpUtil.success(null).toJSONString();
            }
            JSONObject requestBody = JSON.parseObject(body);

            // MATERIAL 根节点兼容 单对象（历史报文）/ 数组（批量推送），统一归一化后循环处理
            Object materialNode = requestBody.get("MATERIAL");
            JSONArray materials = normalizeToArray(materialNode);
            if (materials == null || materials.isEmpty()) {
                logger.warn(TAG + " MATERIAL 根节点为空，忽略");
                return HttpUtil.success(null).toJSONString();
            }

            // 入参日志：单条打全量，批量只打条数（避免日志膨胀）
            if (materialNode instanceof JSONObject) {
                logger.info(TAG + " 入参(单条): " + requestBody.toJSONString());
            } else {
                logger.info(TAG + " 入参(批量): 条数=" + materials.size());
            }

            // ====== 预查询：收集全量物料涉及的销售组织编码 / 物料号，一次 IN 批量查出（禁止循环内逐条查询）======
            Map<String, Long> salesOrgMap = querySaleOrgIdsByNames(collectSalesOrgNames(materials));
            Map<String, Long> productIdMap = queryProductIdsByCodes(collectMaterialNos(materials));

            // ====== 逐条处理：单条失败不影响其它，成功/失败只记 log ======
            int successCount = 0;
            for (int i = 0; i < materials.size(); i++) {
                JSONObject material = materials.getJSONObject(i);
                String materialNo = material == null ? null : material.getString("MATERIAL_NO");
                try {
                    if (material == null) {
                        throw new RuntimeException("MATERIAL 数组元素为空");
                    }
                    processOne(material, salesOrgMap, productIdMap);
                    successCount++;
                } catch (Exception e) {
                    logger.error(TAG + " 物料处理失败 materialNo=" + materialNo + " err=" + e.getMessage(), e);
                }
            }
            logger.info(TAG + " 推送完成: total=" + materials.size()
                    + " success=" + successCount + " fail=" + (materials.size() - successCount));
            return HttpUtil.success(null).toJSONString();

        } catch (Exception e) {
            logger.error(TAG + " 物料主数据处理异常", e);
            return HttpUtil.success(null).toJSONString();
        }
    }

    /**
     * 处理单条物料：upsert 产品 + 整组替换子表（产品组织/产品包装）。
     * 失败抛异常由调用方记 log。
     */
    private void processOne(JSONObject material,
                            Map<String, Long> salesOrgMap, Map<String, Long> productIdMap) throws Exception {
        // ====== 必填：物料号 ======
        String materialNo = material.getString("MATERIAL_NO");
        if (materialNo == null || materialNo.trim().isEmpty()) {
            throw new RuntimeException("MATERIAL_NO 不能为空");
        }
        materialNo = materialNo.trim();

        // ====== 控制字段（仅日志，不落库）======
        logger.info(TAG + " 处理物料: materialNo=" + materialNo
                + ", IDOC_NUMBER=" + material.getString("IDOC_NUMBER")
                + ", MSGTYPE=" + material.getString("MSGTYPE"));

        // ====== 产品基础字段 ======
        String materialDesc = material.getString("MATERIAL_DESC");
        Double weight = parseDouble(material.getString("Weight"));
        String weightUnit = material.getString("WeightUnit");
        Double volume = parseDouble(material.getString("Volume"));
        String volumeUnit = material.getString("VolumeUnit");

        Boolean masterPact = false;
        if(StringUtil.isNotBlank(material.getString("MasterPact"))){
            masterPact=true;
        }

        String materialGrp = material.getString("MATERIAL_GRP");
        String division = material.getString("DIVISION");

        // ====== upsert 产品 ======
        Long productId = upsertProduct(material, materialNo, materialDesc,
                weight, weightUnit, volume, volumeUnit,
                masterPact, materialGrp, division, productIdMap);

        // ====== 子表：产品组织 SALES_AREA_INFO ======
        // SAP 单条时传对象、多条时传数组，统一归一化为数组
        JSONArray salesArea = normalizeToArray(material.get("SALES_AREA_INFO"));
        if (salesArea != null && !salesArea.isEmpty()) {
            syncProductOrgs(productId, salesArea, materialNo, salesOrgMap);
        }

        // ====== 子表：产品包装 MATERIAL_UNITS ======
        JSONArray units = normalizeToArray(material.get("MATERIAL_UNITS"));
        if (units != null && !units.isEmpty()) {
            syncProductPacks(productId, units);
        }

        logger.info(TAG + " 物料处理成功: materialNo=" + materialNo);
    }

    /**
     * upsert 产品记录（按 productCode__c = materialNo，存在性由预查询 Map 提供）。返回产品 id。
     * 新增成功后回填 Map，防止同批重复物料号二次 insert。
     */
    private Long upsertProduct(JSONObject material, String materialNo, String materialDesc,
                               Double weight, String weightUnit, Double volume, String volumeUnit,
                               Boolean masterPact, String materialGrp,
                               String division, Map<String, Long> productIdMap) throws Exception {
        Long existingId = productIdMap.get(materialNo);
        boolean isUpdate = existingId != null;
        Product product = new Product();
        if (isUpdate) {
            product.setId(existingId);
        } else {
            product.setEntityType(PRODUCT_BUSI_TYPE);
            product.setParentId(PRODUCT_PARENT_ID);
        }
        product.setUnit("无");
        product.setProductName(materialNo);
        product.setProDescription__c(materialDesc);
        product.setProductCode__c(materialNo);
        product.setKzkfg__c(masterPact);
        product.setMatkl__c(materialGrp);
        if (weight != null) {
            product.setWeight__c(weight);
        }
        if (weightUnit != null) {
            product.setWeightUnit__c(weightUnit);
        }
        if (volume != null) {
            product.setVolume__c(volume);
        }
        if (volumeUnit != null) {
            product.setVolumeUnit__c(volumeUnit);
        }
        product.setBwkey__c(material.getString("PLANT"));
        product.setSpart__c(division);
        OperateResult result;
        try {
            if (isUpdate) {
                result = XObjectService.instance().update(product);
            } else {
                result = XObjectService.instance().insert(product, true);
            }
        } catch (Exception e) {
            throw new RuntimeException("保存产品失败 materialNo=" + materialNo + ": " + e.getMessage(), e);
        }
        if (!Boolean.TRUE.equals(result.getSuccess())) {
            throw new RuntimeException("保存产品失败 materialNo=" + materialNo + ": " + result.getErrorMessage());
        }
        Long productId = isUpdate ? existingId : result.getDataId();
        if (productId == null) {
            throw new RuntimeException("产品保存后未获取到 id materialNo=" + materialNo);
        }
        if (!isUpdate) {
            // 新增成功回填 Map，防止同批重复物料号二次 insert
            productIdMap.put(materialNo, productId);
        }
        logger.info(TAG + (isUpdate ? " 产品已存在，更新: " : " 新增产品: ") + "materialNo=" + materialNo);
        return productId;
    }

    /**
     * 同步产品组织（SALES_AREA_INFO → productOrg__c）。
     *
     * <p><b>唯一键 = product__c + salesOrg__c + disChannel__c</b>（老板 2026-10-23 明确）：
     * 命中已有记录 → setId 走<b>更新</b>；未命中 → 新增。
     * <b>不再整组删除</b>（老板 2026-10-23：接过来不能直接全部删除了），
     * 本次报文未下发的旧记录<b>保留</b>。</p>
     *
     * <p>三个字段都要<b>归一到同一形态</b>才能对上：
     * salesOrg__c 是 reference（库里取出是记录 id），disChannel__c 是 picklist
     * （库里用 XOQL 取出来是<b>label</b> 如 "01"，而报文侧是<b>选项 value</b> 1/2/3），
     * 因此统一用 {@link #normalizeDisChannel(Object)} 归一到选项 value。</p>
     */
    private void syncProductOrgs(Long productId, JSONArray salesArea, String materialNo,
                                 Map<String, Long> salesOrgMap) throws Exception {
        // 已有记录：唯一键 → 记录 id
        Map<String, Long> existing = queryExistingProductOrgs(productId);

        // 本次报文构建：唯一键 → 记录（同一唯一键后出现的覆盖先出现的）
        Map<String, ProductOrg__c> byKey = new LinkedHashMap<String, ProductOrg__c>();
        for (int i = 0; i < salesArea.size(); i++) {
            JSONObject item = salesArea.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String salesOrgCode = item.getString("SALES_ORG");
            Long salesOrgId = null;
            if (salesOrgCode != null && !salesOrgCode.trim().isEmpty()) {
                salesOrgId = salesOrgMap.get(salesOrgCode.trim());
                if (salesOrgId == null) {
                    logger.warn(TAG + " 未找到销售组织 saleorg__c: name=" + salesOrgCode);
                }
            }
            Integer channel = parseInteger(item.getString("CHANNEL"));

            String key = productOrgKey(productId, salesOrgId, channel);
            if (byKey.containsKey(key)) {
                logger.warn(TAG + " 同批重复(产品+销售组织+渠道)，后一条覆盖前一条: key=" + key);
            }

            ProductOrg__c org = new ProductOrg__c();
            org.setProduct__c(productId);

            if (salesOrgCode != null && !salesOrgCode.trim().isEmpty()) {
                org.setName(materialNo + "-" + salesOrgCode);
                if (salesOrgId != null) {
                    org.setSalesOrg__c(salesOrgId);
                }
            } else {
                org.setName(materialNo + "-org" + i);
            }

            if (channel != null) {
                org.setDisChannel__c(channel);
            }
            String v;
            if ((v = item.getString("PRICE_GRP")) != null) org.setMaterialPriceGroup__c(v);
            if ((v = item.getString("DISTRI_STATUS")) != null) org.setDistriStatus__c(v);
            if ((v = item.getString("SALES_UNIT")) != null) org.setSalesUnit__c(v);
            if ((v = item.getString("CAT_GROUP")) != null) org.setProductCategoryGroup__c(v);
            if ((v = item.getString("DispatchCenter")) != null) org.setShippingCenter__c(v);
            if ((v = item.getString("SoldOnInternet")) != null) org.setInternetSales__c(v);
            if ((v = item.getString("RestrictionPrice")) != null) org.setPriceRestriction__c(v);
            if ((v = item.getString("MATERIAL_GROUP2")) != null) org.setMvgr2__c(v);
            if ((v = item.getString("MOQ")) != null) {
                Double moq=Double.parseDouble(v);
                org.setMoq__c(moq.longValue());
            }
            //  DELIVERY_UNIT  无对应字段，跳过
            byKey.put(key, org);
        }

        // 分流：命中已有 → 更新；未命中 → 新增
        List<ProductOrg__c> toUpdate = new ArrayList<ProductOrg__c>();
        List<ProductOrg__c> toInsert = new ArrayList<ProductOrg__c>();
        for (Map.Entry<String, ProductOrg__c> e : byKey.entrySet()) {
            ProductOrg__c org = e.getValue();
            Long existId = existing.get(e.getKey());
            if (existId != null) {
                org.setId(existId);
                toUpdate.add(org);
            } else {
                org.setEntityType(PRODUCT_ORG_BUSI_TYPE);
                toInsert.add(org);
            }
        }
        // 先更新后新增：同一产品下唯一键唯一，先更新可避免与历史残留记录撞唯一键
        batchUpdateChildren(toUpdate, "productOrg__c");
        batchInsertChildren(toInsert, "productOrg__c");
        logger.info(TAG + " 产品组织同步完成 productId=" + productId
                + " 本次=" + byKey.size() + " 更新=" + toUpdate.size() + " 新增=" + toInsert.size()
                + " 已有记录=" + existing.size());
    }

    /**
     * 预查询某产品下已有的产品组织记录（唯一键 → 记录 id）。
     *
     * <p>走平台内部 XOQL（{@link RkhdHttpClient}，系统鉴权）—— 与 {@link #deleteChildren} 同理：
     * 普通 {@code XoqlService} 在当前 API 上下文读不到 productOrg__c。</p>
     *
     * <p>同一唯一键命中多条（历史脏数据）→ <b>取 id 最小</b>那条做更新目标，其余不动
     * （与 Myse13 发货地址「同键多条取 id 最小」口径一致；productOrg__c 没有 isDeleted__c，
     * 无法标记删除）。</p>
     */
    private Map<String, Long> queryExistingProductOrgs(Long productId) {
        Map<String, Long> map = new HashMap<String, Long>();
        Map<String, Long> dupCount = new HashMap<String, Long>();
        // 注意加 LIMIT：平台单次响应上限 300（不带 LIMIT 只有 100），超出会静默截断
        String xoql = "SELECT id, product__c, salesOrg__c, disChannel__c FROM productOrg__c"
                + " WHERE product__c = " + productId + " LIMIT 0, 1000";
        logger.info(TAG + " 内部XOQL(查已有产品组织): " + xoql);
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
                        if (j == null) {
                            continue;
                        }
                        Long id = asLongValue(j.get("id"));
                        if (id == null) {
                            continue;
                        }
                        Long salesOrgId = asLongValue(j.get("salesOrg__c"));
                        Integer channel = normalizeDisChannel(j.get("disChannel__c"));
                        String key = productOrgKey(productId, salesOrgId, channel);
                        Long exist = map.get(key);
                        if (exist == null) {
                            map.put(key, id);
                        } else {
                            dupCount.put(key, (dupCount.get(key) == null ? 1L : dupCount.get(key)) + 1L);
                            if (id < exist) {
                                // 取 id 最小的做更新目标
                                map.put(key, id);
                            }
                        }
                    }
                } else {
                    logger.warn(TAG + " 内部XOQL响应缺少 records 字段 productId=" + productId);
                }
            }
        } catch (Exception e) {
            logger.warn(TAG + " 内部XOQL查询已有产品组织失败 productId=" + productId + " : " + e.getMessage());
        }
        for (Map.Entry<String, Long> e : dupCount.entrySet()) {
            logger.warn(TAG + " 已有产品组织存在重复唯一键(产品+销售组织+渠道)，取 id 最小的一条更新，其余不动: key="
                    + e.getKey() + " 重复=" + e.getValue());
        }
        return map;
    }

    /**
     * 产品组织唯一键：产品 id + 销售组织 id + 分销渠道（选项 value）。
     * <p>用 {@code |} 分隔，避免直接拼接产生歧义；空值统一用空串。</p>
     */
    private String productOrgKey(Long productId, Long salesOrgId, Integer channel) {
        return productId + "|" + (salesOrgId == null ? "" : salesOrgId) + "|" + (channel == null ? "" : channel);
    }

    /**
     * disChannel__c 归一到<b>选项 value</b>。
     *
     * <p><b>为什么要归一</b>：disChannel__c 是 picklist（value 1/2/3 ↔ label 01/02/03），
     * 用 XOQL 查出来的是 <b>label</b>（"01"），而报文侧 {@code CHANNEL} 转出来是 <b>value</b>（1），
     * 两边不归一就对不上，唯一键会失效。</p>
     *
     * <p>兼容三种输入：label（"01"）、value（1 / "1"）、空（null）。</p>
     */
    private Integer normalizeDisChannel(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        // ★ XOQL 对 picklist 字段会返回 <b>label 数组</b>（如 ["02"]），
        //   直接 String.valueOf 会得到 "[\"02\"]" → 查表/parseInt 都失败 → 归一为 null，
        //   导致唯一键对不上、每次同步都新建。这里取数组首元素递归归一。
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            return arr.isEmpty() ? null : normalizeDisChannel(arr.get(0));
        }
        if (raw instanceof JSONObject) {
            JSONObject jo = (JSONObject) raw;
            Object code = jo.get("optionCode") != null ? jo.get("optionCode") : jo.get("optionApiKey");
            if (code == null) {
                code = jo.get("value");
            }
            if (code == null) {
                code = jo.get("id");
            }
            return code == null ? null : normalizeDisChannel(code);
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return null;
        }
        // ① 当作 picklist label 反查 value（"01" → 1）
        Integer byLabel = disChannelLabelToValue().get(s);
        if (byLabel != null) {
            return byLabel;
        }
        // ② 当作 value 直接解析（"1" / "1"）
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            logger.warn(TAG + " 分销渠道归一失败，按空处理: " + s + "（会导致唯一键对不上、可能重复创建）");
            return null;
        }
    }

    /** disChannel__c 的 label → value 映射（读选项集，静态缓存 30 分钟）。 */
    private Map<String, Integer> disChannelLabelToValue() {
        long at = disChannelMapAt;
        if (at > 0L && (System.currentTimeMillis() - at) < PICK_MAP_TTL) {
            return DIS_CHANNEL_LABEL_TO_VALUE;
        }
        Map<String, Integer> result = new HashMap<String, Integer>();
        try {
            Map<String, Map<Integer, String>> all =
                    QueryUtil.getPicklistDescriptionValueToLabel("productOrg__c");
            Map<Integer, String> valueToLabel = all.get("disChannel__c");
            if (valueToLabel != null) {
                for (Map.Entry<Integer, String> e : valueToLabel.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        result.put(e.getValue().trim(), e.getKey());
                    }
                }
            }
        } catch (Exception e) {
            logger.error(TAG + " 读取 productOrg__c.disChannel__c 选项集失败: " + e.getMessage(), e);
        }
        DIS_CHANNEL_LABEL_TO_VALUE.clear();
        DIS_CHANNEL_LABEL_TO_VALUE.putAll(result);
        disChannelMapAt = System.currentTimeMillis();
        return DIS_CHANNEL_LABEL_TO_VALUE;
    }

    /** 把任意值转成 Long（数字 / 数字字符串 / {id:...} 对象），转不了返回 null。 */

    /**
     * 同步产品包装（MATERIAL_UNITS → productPack__c）。整组替换：先删后批量插。
     */
    private void syncProductPacks(Long productId, JSONArray units) throws Exception {
        deleteChildren("productPack__c", productId, false);

        List<ProductPack__c> toInsert = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            JSONObject item = units.getJSONObject(i);
            if (item == null) {
                continue;
            }
            ProductPack__c pack = new ProductPack__c();
            pack.setProduct__c(productId);
            pack.setEntityType(PRODUCT_PACK_BUSI_TYPE);

            // 用户确认：PkgType → name，PkgUnits → packageUnit__c
            String v2;
            if ((v2 = item.getString("PkgType")) != null) pack.setName(v2);
            if ((v2 = item.getString("PkgUnits")) != null) pack.setPackageUnit__c(v2);
            if ((v2 = item.getString("PkgDesc")) != null) pack.setPackageDescription__c(v2);

            Long pkgQty = parseLong(item.getString("PkgQty"));
            if (pkgQty != null) {
                pack.setPackageQuantity__c(pkgQty);
            }
            Double pkgWeight = parseDouble(item.getString("PkgWeight"));
            if (pkgWeight != null) {
                pack.setPackageWeight__c(pkgWeight);
            }
            if ((v2 = item.getString("PkgWeightUnit")) != null) pack.setWeightUnit__c(v2);
            Double pkgDim = parseDouble(item.getString("PkgDimension"));
            if (pkgDim != null) {
                pack.setPackageDimension__c(pkgDim);
            }
            if ((v2 = item.getString("PkgDimensionUnit")) != null) pack.setDimensionUnit__c(v2);
            // StdPackageQty
            Long stdPackageQty = parseLong(item.getString("StdPackageQty"));
            if (stdPackageQty != null) {
                pack.setStandardPackageQuantity__c(stdPackageQty);
            }
            toInsert.add(pack);
        }
        batchInsertChildren(toInsert, "productPack__c");
    }

    /**
     * 子表批量写入：分批（200/批），任一批失败抛异常（整组替换场景不允许半截写入）。
     */
    private <T extends XObject> void batchInsertChildren(List<T> list, String entityApiName) throws Exception {
        if (list.isEmpty()) {
            return;
        }
        int success = 0;
        for (int from = 0; from < list.size(); from += CHILD_BATCH_SIZE) {
            List<T> part = list.subList(from, Math.min(from + CHILD_BATCH_SIZE, list.size()));
            BatchOperateResult br;
            try {
                br = XObjectService.instance().insert(part, true);
            } catch (Exception e) {
                throw new RuntimeException("批量写入子表失败 entity=" + entityApiName + ": " + e.getMessage(), e);
            }
            if (br == null || !Boolean.TRUE.equals(br.getSuccess())) {
                throw new RuntimeException("批量写入子表失败 entity=" + entityApiName + ": "
                        + (br == null ? "null" : br.getErrorMessage()));
            }
            if (br.getOperateResults() != null) {
                for (OperateResult r : br.getOperateResults()) {
                    if (r != null && Boolean.TRUE.equals(r.getSuccess())) {
                        success++;
                    } else if (r != null) {
                        logger.error(TAG + " 子表单条写入失败 entity=" + entityApiName + " err=" + r.getErrorMessage());
                    }
                }
            }
        }
        logger.info(TAG + " 批量写入子表 entity=" + entityApiName + " 成功=" + success + "/" + list.size());
    }

    /**
     * 子表批量更新：分批（200/批），任一批失败抛异常。
     * <p>用于「按唯一键 upsert」场景：命中已有记录 setId 后走这里更新。</p>
     */
    private <T extends XObject> void batchUpdateChildren(List<T> list, String entityApiName) throws Exception {
        if (list.isEmpty()) {
            return;
        }
        int success = 0;
        for (int from = 0; from < list.size(); from += CHILD_BATCH_SIZE) {
            List<T> part = list.subList(from, Math.min(from + CHILD_BATCH_SIZE, list.size()));
            BatchOperateResult br;
            try {
                br = XObjectService.instance().update(part);
            } catch (Exception e) {
                throw new RuntimeException("批量更新子表失败 entity=" + entityApiName + ": " + e.getMessage(), e);
            }
            if (br == null || !Boolean.TRUE.equals(br.getSuccess())) {
                throw new RuntimeException("批量更新子表失败 entity=" + entityApiName + ": "
                        + (br == null ? "null" : br.getErrorMessage()));
            }
            if (br.getOperateResults() != null) {
                for (OperateResult r : br.getOperateResults()) {
                    if (r != null && Boolean.TRUE.equals(r.getSuccess())) {
                        success++;
                    } else if (r != null) {
                        logger.error(TAG + " 子表单条更新失败 entity=" + entityApiName + " err=" + r.getErrorMessage());
                    }
                }
            }
        }
        logger.info(TAG + " 批量更新子表 entity=" + entityApiName + " 成功=" + success + "/" + list.size());
    }

    /**
     * 删除某产品下的全部子表记录（按 product__c）。
     * 说明：平台内 XoqlService.query 对 productOrg__c/productPack__c 自定义对象在当前 API 上下文无法读取
     * （连接器/管理员上下文可读、XOQL 实体名需小写），故改用 RkhdHttpClient 调用平台内部 XOQL
     * （系统鉴权，权限更高）来查旧记录，并用 formData 正确传递 xoql 参数。
     *
     * @param entityApiName XOQL 实体名（小写 API 名，如 productOrg__c）
     * @param productId     父产品 id
     * @param isOrg         true=产品组织(ProductOrg__c)，false=产品包装(ProductPack__c)
     */
    private void deleteChildren(String entityApiName, Long productId, boolean isOrg) throws Exception {
        List<Long> ids = new ArrayList<>();
        String xoql = "SELECT id FROM " + entityApiName + " WHERE product__c = " + productId;
        logger.info(TAG + " 内部XOQL(删前查旧): " + xoql);
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
                        if (id != null) {
                            ids.add(Long.parseLong(id.toString()));
                        }
                    }
                } else {
                    logger.warn(TAG + " 内部XOQL响应缺少 records 字段 entity=" + entityApiName + " productId=" + productId);
                }
            }
        } catch (Exception e) {
            logger.warn(TAG + " 内部XOQL查询旧子表失败 entity=" + entityApiName + " productId=" + productId + " : " + e.getMessage());
        }
        if (ids.isEmpty()) {
            return;
        }
        List<XObject> toDelete = new ArrayList<>();
        for (Long id : ids) {
            XObject stub = isOrg ? new ProductOrg__c() : new ProductPack__c();
            stub.setId(id);
            toDelete.add(stub);
        }
        try {
            XObjectService.instance().delete(toDelete,true);
            logger.info(TAG + " 删除旧子表成功 entity=" + entityApiName + " productId=" + productId + " 数量=" + ids.size());
        } catch (Exception e) {
            throw new RuntimeException("删除旧子表失败 entity=" + entityApiName + ": " + e.getMessage(), e);
        }
    }

    /**
     * 从 XOQL REST 响应中提取 records 数组，兼容多种返回结构。
     */
    private JSONArray extractRecords(JSONObject resp) {
        if (resp == null) {
            return null;
        }
        if (resp.containsKey("records")) {
            Object r = resp.get("records");
            if (r instanceof JSONArray) {
                return (JSONArray) r;
            }
        }
        if (resp.containsKey("data")) {
            Object d = resp.get("data");
            if (d instanceof JSONObject) {
                JSONObject dj = (JSONObject) d;
                if (dj.containsKey("records")) {
                    Object r = dj.get("records");
                    if (r instanceof JSONArray) {
                        return (JSONArray) r;
                    }
                }
            } else if (d instanceof JSONArray) {
                return (JSONArray) d;
            }
        }
        return null;
    }

    /**
     * 收集全部物料 SALES_AREA_INFO 里出现的销售组织编码（去重），用于预查询。
     */
    private Set<String> collectSalesOrgNames(JSONArray materials) {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < materials.size(); i++) {
            JSONObject material = materials.getJSONObject(i);
            if (material == null) {
                continue;
            }
            JSONArray salesArea = normalizeToArray(material.get("SALES_AREA_INFO"));
            if (salesArea == null) {
                continue;
            }
            for (int j = 0; j < salesArea.size(); j++) {
                JSONObject item = salesArea.getJSONObject(j);
                if (item == null) {
                    continue;
                }
                String code = item.getString("SALES_ORG");
                if (code != null && !code.trim().isEmpty()) {
                    names.add(code.trim());
                }
            }
        }
        return names;
    }

    /**
     * 按 name 批量反查 saleorg__c 的 id（一次 IN 查询，分批 100，避免循环内逐条查询）。
     * 返回 name → id 映射；查不到的 name 不在 map 中。
     */
    private Map<String, Long> querySaleOrgIdsByNames(Set<String> names) {
        Map<String, Long> map = new HashMap<>();
        if (names == null || names.isEmpty()) {
            return map;
        }
        List<String> nameList = new ArrayList<>(names);
        for (int from = 0; from < nameList.size(); from += 100) {
            List<String> part = nameList.subList(from, Math.min(from + 100, nameList.size()));
            StringBuilder inClause = new StringBuilder();
            for (String name : part) {
                if (inClause.length() > 0) {
                    inClause.append(",");
                }
                inClause.append("'").append(name.replace("'", "''")).append("'");
            }
            String xoql = "SELECT id, name FROM saleorg__c WHERE name IN (" + inClause + ")";
            logger.info(TAG + " SQL: " + xoql);
            try {
                QueryResult<JSONObject> qr = XoqlService.instance().query(xoql);
                if (qr != null && qr.getRecords() != null) {
                    for (JSONObject record : qr.getRecords()) {
                        if (record == null) {
                            continue;
                        }
                        Object id = record.get("id");
                        Object name = record.get("name");
                        if (id != null && name != null) {
                            map.put(name.toString(), Long.parseLong(id.toString()));
                        }
                    }
                }
                logger.info(TAG + " 销售组织预查询: 入参=" + part.size() + " 命中=" + map.size());
            } catch (Exception e) {
                logger.warn(TAG + " 批量反查销售组织失败: " + e.getMessage());
            }
        }
        return map;
    }

    /**
     * 收集全部物料号（MATERIAL_NO，去重），用于预查询。
     */
    private Set<String> collectMaterialNos(JSONArray materials) {
        Set<String> materialNos = new HashSet<>();
        for (int i = 0; i < materials.size(); i++) {
            JSONObject material = materials.getJSONObject(i);
            if (material == null) {
                continue;
            }
            String materialNo = material.getString("MATERIAL_NO");
            if (materialNo != null && !materialNo.trim().isEmpty()) {
                materialNos.add(materialNo.trim());
            }
        }
        return materialNos;
    }

    /**
     * 按 productCode__c 批量反查产品 id（一次 IN 查询，分批 100，避免循环内逐条查询）。
     * 返回 materialNo → productId 映射；不存在的产品不在 map 中。
     */
    private Map<String, Long> queryProductIdsByCodes(Set<String> materialNos) {
        Map<String, Long> map = new HashMap<>();
        if (materialNos == null || materialNos.isEmpty()) {
            return map;
        }
        List<String> codeList = new ArrayList<>(materialNos);
        for (int from = 0; from < codeList.size(); from += 100) {
            List<String> part = codeList.subList(from, Math.min(from + 100, codeList.size()));
            StringBuilder inClause = new StringBuilder();
            for (String code : part) {
                if (inClause.length() > 0) {
                    inClause.append(",");
                }
                inClause.append("'").append(code.replace("'", "''")).append("'");
            }
            String xoql = "SELECT id, productCode__c FROM product WHERE productCode__c IN (" + inClause + ")";
            logger.info(TAG + " SQL: " + xoql);
            try {
                QueryResult<JSONObject> qr = XoqlService.instance().query(xoql);
                if (qr != null && qr.getRecords() != null) {
                    for (JSONObject record : qr.getRecords()) {
                        if (record == null) {
                            continue;
                        }
                        Object id = record.get("id");
                        Object code = record.get("productCode__c");
                        if (id != null && code != null) {
                            map.put(code.toString(), Long.parseLong(id.toString()));
                        }
                    }
                }
                logger.info(TAG + " 产品预查询: 入参=" + part.size() + " 命中=" + map.size());
            } catch (Exception e) {
                logger.warn(TAG + " 批量反查产品失败: " + e.getMessage());
            }
        }
        return map;
    }

    // ===================== 工具方法 =====================

    /**
     * 将 SAP 可能传来的单对象或数组统一归一化为 JSONArray。
     * SAP 在只有一条明细时传对象，多条时传数组，两者都要兼容。
     */

    private Double parseDouble(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            logger.warn(TAG + " 数值解析失败: " + s);
            return null;
        }
    }

    private Long parseLong(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            // 兼容小数形式（如 "6.0"）
            try {
                return Math.round(Double.parseDouble(s.trim()));
            } catch (Exception e2) {
                logger.warn(TAG + " 长整型解析失败: " + s);
                return null;
            }
        }
    }

    private Integer parseInteger(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            logger.warn(TAG + " 整型解析失败: " + s);
            return null;
        }
    }

}
