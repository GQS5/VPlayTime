package site.vackstudio.vplaytime.config;

import java.util.List;

/**
 * A configuration load/reload failure with machine-usable context.
 *
 * <p>Extends {@link IllegalStateException} so every existing failure path
 * keeps working; the added file/path context turns "reload failed" into a
 * diagnostic that names the file, the exact key and the reason, e.g.:
 *
 * <pre>
 * [VPlaytime] Reload failed: rewards.yml
 * [VPlaytime] Invalid value at menus.main.items.next.action.open-menu
 * [VPlaytime] Expected a menu id but received an empty value.
 * [VPlaytime] Previous configuration remains active.
 * </pre>
 */
public final class ConfigError extends IllegalStateException {

    private final String file;
    private final String path;

    public ConfigError(String file, String path, String reason) {
        super(reason);
        this.file = file == null || file.isBlank() ? "unknown file" : file;
        this.path = path == null ? "" : path;
    }

    /** Configuration file that failed (e.g. {@code rewards.yml}). */
    public String file() {
        return file;
    }

    /** Dotted key path (e.g. {@code menus.main.items.next}), may be empty. */
    public String path() {
        return path;
    }

    /** Human reason (also {@link #getMessage()}). */
    public String reason() {
        return getMessage();
    }

    /** Full diagnostic for the console log (logger adds the [VPlaytime] prefix). */
    public List<String> reportLines() {
        if (path.isBlank()) {
            return List.of(
                    "Reload failed: " + file,
                    getMessage(),
                    "Previous configuration remains active.");
        }
        return List.of(
                "Reload failed: " + file,
                "Invalid value at " + path,
                getMessage(),
                "Previous configuration remains active.");
    }

    /** One-line player-facing reason (the configured fail message comes first). */
    public String playerReason() {
        if (path.isBlank()) {
            return getMessage();
        }
        return path + ": " + getMessage();
    }
}
