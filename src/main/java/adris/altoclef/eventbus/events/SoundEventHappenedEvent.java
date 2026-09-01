package adris.altoclef.eventbus.events;

import net.minecraft.sound.SoundEvent;

/**
 * Fired when a positioned sound plays in the world (from the network sound packet).
 */
public class SoundEventHappenedEvent {

    public final SoundEvent sound;
    public final double x;
    public final double y;
    public final double z;
    public final float volume;
    public final float pitch;

    public SoundEventHappenedEvent(SoundEvent sound, double x, double y, double z, float volume, float pitch) {
        this.sound = sound;
        this.x = x;
        this.y = y;
        this.z = z;
        this.volume = volume;
        this.pitch = pitch;
    }
}
