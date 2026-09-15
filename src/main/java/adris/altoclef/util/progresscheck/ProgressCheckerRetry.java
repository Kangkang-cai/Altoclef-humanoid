package adris.altoclef.util.progresscheck;

/**
 * A progress checker that can fail a few times before it "actually" fails.
 */
public class ProgressCheckerRetry<T> implements IProgressChecker<T> {

    private final IProgressChecker<T> _subChecker;
    private final int _allowedAttempts;

    private int _failCount;

    public ProgressCheckerRetry(IProgressChecker<T> subChecker, int allowedAttempts) {
        _subChecker = subChecker;
        _allowedAttempts = allowedAttempts;
    }

    @Override
    public void setProgress(T progress) {
        _subChecker.setProgress(progress);

        // If our subchecker fails, retry with an updated fail counter.
        if (_subChecker.failed()) {
            _failCount++;
            // (2026-09-15 统一超时标签) 重试额度用尽的边沿 = 真正的"无进展判负"。
            if (_failCount >= _allowedAttempts) {
                adris.altoclef.util.helpers.TimeoutLog.hit("ProgressCheckerRetry",
                        "attempts=" + _allowedAttempts + " exhausted");
            }
            _subChecker.reset();
        }
    }

    @Override
    public boolean failed() {
        return _failCount >= _allowedAttempts;
    }

    @Override
    public void reset() {
        _subChecker.reset();
        _failCount = 0;
    }
}
