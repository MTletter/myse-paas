package com.myse.deployer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可发布的代码包清单。
 *
 * <p>与 {@code tools/deploy.py} 的 PACKAGES 保持一致；顺序即建议发布顺序
 * （主包 → general → home → messagecenter → invoice → coordinator）。</p>
 */
public final class DeployPackages {

    /** key=deploy.py 的包名，value=展示说明 */
    public static final Map<String, String> PACKAGES = new LinkedHashMap<>();

    static {
        PACKAGES.put("myse", "主包 other.xsy.myse（SAP 对接接口；全量打包，会带上所有本地改动）");
        PACKAGES.put("general", "通用包 other.myse.general（产品查询/导出/文件下载）");
        PACKAGES.put("home", "首页包 other.myse.home（首页概览/待办/常用清单/协议）");
        PACKAGES.put("messagecenter", "消息中心包 other.myse.messagecenter（消息/我的账户/角色申请）");
        PACKAGES.put("invoice", "形式发票包 other.myse.invoice（形式发票列表/详情）");
        PACKAGES.put("coordinator", "协调员包 other.myse.coordinator（账户权限/审批/角色定义）");
    }

    private DeployPackages() {
    }
}
