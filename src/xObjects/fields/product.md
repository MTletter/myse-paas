# 产品 (product)

共 59 个字段

| 字段名称 | API Key | 字段类型 | 引用对象 | 标准/自定义 |
|----------|---------|----------|----------|------------|
| 可转资产 | fscTrackedAsAsset | 整数 | - | 标准 |
| 订货号 | productName | 文本 | - | 标准 |
| 产品目录 | parentId | 引用 | productCategory | 标准 |
| 产品所有人 | ownerId | 引用 | user | 标准 |
| 标准价格 | priceUnit | 百分比 | - | 标准 |
| 销售单位 | unit | 文本 | - | 标准 |
| 产品图片 | fileImage1 | 关联字段 | - | 标准 |
| 产品描述 | description | 多行文本 | - | 标准 |
| 创建日期 | createdAt | 日期 | - | 标准 |
| 最新修改日 | updatedAt | 日期 | - | 标准 |
| 启用状态 | enableStatus | 整数 | - | 标准 |
| 组合产品 | configurable | 布尔 | - | 标准 |
| 独立销售产品 | independentProduct | 布尔 | - | 标准 |
| 生效日期 | startDate | 日期 | - | 标准 |
| 失效日期 | endDate | 日期 | - | 标准 |
| 创建人 | createdBy | 引用 | user | 标准 |
| 最新修改人 | updatedBy | 引用 | user | 标准 |
| 业务类型 | entityType | 引用 | entityBelongType | 标准 |
| 所属部门 | dimDepart | 引用 | department | 标准 |
| 审批状态 | approvalStatus | 整数 | - | 标准 |
| 审批提交人 | applicantId | 引用 | user | 标准 |
| 锁定状态 | lockStatus | 整数 | - | 标准 |
| 汇总计算 | aggregate | 布尔 | - | 标准 |
| 配置 | configurationType | 整数 | - | 标准 |
| 区域 | territoryId | 引用 | territory | 标准 |
| 配置时机 | configurationEvent | 整数 | - | 标准 |
| 产品型号 | fscProductModel | 文本 | - | 标准 |
| 产品规格 | fscProductSpec | 文本 | - | 标准 |
| 产品条码 | productCode | 文本 | - | 标准 |
| 产品类型 | fscProductType | 整数 | - | 标准 |
| 需要回收 | fscNeedReturn | 整数 | - | 标准 |
| 商品 | goods | 引用 | goods | 标准 |
| 最小数量 | minQuantity | 百分比 | - | 标准 |
| SKU类型 | skuType | 整数 | - | 标准 |
| 包装单位 | packUnit | 引用 | unit | 标准 |
| 基础单位 | baseUnitId | 引用 | unit | 标准 |
| 规格值1 | specificationValue1 | 引用 | specificationValue | 标准 |
| 规格值2 | specificationValue2 | 引用 | specificationValue | 标准 |
| 开启多单位 | multiUnit | 布尔 | - | 标准 |
| 规格值3 | specificationValue3 | 引用 | specificationValue | 标准 |
| 固定增购数量 | orderFixedIncrement | 百分比 | - | 标准 |
| 规格值4 | specificationValue4 | 引用 | specificationValue | 标准 |
| 规格值5 | specificationValue5 | 引用 | specificationValue | 标准 |
| 商品单位 | goodsUnit | 引用 | goodsUnit | 标准 |
| 促销标记 | salesPromotionLabel | 整数 | - | 标准 |
| 爆炸图 | explodedView | 关联字段 | - | 标准 |
| 产品编码 | productCode__c | 文本 | - | 自定义 |
| 标准包装数量 | standardPackageQuantity__c | 金额 | - | 自定义 |
| 重量 | weight__c | 百分比 | - | 自定义 |
| 重量单位 | weightUnit__c | 文本 | - | 自定义 |
| 体积 | volume__c | 百分比 | - | 自定义 |
| 体积单位 | volumeUnit__c | 文本 | - | 自定义 |
| 国家代码 | country__c | 文本 | - | 自定义 |
| 公司代码 | company__c | 文本 | - | 自定义 |
| 是否可配置物料 | kzkfg__c | 布尔 | - | 自定义 |
| 描述 | proDescription__c | 文本 | - | 自定义 |
| Material Group | matkl__c | 文本 | - | 自定义 |
| Division | spart__c | 文本 | - | 自定义 |
| Plant | bwkey__c | 文本 | - | 自定义 |

## 选项列表

### 可转资产 (fscTrackedAsAsset)

| 选项值 | 选项标签 |
|--------|----------|
| 1 | 是 |
| 2 | 否 |

### 启用状态 (enableStatus)

| 选项值 | 选项标签 |
|--------|----------|
| 1 | 启用 |
| 2 | 禁用 |

### 审批状态 (approvalStatus)

| 选项值 | 选项标签 |
|--------|----------|
| 0 | 待提交 |
| 1 | 审批中 |
| 2 | 审批拒绝 |
| 3 | 审批通过 |
| 4 | 撤回 |

### 锁定状态 (lockStatus)

| 选项值 | 选项标签 |
|--------|----------|
| 1 | 未锁定 |
| 2 | 已锁定 |

### 配置 (configurationType)

| 选项值 | 选项标签 |
|--------|----------|
| 1 | 必须 |
| 2 | 允许 |
| 3 | 不允许 |

### 配置时机 (configurationEvent)

| 选项值 | 选项标签 |
|--------|----------|
| 1 | 任何时候 |
| 2 | 新增产品 |
| 3 | 编辑明细 |

### 产品类型 (fscProductType)

| 选项值 | 选项标签 |
|--------|----------|
| 1 | 成品 |
| 2 | 备件 |
| 3 | 服务 |
| 4 | 其它 |

### 需要回收 (fscNeedReturn)

| 选项值 | 选项标签 |
|--------|----------|
| 1 | 是 |
| 2 | 否 |

### SKU类型 (skuType)

| 选项值 | 选项标签 |
|--------|----------|
| 1 | 主规格 |
| 2 | 子规格 |

### 促销标记 (salesPromotionLabel)

| 选项值 | 选项标签 |
|--------|----------|
| 1 | 新品 |
| 2 | 热销 |
