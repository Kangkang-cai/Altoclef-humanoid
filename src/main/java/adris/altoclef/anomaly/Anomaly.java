package adris.altoclef.anomaly;

/**
 * (2026-09-11 异常看门狗) 一条异常记录。
 *
 * 不可变对象: 由检测器构造, 交给 AnomalyWatchdog 统一去重 / 分级 / 输出。
 * 检测器不负责决定"报不报", 只负责"我看没看出来"。
 */
public final class Anomaly {

    private final String _type;
    private final String _key;
    private final AnomalySeverity _severity;
    private final String _summary;
    private final String _detail;

    public Anomaly(String type, String key, AnomalySeverity severity, String summary, String detail) {
        _type = type;
        _key = key;
        _severity = severity;
        _summary = summary;
        _detail = detail;
    }

    /** 检测器短名 (stall / unreachable / oscillation ...)。 */
    public String type() {
        return _type;
    }

    /**
     * 去重键: 同一 (type, key) 在冷却期内只报一次。
     *
     * 注意 key 必须由**稳定标识**构成 (链名 / 任务类名)。本项目教训: 任务实例
     * 每 tick 重建, 拿实例做键等于没有去重, 会退化成刷屏。
     */
    public String key() {
        return _key;
    }

    public AnomalySeverity severity() {
        return _severity;
    }

    /** 一句话摘要。会进游戏聊天, 所以要短。 */
    public String summary() {
        return _summary;
    }

    /** 补充说明, 只进日志文件。可为 null。 */
    public String detail() {
        return _detail;
    }

    @Override
    public String toString() {
        return "[" + _severity + "][" + _type + "] " + _summary;
    }
}
