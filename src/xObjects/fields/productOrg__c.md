# 产品销售组织 (productOrg__c)

共 31 个字段

| 字段名称 | API Key | 字段类型 | 引用对象 | 标准/自定义 |
|----------|---------|----------|----------|------------|
| 产品销售组织名称 | name | 复选框 | - | 标准 |
| 业务类型 | entityType | 引用 | busiType | 标准 |
| 所有人 | ownerId | 引用 | user | 标准 |
| 所属部门 | dimDepart | 引用 | department | 标准 |
| 所属区域 | dimArea | 维度 | - | 标准 |
| 工作流阶段名称 | workflowStageName | 文本 | - | 标准 |
| 所属产品 | dimProduct | 维度 | - | 标准 |
| 所属行业 | dimIndustry | 维度 | - | 标准 |
| 所属业务线 | dimBusiness | 维度 | - | 标准 |
| 审批提交人 | applicantId | 引用 | user | 标准 |
| 创建人 | createdBy | 引用 | user | 标准 |
| 创建日期 | createdAt | 汇总字段 | - | 标准 |
| 修改人 | updatedBy | 引用 | user | 标准 |
| 修改日期 | updatedAt | 汇总字段 | - | 标准 |
| 锁定状态 | lockStatus | 整数 | - | 标准 |
| 审批状态 | approvalStatus | 整数 | - | 标准 |
| 产品 | product__c | 引用 | product | 自定义 |
| 销售组织 | salesOrg__c | 引用 | saleorg__c | 自定义 |
| 发货中心 | shippingCenter__c | 文本 | - | 自定义 |
| 线上销售标识 | internetSales__c | 文本 | - | 自定义 |
| 限制价格 | priceRestriction__c | 文本 | - | 自定义 |
| 产品类型 | productCategoryGroup__c | 文本 | - | 自定义 |
| 冻结原因代码 | distriStatus__c | 文本 | - | 自定义 |
| 冻结开始日期 | blkStartDate__c | 日期 | - | 自定义 |
| 物料价格组 | materialPriceGroup__c | 文本 | - | 自定义 |
| 销售单位 | salesUnit__c | 文本 | - | 自定义 |
| 分销渠道 | disChannel__c | 整数 | - | 自定义 |
| 最小订货量 | moq__c | 金额 | - | 自定义 |
| materialGroup2 | mvgr2__c | 文本 | - | 自定义 |
| 交易实体 | entity__c | 复合字段 | - | 自定义 |
| 产品是否选型 | customItem15__c | 统计字段 | - | 自定义 |

## 选项列表

### 锁定状态 (lockStatus)

| 选项值 | 选项标签 |
|--------|----------|
| 1 | 未锁定 |
| 2 | 已锁定 |

### 审批状态 (approvalStatus)

| 选项值 | 选项标签 |
|--------|----------|
| 0 | 待提交 |
| 1 | 审批中 |
| 2 | 审批拒绝 |
| 3 | 审批通过 |
| 4 | 撤回 |

### 分销渠道 (disChannel__c)

| 选项值 | 选项标签 |
|--------|----------|
| 1 | 01 |
| 2 | 02 |
| 3 | 03 |
