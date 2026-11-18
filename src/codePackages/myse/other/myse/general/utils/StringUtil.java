package other.myse.general.utils;

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
}
