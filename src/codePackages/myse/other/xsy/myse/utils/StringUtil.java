package other.xsy.myse.utils;

/**
 * 字符串工具类（平台安全版，替代不可用的 org.eclipse.jetty.util.StringUtil）
 */
public class StringUtil {

    /**
     * 判断字符串是否为 null 或空白（空串/纯空格）
     */
    public static boolean isBlank(String s) {
        if (s == null) {
            return true;
        }
        int len = s.length();
        if (len == 0) {
            return true;
        }
        for (int i = 0; i < len; i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断字符串是否非 null 且非空白
     */
    public static boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    /** SAP ADDRESSLINE1~5 每行最大字符数 */
    public static final int ADDR_LINE_LEN = 35;

    /** SAP ADDRESSLINE 行数上限 */
    public static final int ADDR_LINE_MAX = 5;

    /**
     * SAP 客户号 CHAR10：不足 10 位前补 0（如 136100 → 0000136100）；空/超长原样返回。
     * <p>作用于 PURCHASERID / CUSTOMERID。</p>
     */
    public static String padKunnr(String kunnr) {
        if (kunnr == null) {
            return null;
        }
        String s = kunnr.trim();
        if (s.isEmpty() || s.length() >= 10) {
            return s;
        }
        return padLeft(s, '0', 10);
    }

    /**
     * SAP 行号 ITEM_NUMBER 补零到 6 位。
     * <p>例：10 / "10" / "00010" → "000010"；已超 6 位原样返回；非数字原样返回。</p>
     */
    public static String padItemNumber(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty() || !s.matches("\\d+")) {
            return s;
        }
        String digits = s.replaceFirst("^0+(?=\\d)", "");
        return digits.length() >= 6 ? digits : padLeft(digits, '0', 6);
    }

    /** 左侧补 pad 到 total 长度（已够长则原样返回）。 */
    private static String padLeft(String s, char pad, int total) {
        StringBuilder sb = new StringBuilder(total);
        for (int i = s.length(); i < total; i++) {
            sb.append(pad);
        }
        return sb.append(s).toString();
    }

    /**
     * 把发货地址拆成 SAP ADDRESSLINE1~5：每行限 35 字符，超长顺延至下一行，
     * 最多 5 行（超出部分丢弃）。与 qtTOsoFutureTask 的既有处理保持一致。
     * <p>示例："0000552431-荣盛安达（北京）电气设备有限公司-北京-北京市丰台区双马建材市场B道4号"（47 字符）
     * → [前 35 字符, 后 12 字符, "", "", ""]</p>
     */
    public static String[] splitAddressLines(String addr) {
        String[] lines = new String[ADDR_LINE_MAX];
        String s = addr == null ? "" : addr;
        for (int i = 0; i < ADDR_LINE_MAX; i++) {
            int start = i * ADDR_LINE_LEN;
            lines[i] = start < s.length()
                    ? s.substring(start, Math.min(start + ADDR_LINE_LEN, s.length()))
                    : "";
        }
        return lines;
    }
}
