package adris.altoclef.bridge;

/**
 * bridge 层极简 JSON 输出助手。
 *
 * <p>刻意手写而非引 Jackson/Gson：HTTP handler 是热路径，固定结构的 JSON 用
 * StringBuilder 直接拼零对象树中间态、零反射、可控性最高（与 record/Json 同思路，
 * 但那是包级私有，bridge 不能复用，故在此独立一份，仅含 bridge 需要的方法）。
 *
 * <p>所有方法均线程安全（无状态）。
 */
final class BridgeJson {

    private BridgeJson() {}

    /** 追加一个 JSON 字符串字面量（含转义；null → {@code null}）。 */
    static void str(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        // 控制字符必须转义，否则产出非法 JSON
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int p = hex.length(); p < 4; p++) sb.append('0');
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** 浮点序列化：保留 1 位小数（坐标/血量够用，体积比全精度小一截）。 */
    static String f1(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "null";
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** 整数序列化（坐标分量、血量取整等）。 */
    static String i(long v) {
        return Long.toString(v);
    }
}
