package com.myse.deployer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按文件路径推断所属代码包。
 *
 * <p>规则与 {@code tools/deploy.py} 的 {@code guess_package_by_file()} 保持一致，
 * 两边必须同步改，否则「发布当前包」会和命令行 {@code --auto} 选出不同的包。</p>
 *
 * <p>目录结构：{@code src/codePackages/myse/} 下
 * {@code other/xsy/myse/**} 是主包，{@code other/myse/<子包>/**} 是各子包。</p>
 */
public final class PackageResolver {

    /** 子包目录前缀 → deploy.py 包名（csv 归到 general） */
    private static final Map<String, String> SUB_PACKAGES = new LinkedHashMap<>();

    static {
        SUB_PACKAGES.put("other/myse/general/", "general");
        SUB_PACKAGES.put("other/myse/home/", "home");
        SUB_PACKAGES.put("other/myse/messagecenter/", "messagecenter");
        SUB_PACKAGES.put("other/myse/invoice/", "invoice");
        SUB_PACKAGES.put("other/myse/coordinator/", "coordinator");
        SUB_PACKAGES.put("other/myse/csv/", "general");
    }

    /**
     * 推断文件所属包。
     *
     * @param filePath 文件绝对路径（Windows 的反斜杠会自动转正斜杠）
     * @return deploy.py 的包名；识别不出返回 null
     */
    public static String guess(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return null;
        }
        String p = filePath.replace('\\', '/');
        for (Map.Entry<String, String> e : SUB_PACKAGES.entrySet()) {
            if (p.contains("/" + e.getKey()) || p.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        if (p.contains("codePackages/myse") || p.startsWith("other/")) {
            return "myse";
        }
        return null;
    }

    /** 包名 → 平台上的包标识（展示用） */
    public static String javaPackageOf(String pkg) {
        if (pkg == null) {
            return null;
        }
        switch (pkg) {
            case "myse":
                return "other.xsy.myse";
            case "general":
                return "other.myse.general";
            case "home":
                return "other.myse.home";
            case "messagecenter":
                return "other.myse.messagecenter";
            case "invoice":
                return "other.myse.invoice";
            case "coordinator":
                return "other.myse.coordinator";
            default:
                return null;
        }
    }

    private PackageResolver() {
    }
}
