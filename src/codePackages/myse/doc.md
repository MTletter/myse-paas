# mySE SAP 集成代码包

## 说明

本代码包实现施耐德 mySE 电商平台与 SAP ERP 通过 SAP PI 中间件的集成。

**销售易 API 基础路径**：`/rest/data/v2.0/scripts/api/myse/`

## 代码包结构

```
myse/
├── scriptTrigger.xml          # 自定义 API 注册配置（全部 10 个接口）
├── doc.md                     # 本说明文档
└── other/xsy/myse/
    ├── apis/                  # 自定义 API 实现（MYSE-01~10）
    │   ├── Myse01PaQueryApi.java              # P&A 价格货期查询
    │   ├── Myse02QuotationSimulateApi.java    # 报价单模拟
    │   ├── Myse03QuotationCreateApi.java      # 报价单创建
    │   ├── Myse04QuotationToOrderApi.java     # 报价单转销售订单
    │   ├── Myse05OrderSimulateApi.java        # 销售订单模拟
    │   ├── Myse06OrderCreateApi.java          # 创建销售订单
    │   ├── Myse07OrderModifySimulateApi.java  # 模拟修改销售订单
    │   ├── Myse08OrderModifyConfirmApi.java   # 确认修改销售订单
    │   ├── Myse09CreditQueryApi.java          # 信用额度查询
    │   └── Myse10ShippingScheduleApi.java     # 查询 Shipping Schedule
    └── utils/
        └── SapPiHttpUtil.java                 # SAP PI HTTP 工具类
```

## 接口清单

| 编号 | 类名 | API 路径 | SAP BAPI |
|------|------|----------|----------|
| MYSE-01 | Myse01PaQueryApi | `myse/pa/query` | Z_BAPI_PAMULTIPLE |
| MYSE-02 | Myse02QuotationSimulateApi | `myse/quotation/simulate` | Z_BAPI_SIMULATE_QUOTATION |
| MYSE-03 | Myse03QuotationCreateApi | `myse/quotation/create` | Z_BAPI_QUOTATION_ENTRY |
| MYSE-04 | Myse04QuotationToOrderApi | `myse/order/create-from-quotation` | Z_BAPI_CREATE_QUOTATION_NEW |
| MYSE-05 | Myse05OrderSimulateApi | `myse/order/simulate` | Z_BAPI_SIMULATE_SALESORDER_NEW |
| MYSE-06 | Myse06OrderCreateApi | `myse/order/create` | Z_BAPI_CREATE_SALESORDER_NEW |
| MYSE-07 | Myse07OrderModifySimulateApi | `myse/order/modify/simulate` | Z_BAPI_ORDERMODIF_VALIDATION |
| MYSE-08 | Myse08OrderModifyConfirmApi | `myse/order/modify/confirm` | Z_BAPI_ORDERMODIF_RELEASE |
| MYSE-09 | Myse09CreditQueryApi | `myse/credit/query` | Z_RFC_CALL_CREDIT_LIMITE_NEW |
| MYSE-10 | Myse10ShippingScheduleApi | `myse/order/shipping-schedule` | Z_ORDER_STAT_SHIP_SCHED_NEW |

## 配置项（待补充）

在 `SapPiHttpUtil.java` 中的 `PI_URL` 常量，需替换为实际 SAP PI 服务地址：

```java
// 当前占位符
private static final String PI_URL = "http://SAP_PI_HOST/sap/bc/srt/rfc/sap/{bapi}/client/rest";

// 替换为实际地址，例如：
private static final String PI_URL = "http://192.168.1.100:50000/sap/bc/srt/rfc/sap/z_bapi_pamultiple/client/rest";
```

如需 Basic Auth，在 `SapPiHttpUtil.java` 的 headers 中添加：
```java
headers.put("Authorization", "Basic " + Base64.encode("pi_user:pi_password"));
```

## 部署

```bash
node neo-backend-dev/scripts/deploy_server_script.js <项目目录> myse other.xsy.myse
```

## 待办

- [ ] 填写各接口的实际 SAP PI URL（替换 `SAP_PI_HOST`）
- [ ] 配置 SAP PI 认证信息（Basic Auth / Token）
- [ ] 完善各接口的入参校验逻辑
- [ ] 添加响应字段映射（PI 返回 → mySE 前端需要的格式）
- [ ] 搭建 MYSE-11~18（SAP→mySE 推送接收接口）
