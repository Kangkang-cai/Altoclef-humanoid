package adris.altoclef.record;

/**
 * (2026-09-17 数据集录制) 极简 JSON 输出助手。
 *
 * <p>刻意手写而不是引 Gson/Jackson: 录制点在 20Hz 的热路径上, 每 tick 要拼 4 个
 * 通道的 JSON。手写 StringBuilder 拼接没有对象树中间态, 分配最少、可控性最高;
 * 数据结构完全由本包自己定义, 不需要通用序列化能力。
 */
final class Json {

    private Json() {}

    /** 追加一个 JSON 字符串字面量 (含转义; null → null)。 */
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
                    // 控制字符必须转义, 否则产出非法 JSON
                    if (c < 0x20) {
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

    /** 浮点序列化: 保留 3 位小数 (训练用足够, 体积比 double 全精度小一大截)。 */
    static String f(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "null";
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** 追加 "[a,b,c]" 形式的 double 数组。 */
    static void vec(StringBuilder sb, double... vals) {
        sb.append('[');
        for (int i = 0; i < vals.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(f(vals[i]));
        }
        sb.append(']');
    }

    /** 追加 "[a,b,c]" 形式的 int 数组。 */
    static void ivec(StringBuilder sb, int... vals) {
        sb.append('[');
        for (int i = 0; i < vals.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vals[i]);
        }
        sb.append(']');
    }
}