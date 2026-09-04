package adris.altoclef.trackers;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.SoundEventHappenedEvent;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        _recentSounds.add(new RecentSound(
                evt.x, evt.y, evt.z,
                evt.sound != null ? evt.sound.getId().getPath() : "",
                System.currentTimeMillis()));
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

    /**
     * (2026-09-04 人类式听觉定位) 返回最近 N 秒内听到的、声音 id 以
     * {@code idPathPrefix} 开头 (如 "entity.blaze." 匹配烈焰人全系音效)
     * 且在 {@code maxDistance} 格以内的最近声源位置。
     *
     * 人类在 Minecraft 里能听到约 16 格内的音效并大致判断方向; 本方法
     * 把这条线索暴露给探索任务 (例如循声找烈焰人刷怪笼)。
     */
    public synchronized Optional<Vec3d> getNearestRecentSoundPos(String idPathPrefix, Vec3d fromPos, double maxDistance) {
        prune();
        Vec3d best = null;
        double bestSq = maxDistance * maxDistance;
        for (RecentSound s : _recentSounds) {
            if (s.soundIdPath == null || !s.soundIdPath.startsWith(idPathPrefix)) continue;
            double sq = s.pos.squaredDistanceTo(fromPos);
            if (sq < bestSq) {
                bestSq = sq;
                best = s.pos;
            }
        }
        return Optional.ofNullable(best);
    }

    private static class RecentSound {
        final Vec3d pos;
        final String soundIdPath;
        final long timeMs;

        RecentSound(double x, double y, double z, String soundIdPath, long timeMs) {
            this.pos = new Vec3d(x, y, z);
            this.soundIdPath = soundIdPath;
            this.timeMs = timeMs;
        }
    }
}
