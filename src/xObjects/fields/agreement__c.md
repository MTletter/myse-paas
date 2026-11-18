# 协议书 (agreement__c)

共 19 个字段

| 字段名称 | API Key | 字段类型 | 引用对象 | 标准/自定义 |
|----------|---------|----------|----------|------------|
| 协议书名称 | name | 文本 | - | 标准 |
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
| 协议版本 | agreement_version__c__c | 文本 | - | 自定义 |
| 协议正文 | agreement_content__c__c | 多行文本 | - | 自定义 |
| 生效日期 | effective_date__c__c | 日期 | - | 自定义 |

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
