# Myse13CustomerMasterApi —— 发货地址信息（shipToParty__c）入库逻辑梳理

> 梳理时间：2026-11-03　代码：`src/codePackages/myse/other/xsy/myse/apis/Myse13CustomerMasterApi.java`
> 数据实测：沙盒 POD-UAT2 租户 `4558820382802893`（48133 条）/ POD-UAT 租户 `4364574156998670`（137832 条）

---

## 一、整体位置

```
SAP 报文 CUST[]
   └── 每个客户 → account（客户主记录）
        └── Partny_function[]（合作伙伴功能行）→ shipToParty__c（发货地址信息）
             └── accountSalesOrg__c（客户销售组织）
```

发货地址不是独立接口同步的，而是**跟着客户主数据一起入库**（Myse13 的子表）。

## 二、执行流程（6 阶段）

| 阶段 | 动作 |
|---|---|
| Phase 1 | 收集 `KUNNR` + 每个 `Partny_function.F_KUNNR` → **一次 IN 查询**反查 account → `accountIdByKunnr` |
| Phase 2/3 | 客户主记录 insert / update；新增客户的 id 补进 `accountIdByKunnr` |
| Phase 4 | 删除旧 `accountSalesOrg__c`（**整组替换**，与发货地址不同） |
| Phase 5 | 预查已有发货地址建唯一键索引 → 构建新增/更新对象 → 收集待标记删除 id |
| Phase 6 | 批量 `insert` / `update` / 标记删除 |

## 三、唯一键与收敛规则

**唯一键 = 账户(purchaseId__c) + F_KUNNR(kunnr__c)**

| 场景 | 处理 |
|---|---|
| 只落什么 | 仅 `PARVW=SH`（发货方）；WE/RE/RG 等全部跳过 |
| 报文内重复 | 同 (账户,F_KUNNR) **后到的覆盖先到的**，只产出一条 |
| 库内已有同键 | 取 **id 最小**那条做更新目标，其余历史重复 → `isDeleted__c=1` |
| 命中已有 | 回填 id → 走 update（整字段覆盖） |
| 未命中 | 走 insert |
| SAP 本次未下发 | 该账户下多余记录 → `isDeleted__c=1`（**标记删除，不物理删**） |
| F_KUNNR 缺失 | **跳过**（没有唯一标识，否则每次推送都新增重复） |

## 四、字段映射（当前代码）

| shipToParty__c 字段 | 来源 | 代码是否写 | 库中填充率 |
|---|---|---|---|
| `purchaseId__c` | 账户 id | ✅ | 100% |
| `entityType` | 运行时查 `busiTypes`（带缓存） | ✅ | — |
| `kunnr__c` | `F_KUNNR` | ✅ | 99.6% |
| `f_Parvw__c` | `PARVW` | ✅ | 100% |
| `f_Vkorg__c` | `VKORG` → 反查 `saleorg__c.id`（有缓存） | ✅ | **仅 7.2%** |
| `f_Vtweg__c` | `VTWEG` | ✅ | 99.8% |
| `f_Spart__c` | `SPART` | ✅ | 100% |
| `customId__c` | **送达方** = `accountIdByKunnr[F_KUNNR]` | ✅ | 99.6% |
| `name` | `KUNNR-PARVW-F_KUNNR` | ✅ | 100% |
| `isDeleted__c` | 0=正常 / 1=删除（`setAttribute`） | ⚠️ 值非法 | 100% |
| `customItem10__c` | —— | ❌ **不写** | **100%** |
| `shipToAddress__c` | —— | ❌ **不写** | **100%** |
| `shipToID__c` | —— | ❌ **不写** | **99.6%** |
| `customItem11__c` | —— | ❌ 不写 | 0% |

> 「库中填充率」= 新租户 48133 条全量统计。

## 五、实测发现的问题

### P1 ⚠️ 三个字段被丢掉（最需要决策）
库里 `customItem10__c` / `shipToAddress__c` / `shipToID__c` 几乎 100% 有值，但**当前代码一个都不写**。

- `customItem10__c` = 客户号（值与 `kunnr__c` 相同），**今天新增的 batchjob 正是靠它反查送达方**
- `shipToAddress__c` = 发货地址全文，样例：`188592-MAG-ISOVER K.K.-CHIYODA-KU, TOKYO-3-7, KOJIMACHI`
  （代码注释写「本接口暂留空，地址后续由人工维护」——但数据显示它本来是从报文自动来的）
- `shipToID__c` = 发货地址 SAP ID（值与 `kunnr__c` 相同）

→ 建议：`F_KUNNR` 同时写进 `customItem10__c` 与 `shipToID__c`；地址文本写进 `shipToAddress__c`。这样字段口径与历史数据一致，batchjob 也不用改。

### P2 ⚠️ `kunnr__c` 为空的历史记录会持续累积重复
`queryExistingShipToParties()` 第 850~856 行：`kunnr__c` 为空的记录直接 `continue` ——
- 不进唯一键索引 → **永远匹配不上** → 每次 Myse13 推送这些客户都会**再新建一条**
- 也不在索引里 → 永远不会被告���删除 → 永久残留

实测（新租户）：**749 条** `kunnr__c` 为空，涉及 **581 个账户**，其中 **73 个账户已有 2 条以上** —— 已经在累积了。

### P3 送达方为空的根因：客户未建档，不是代码 bug
`customId__c` 取自 `accountIdByKunnr[F_KUNNR]`，而 Phase 1 已把 `F_KUNNR` 放进反查列表，
所以**只要客户表里存在这个客户号就能填上**。

实测：新租户剩下的 748 条，**客户表里实际能找到 0 个** → 是客户档案没建，不是逻辑错。
→ 等客户同步过来后，再跑一次 `shipToParty/fillAccountNow` 就能补上。

### P4 ⚠️ 两个 IN 查询都没分批
- `findAccountIdsByKunnrs()`：所有客户号拼进一个 `IN`
- `queryExistingShipToParties()`：所有账户 id 拼进一个 `IN`

平台对 `IN` 列表有上限（实测外部通道 300 个 id 只回 20 条）→ 客户多时会漏查 →
误判「不存在」→ **重复新建** / 误标删除。建议按 200 分批。

### P5 ⚠️ `isDeleted__c` 用了非法选项值
字段是 picklist，选项 `optionApiKey`：**1 = 'Y'（已删除）、2 = 'N'（未删除）**。
代码写的是 `Integer 0`（正常）/ `Integer 1`（删除）：
- `1` 碰巧合法（= Y）
- **`0` 不是合法选项值**（应为 2='N'）→ 库里实际存的是 `'0'`，平台界面显示与按该字段过滤都会不对

### P6 `f_Vkorg__c` 只有 7.2% 命中
`resolveSaleOrg()` 用 `name` / `companyCode__c` 匹配 `saleorg__c`，多数销售组织匹配不上（会打 warn）。

### P7 数据存在两个写入方
- 11-02 15:00~15:47 同一批 48132 条，**created → updated 跨了 3 轮**，`createdAt == updatedAt` 的 0 条
- 11-03 17:32 新建的那条：`kunnr__c` 空 / `customItem10__c=155095` / 送达方空
  —— 与当前代码行为**正好相反**（当前代码会写 `kunnr__c`、不写 `customItem10__c`）
- 已核对沙盒 `myse` 包的字节码与本地**完全一致**（`Myse13CustomerMasterApi.class` 均 31030 字节，字段常量相同）
  → 17:32 那条**不是当前 myse 包产生的**，沙盒上还有别的东西在写这张表（Windows 那份代码？平台流程？）

## 六、与今天 batchjob 的关系

新增的 `shipToParty/fillAccountNow` 是**补历史数据**的补丁，不是入库链路的一部分：

| | Myse13 入库 | batchjob 回填 |
|---|---|---|
| 依据 | `F_KUNNR` → `accountIdByKunnr` | `customItem10__c`（缺失时可用 `kunnr__c`）→ 客户表 |
| 客户不存在 | `customId__c` 留空 | 跳过 |
| 跑过之后 | 新数据自带送达方 | 只补历史 |

⚠️ 因为 P1（当前代码不写 `customItem10__c`），**batchjob 只认 `customItem10__c` 的话，新推送的数据它处理不了**。
两个选择：
1. 按 P1 把 `customItem10__c` 补回入库代码 → batchjob 不用改（推荐）
2. batchjob 改成「`customItem10__c` 优先，缺失时用 `kunnr__c`」→ 两边都能覆盖
