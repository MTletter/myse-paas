# 产品包装信息 (productPack__c)

共 25 个字段

| 字段名称 | API Key | 字段类型 | 引用对象 | 标准/自定义 |
|----------|---------|----------|----------|------------|
| 包装类型 | name | 文本 | - | 标准 |
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
| 包装描述 | packageDescription__c | 文本 | - | 自定义 |
| 包装数量 | packageQuantity__c | 金额 | - | 自定义 |
| 包装单位 | packageUnit__c | 文本 | - | 自定义 |
| 包装重量 | packageWeight__c | 百分比 | - | 自定义 |
| 包装重量单位 | weightUnit__c | 文本 | - | 自定义 |
| 包装尺寸 | packageDimension__c | 百分比 | - | 自定义 |
| 包装尺寸单位 | dimensionUnit__c | 文本 | - | 自定义 |
| 标准包装数量 | standardPackageQuantity__c | 金额 | - | 自定义 |
| 订货号 | product__c | 引用 | product | 自定义 |

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
