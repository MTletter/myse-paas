# 销售组织 (saleorg__c)

共 20 个字段

| 字段名称 | API Key | 字段类型 | 引用对象 | 标准/自定义 |
|----------|---------|----------|----------|------------|
| 销售组织名称 | name | 文本 | - | 标准 |
| 业务类型 | entityType | 引用 | busiType | 标准 |
| 所有人 | ownerId | 引用 | user | 标准 |
| 所属部门 | dimDepart | 引用 | department | 标准 |
| 所属区域 | dimArea | 维度 | - | 标准 |
| 所属产品 | dimProduct | 维度 | - | 标准 |
| 工作流阶段名称 | workflowStageName | 文本 | - | 标准 |
| 所属行业 | dimIndustry | 维度 | - | 标准 |
| 所属业务线 | dimBusiness | 维度 | - | 标准 |
| 创建人 | createdBy | 引用 | user | 标准 |
| 审批提交人 | applicantId | 引用 | user | 标准 |
| 创建日期 | createdAt | 汇总字段 | - | 标准 |
| 修改人 | updatedBy | 引用 | user | 标准 |
| 修改日期 | updatedAt | 汇总字段 | - | 标准 |
| 锁定状态 | lockStatus | 整数 | - | 标准 |
| 审批状态 | approvalStatus | 整数 | - | 标准 |
| 公司代码 | companyCode__c | 文本 | - | 自定义 |
| 公司名称 | compnayName__c | 文本 | - | 自定义 |
| 交易实体 | Eentity__c | 整数 | - | 自定义 |
| 交易实体代码 | entityCode__c | 统计字段 | - | 自定义 |

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

### 交易实体 (Eentity__c)

| 选项值 | 选项标签 |
|--------|----------|
| CN01-SEC | SEC施耐德电气(中国)有限公司 |
| CN22-SEIT | SEIT施耐德电气信息技术(中国)有限公司 |
| CN26-SWEEC | SWEEC施耐德万高(天津)电气设备有限公司 |
| CN30-ASCO | SASS(ASCO)上海高双电气科技有限公司 |
