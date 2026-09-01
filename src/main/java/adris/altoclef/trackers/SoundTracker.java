package adris.altoclef.trackers;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.SoundEventHappenedEvent;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Remembers recent positioned sounds that the player heard.
 *
 * This lets the bot perceive entities/events that are NOT visible:
 * if an entity made a sound recently, we may know roughly where it is
 * even though we can't see it (human-like hearing).
 */
public class SoundTracker {

    // How long a heard sound stays "usable" for localization.
    private static final double RECENT_SOUND_WINDOW_SECONDS = 8.0;

    // If an entity is within this distance of a recent sound, consider it "heard".
    private static final double SOUND_MATCH_RADIUS = 12.0;

    private final List<RecentSound> _recentSounds = new ArrayList<>();

    public SoundTracker() {
        EventBus.subscribe(SoundEventHappenedEvent.class, this::onSound);
    }

    private synchronized void onSound(SoundEventHappenedEvent evt) {
        _recentSounds.add(new RecentSound(evt.x, evt.y, evt.z, System.currentTimeMillis()));
        prune();
    }

    private void prune() {
        long cutoff = System.currentTimeMillis() - (long) (RECENT_SOUND_WINDOW_SECONDS * 1000);
        _recentSounds.removeIf(s -> s.timeMs < cutoff);
    }

    /**
     * Whether an entity at the given position could have been localized via a recent sound.
     */
    public synchronized boolean isEntityAudible(Vec3d entityPos) {
        prune();
        for (RecentSound s : _recentSounds) {
            if (s.pos.squaredDistanceTo(entityPos) < SOUND_MATCH_RADIUS * SOUND_MATCH_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private static class RecentSound {
        final Vec3d pos;
        final long timeMs;

        RecentSound(double x, double y, double z, long timeMs) {
            this.pos = new Vec3d(x, y, z);
            this.timeMs = timeMs;
        }
    }
}
