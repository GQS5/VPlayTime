package site.vackstudio.vplaytime.config;

/**
 * One menu sound event as configured: the raw key plus playback volume
 * and pitch. Resolution against the live sound registry happens once at
 * load (see MenuSounds); this record is just the parsed configuration.
 */
public record SoundConfig(String key, float volume, float pitch) {

    public SoundConfig {
        key = key == null ? "" : key;
    }

    /** Blank key means silence (no warning at resolution). */
    public boolean silent() {
        return key.isBlank();
    }
}
