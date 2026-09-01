package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.LookHelper;
import baritone.api.utils.Rotation;
import net.minecraft.util.math.Vec3d;

/**
 * Active perception: while moving, keep looking where we're going (so the
 * visibility filter can actually see what's ahead) and periodically sweep the
 * camera left/right; while standing still, do a full panorama.
 *
 * The "base yaw" (where to look between sweeps) is either provided explicitly
 * (e.g. the current exploration target) or derived from the player's velocity.
 * Combat/manual look targets simply overwrite ours.
 */
public class ScanLookBehavior {

    // How often we sweep while moving (ticks).
    private static final int MOVE_SCAN_INTERVAL_TICKS = 35;
    // How long a sweep "holds" before the next one may start (ticks).
    private static final int SWEEP_HOLD_TICKS = 10;
    // Sweep angle to the side of the travel direction (degrees).
    private static final float SWEEP_ANGLE_DEG = 45f;
    // How often we change direction while standing still (ticks).
    private static final int IDLE_SCAN_INTERVAL_TICKS = 30;
    // Horizontal speed below which we consider the player "standing still".
    private static final double STILL_SPEED = 0.05;

    private int _moveCounter = 0;
    private int _sweepRemaining = 0;
    private boolean _sweepPositive = true; // alternate sweep direction
    private int _idleCounter = 0;
    private int _idleDirection = 0;
    private Float _idleBaseYaw = null;

    public void step(AltoClef mod) {
        step(mod, null);
    }

    public void step(AltoClef mod, Float baseYaw) {
        if (!AltoClef.inGame() || mod.getPlayer() == null) return;
        if (isStandingStill(mod)) {
            _moveCounter = 0;
            stepIdleScan(mod);
            return;
        }
        _idleBaseYaw = null;
        _idleDirection = 0;
        stepMovingScan(mod, baseYaw);
    }

    private void stepMovingScan(AltoClef mod, Float baseYaw) {
        _moveCounter++;
        if (_sweepRemaining > 0) {
            _sweepRemaining--;
            return;
        }
        if (_moveCounter >= MOVE_SCAN_INTERVAL_TICKS) {
            // Sweep to the side of our travel direction.
            _moveCounter = 0;
            float travelYaw = baseYaw != null ? baseYaw : getTravelYawDeg(mod);
            float targetYaw = travelYaw + (_sweepPositive ? SWEEP_ANGLE_DEG : -SWEEP_ANGLE_DEG);
            _sweepPositive = !_sweepPositive;
            LookHelper.lookAt(mod, new Rotation(targetYaw, 0));
            _sweepRemaining = SWEEP_HOLD_TICKS;
            return;
        }
        // Between sweeps: keep looking where we're going instead of walking blind.
        float travelYaw = baseYaw != null ? baseYaw : getTravelYawDeg(mod);
        LookHelper.lookAt(mod, new Rotation(travelYaw, 0));
    }

    private void stepIdleScan(AltoClef mod) {
        if (_idleBaseYaw == null) {
            _idleBaseYaw = mod.getPlayer().getYaw();
            _idleDirection = 1;
            _idleCounter = 0;
        }
        _idleCounter++;
        if (_idleCounter >= IDLE_SCAN_INTERVAL_TICKS) {
            _idleCounter = 0;
            _idleDirection++;
            if (_idleDirection > 4) {
                // Panorama round finished: let other systems take back the camera.
                _idleBaseYaw = null;
                _idleDirection = 0;
                return;
            }
            float targetYaw = _idleBaseYaw + _idleDirection * 90f;
            LookHelper.lookAt(mod, new Rotation(targetYaw, 0));
        }
    }

    private float getTravelYawDeg(AltoClef mod) {
        Vec3d vel = mod.getPlayer().getVelocity();
        if (Math.abs(vel.x) < 0.01 && Math.abs(vel.z) < 0.01) {
            return mod.getPlayer().getYaw();
        }
        // MC yaw: 0 = +Z (south), -90 = +X (east).
        return (float) Math.toDegrees(Math.atan2(-vel.x, vel.z));
    }

    private boolean isStandingStill(AltoClef mod) {
        Vec3d vel = mod.getPlayer().getVelocity();
        return Math.abs(vel.x) < STILL_SPEED && Math.abs(vel.z) < STILL_SPEED;
    }
}
