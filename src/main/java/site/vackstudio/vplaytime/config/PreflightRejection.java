package site.vackstudio.vplaytime.config;

import site.vackstudio.vplaytime.reward.PreflightReport;

import java.util.List;
import java.util.Map;

/**
 * Aggregated load/reload failure: every collected preflight issue in one
 * throwable. Extends {@link ConfigError} so every existing failure path
 * (console report, player reason, transactional keep-old behavior) keeps
 * working; the added report carries the full per-reward diagnostics plus
 * the partial menus (for GUI diagnostic rendering on invalid startup).
 *
 * <p>Thrown only when the candidate configuration has at least one INVALID
 * finding. UNVERIFIABLE-only configurations activate normally.
 */
public final class PreflightRejection extends ConfigError {

    private final PreflightReport report;
    private final Map<String, MenuDefinition> partialMenus;

    public PreflightRejection(PreflightReport report, Map<String, MenuDefinition> partialMenus) {
        super(MenuRegistry.FILE,
                "",
                report == null ? "Reward preflight validation failed." : report.summary());
        this.report = report == null ? PreflightReport.empty() : report;
        this.partialMenus = Map.copyOf(partialMenus == null ? Map.of() : partialMenus);
    }

    /** Full per-issue diagnostics (counts, entries, retention line). */
    public PreflightReport report() {
        return report;
    }

    /**
     * Successfully parsed menus from the rejected configuration (possibly
     * partial, possibly empty). Used only to render the non-claimable GUI
     * error state when no valid plan exists — never activated.
     */
    public Map<String, MenuDefinition> partialMenus() {
        return partialMenus;
    }

    /** Full diagnostic block for the console log (logger adds the prefix). */
    @Override
    public List<String> reportLines() {
        return report.consoleReport();
    }

    /** One-line player-facing reason (the configured fail message comes first). */
    @Override
    public String playerReason() {
        return report.summary();
    }
}
