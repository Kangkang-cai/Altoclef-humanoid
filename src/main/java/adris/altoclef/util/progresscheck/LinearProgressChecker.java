package adris.altoclef.util.progresscheck;

import adris.altoclef.util.helpers.TimeoutLog;
import adris.altoclef.util.time.TimerGame;

/**
 * Simple progress checker that requires we always make progress.
 */
public class LinearProgressChecker implements IProgressChecker<Double> {

    private final double _minProgress;
    private final TimerGame _timer;

    private double _lastProgress;
    private double _currentProgress;

    private boolean _first;

    private boolean _failed;

    // (2026-09-15 统一超时标签) 记录构造时的阈值, 判负边沿打 [TIMEOUT] 用。
    private final double _timeout;

    public LinearProgressChecker(double timeout, double minProgress) {
        _minProgress = minProgress;
        _timeout = timeout;
        _timer = new TimerGame(timeout);
        reset();
    }

    @Override
    public void setProgress(Double progress) {
        _currentProgress = progress;
        if (_first) {
            _lastProgress = progress;
            _first = false;
        }
        if (_timer.elapsed()) {
            double improvement = progress - _lastProgress;
            if (improvement < _minProgress) {
                if (!_failed) {
                    // (2026-09-15) 判负边沿: "timeout 秒内进展 < minProgress" 被判超时。
                    TimeoutLog.hit("LinearProgressChecker", "timeout=" + _timeout
                            + "s minProgress=" + _minProgress + " actual=" + improvement);
                }
                _failed = true;
            }
            _first = false;
            _timer.reset();
            _lastProgress = progress;
        }
    }

    @Override
    public boolean failed() {
        return _failed;
    }

    public void reset() {
        //_first = true;
        _failed = false;
        _timer.reset();
        _first = true;
    }
}
