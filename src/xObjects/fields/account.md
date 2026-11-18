# 客户 (account)

共 98 个字段

| 字段名称 | API Key | 字段类型 | 引用对象 | 标准/自定义 |
|----------|---------|----------|----------|------------|
| 客户类型 | entityType | 引用 | entityBelongType | 标准 |
| 客户所有人 | ownerId | 引用 | user | 标准 |
| 客户名称 | accountName | 文本 | - | 标准 |
| 客户级别 | level | 整数 | - | 标准 |
| 上级客户 | parentAccountId | 引用 | account | 标准 |
| 行业 | industryId | 整数 | - | 标准 |
| 省份 | fState | 整数 | - | 标准 |
| 市 | fCity | 整数 | - | 标准 |
| 区 | fDistrict | 整数 | - | 标准 |
| 经度 | longitude | 百分比 | - | 标准 |
| 纬度 | latitude | 百分比 | - | 标准 |
| 详细地址 | address | 文本 | - | 自定义 |
| 邮政编码 | zipCode | 文本 | - | 自定义 |
| 电话 | phone | 富文本 | - | 自定义 |
| 传真 | fax | 文本 | - | 自定义 |
| 公司网址 | url | 文本 | - | 自定义 |
| 微博 | weibo | 文本 | - | 自定义 |
| 总人数 | employeeNumber | 金额 | - | 自定义 |
| 销售额 | annualRevenue | 百分比 | - | 自定义 |
| 最新活动记录时间 | recentActivityRecordTime | 日期 | - | 标准 |
| 最新跟进人 | recentActivityCreatedBy | 引用 | user | 标准 |
| 创建日期 | createdAt | 日期 | - | 标准 |
| 客户来源 | highSeaAccountSource | 小数 | - | 自定义 |
| 创建人 | createdBy | 引用 | user | 标准 |
| 最新修改日 | updatedAt | 日期 | - | 标准 |
| 最新修改人 | updatedBy | 引用 | user | 标准 |
| 备注 | comment | 多行文本 | - | 自定义 |
| 审批提交人 | applicantId | 引用 | user | 标准 |
| 区域 | territoryId | 引用 | territory | 标准 |
| 所属部门 | dimDepart | 引用 | department | 标准 |
| 审批状态 | approvalStatus | 整数 | - | 标准 |
| 所属区域 | dimArea | 维度 | - | 标准 |
| 锁定状态 | lockStatus | 整数 | - | 标准 |
| 工商注册 | srcFlg | 整数 | - | 标准 |
| 所属行业 | dimIndustry | 维度 | - | 标准 |
| 所属产品线 | dimProduct | 维度 | - | 标准 |
| 所属业务 | dimBusiness | 维度 | - | 标准 |
| 免打扰 | doNotDisturb | 布尔 | - | 标准 |
| 外部部门 | outterDepartId | 引用 | department | 标准 |
| 结单商机数 | totalWonOpportunities | 统计字段 | - | 标准 |
| 实际收款金额 | paidAmount | 统计字段 | - | 标准 |
| 结单商机总金额 | totalWonOpportunityAmount | 统计字段 | - | 标准 |
| 应收余额（欠款） | invoiceBalance | 统计字段 | - | 标准 |
| 未收款金额 | unpaidAmount | 统计字段 | - | 标准 |
| 未出应收账金额 | amountUnbilled | 统计字段 | - | 标准 |
| 实际应收账金额 | actualInvoicedAmount | 统计字段 | - | 标准 |
| 生效订单数 | totalActiveOrders | 统计字段 | - | 标准 |
| 订单总金额 | totalOrderAmount | 统计字段 | - | 标准 |
| 合同数 | totalContract | 统计字段 | - | 标准 |
| 是否为结单客户 | isCustomer | 统计字段 | - | 标准 |
| 销售线索 | leadId | 引用 | lead | 标准 |
| 计划内拜访数 | visitInplanCount | 统计字段 | - | 标准 |
| 拜访总数 | visitTotalCount | 统计字段 | - | 标准 |
| 最近拜访时间 | visitLatestTime | 汇总字段 | - | 标准 |
| 未拜访天数 | visitUnvisitDay | 金额 | - | 标准 |
| 客户得分 | accountScore | 统计字段 | - | 标准 |
| 疑似查重 | duplicateFlg | 布尔 | - | 标准 |
| 账户支付比例 | paymentRate | 33 | - | 标准 |
| 最近回填时间 | recentBackfillTime | 汇总字段 | - | 标准 |
| 中央订单冻结 | aufsd__c | 文本 | - | 自定义 |
| 付款人银行名称 | bankA__c | 文本 | - | 自定义 |
| 付款人银行账号 | bankN__c | 文本 | - | 自定义 |
| 行业代码 | BRAN1__c | 文本 | - | 自定义 |
| 业务ID | businessId__c | 文本 | - | 自定义 |
| 付款联系人 | conper__c | 文本 | - | 自定义 |
| 中央开票冻结 | faksd__c | 文本 | - | 自定义 |
| 客户ID | kunnr__c | 文本 | - | 自定义 |
| 账户组 | ktokd__c | 文本 | - | 自定义 |
| 国家 | land1__c | 文本 | - | 自定义 |
| 中央交货冻结 | lifsd__c | 文本 | - | 自定义 |
| 运输区 | lzone__c | 文本 | - | 自定义 |
| 付款人邮寄地址 | mailName__c | 文本 | - | 自定义 |
| mySE管理员 | myseAdmin__c | 文本 | - | 自定义 |
| mySE管理员手机号码 | mySeAdminPhone__c | 文本 | - | 自定义 |
| 名称3 | name3__c | 文本 | - | 自定义 |
| 中央销售冻结 | newCode__c | 文本 | - | 自定义 |
| 城市 | ort01__c | 文本 | - | 自定义 |
| 邮政编码 | patlz__c | 文本 | - | 自定义 |
| 地区 | region__c | 文本 | - | 自定义 |
| 搜索词1 | sort1__c | 文本 | - | 自定义 |
| 语言 | spras__c | 文本 | - | 自定义 |
| 付款人税号1 | stcd1__c | 文本 | - | 自定义 |
| 税号3 | stcd3__c | 文本 | - | 自定义 |
| 街道/门牌号 | stras__c | 文本 | - | 自定义 |
| ���款人电话 | tel__c | 文本 | - | 自定义 |
| 付款人首选电话号码 | telF1__c | 文本 | - | 自定义 |
| 货币 | uwaer__c | 文本 | - | 自定义 |
| 细分1-ykatr6 | ykatr6__c | 文本 | - | 自定义 |
| 细分2-ykatr7 | ykatr7__c | 文本 | - | 自定义 |
| 标志 | zflag__c | 文本 | - | 自定义 |
| 邮政编码 | zipcode__c | 文本 | - | 自定义 |
| 客户编号1 | zkunnr__c | 文本 | - | 自定义 |
| 付款人名称1 | zname1__c | 文本 | - | 自定义 |
| 付款人街道门牌号 | zstras__c | 文本 | - | 自定义 |
| 是否启用 | isEnable__c | 布尔 | - | 自定义 |
| 是否授权 | isAuthorized__c | 整数 | - | 自定义 |
| 企业组织机构代码 | orgCode__c | 文本 | - | 自定义 |
| 客户分组 | group__c | 小数 | - | 自定义 |

## 选项列表

### 客户级别 (level)

| 选项值 | 选项标签 |
|--------|----------|
| 1 | A(重点客户) |
| 2 | B(普通客户) |
| 3 | C(非优先客户) |

### 行业 (industryId)

| 选项值 | 选项标签 |
|--------|----------|
| 1 | 金融 |
| 2 | 电信 |
| 3 | 教育 |
| 4 | 高科技 |
| 5 | 政府 |
| 6 | 制造业 |
| 7 | 服务 |
| 8 | 能源 |
| 9 | 零售 |
| 10 | 媒体 |
| 11 | 娱乐 |
| 12 | 咨询 |
| 13 | 非盈利事业 |
| 14 | 公用事业 |
| 15 | 其它 |

### 省份 (fState)

| 选项值 | 选项标签 |
|--------|----------|
| 2 | 北京市 |
| 3 | 天津市 |
| 4 | 河北省 |
| 5 | 山西省 |
| 6 | 内蒙古自治区 |
| 7 | 辽宁省 |
| 8 | 吉林省 |
| 9 | 黑龙江省 |
| 10 | 上海市 |
| 11 | 江苏省 |
| 12 | 浙江省 |
| 13 | 安徽省 |
| 14 | 福建省 |
| 15 | 江西省 |
| 16 | 山东省 |
| 17 | 河南省 |
| 18 | 湖北省 |
| 19 | 湖南省 |
| 20 | 广东省 |
| 21 | 广西壮族自治区 |
| 22 | 海南省 |
| 23 | 重庆市 |
| 24 | 四川省 |
| 25 | 贵州省 |
| 26 | 云南省 |
| 27 | 西藏自治区 |
| 28 | 陕西省 |
| 29 | 甘肃省 |
| 30 | 青海省 |
| 31 | 宁夏回族自治区 |
| 32 | 新疆维吾尔自治区 |
| 33 | 台湾省 |
| 34 | 香港特别行政区 |
| 35 | 澳门特别行政区 |

### 客户来源 (highSeaAccountSource)

| 选项值 | 选项标签 |
|--------|----------|
| 1 | 广告 |
| 2 | 研讨会 |
| 3 | 搜索引擎 |
| 4 | 客户介绍 |
| 5 | 其他 |

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

### 工商注册 (srcFlg)

| 选项值 | 选项标签 |
|--------|----------|
| 0 | 否 |
| 1 | 是 |

### 是否授权 (isAuthorized__c)

| 选项值 | 选项标签 |
|--------|----------|
| 1 | 是 |
| 2 | 否 |

### 客户分组 (group__c)

| 选项值 | 选项标签 |
|--------|----------|
| 1 | A |
| 2 | B |
| 3 | C |
