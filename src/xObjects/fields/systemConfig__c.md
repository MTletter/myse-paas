# 系统配置表 (systemConfig__c)

共 24 个字段

| 字段名称 | API Key | 字段类型 | 引用对象 | 标准/自定义 |
|----------|---------|----------|----------|------------|
| 系统配置表名称 | name | 复选框 | - | 标准 |
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
| 系统域名 | sys_domain__c | 文本 | - | 自定义 |
| 集成系统 | sys_name__c | 整数 | - | 自定义 |
| 集成编号 | sys_number__c | 文本 | - | 自定义 |
| 是否启用接口 | sys_use__c | 布尔 | - | 自定义 |
| 对应系统编号 | sys_othercode__c | 文本 | - | 自定义 |
| 集成路径 | sys_url__c | 文本 | - | 自定义 |
| 集成认证 | sys_token__c | 文本 | - | 自定义 |
| 接口说明 | sys_memo__c | 文本 | - | 自定义 |

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

### 集成系统 (sys_name__c)

| 选项值 | 选项标签 |
|--------|----------|
| 1 | SAP |
| 2 | myCP |
| 3 | PMS |
| 4 | mySE |
| 5 | 其他 |
