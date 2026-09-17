package site.vackstudio.vplaytime.reward;

import site.vackstudio.vplaytime.model.RewardAction;
import site.vackstudio.vplaytime.model.RewardDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Precise wording for failed console command actions.
 *
 * <p>{@code Bukkit.dispatchCommand} collapses every failure to
 * {@code false}: unknown command and executor rejection look identical.
 * The production-only target probes command existence separately and
 * reports through here, so the console tells admins whether the command
 * is missing entirely or rejected at execution (sender/args). Pure and
 * unit-testable; the Bukkit probe itself lives in
 * {@link BukkitClaimTarget}.
 */
public final class CommandFailure {

    private CommandFailure() {
    }

    /**
     * Root label of a command line: first token, lowercased, with a
     * leading slash stripped ({@code "/addmoney RM7PC 1000"} →
     * {@code "addmoney"}). Empty or blank input yields {@code ""}.
     */
    public static String rootLabel(String command) {
        if (command == null) {
            return "";
        }
        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
        }
        int space = trimmed.indexOf(' ');
        String root = space < 0 ? trimmed : trimmed.substring(0, space);
        int colon = root.indexOf(':');
        if (colon >= 0) {
            root = root.substring(colon + 1);
        }
        return root.toLowerCase(Locale.ROOT);
    }

    /**
     * One-line diagnosis for a failed dispatch.
     *
     * @param command the full substituted command line
     * @param known   whether any enabled plugin currently provides the root label
     */
    public static String describe(String command, boolean known) {        String root = rootLabel(command);
        if (!known) {
            return "unknown command '/" + root + "' — no enabled plugin provides it;"
                    + " check the command name in rewards.yml"
                    + (command == null ? "" : " (tried: '" + command.trim() + "')");
        }
        return "command '" + (command == null ? "" : command.trim()) + "' was rejected"
                + " by '/" + root + "' (executor returned false) — check console-sender"
                + " support and argument order/syntax of that plugin's command";
    }

    /**
     * Collects command root labels used by any reward that fail the
     * {@code isKnown} probe, mapped to one example reward id each (sorted).
     * Pure: the caller supplies the probe (live command map on the server,
     * a fake set in tests). Used at load time so a misconfigured command
     * warns once per reload instead of once per failed claim.
     */
    public static Map<String, String> unknownRoots(
            Map<String, RewardDefinition> rewards, Predicate<String> isKnown) {
        Map<String, String> unknown = new TreeMap<>();
        if (rewards == null || isKnown == null) {
            return unknown;
        }
        for (RewardDefinition def : rewards.values()) {
            if (def == null || def.actions() == null) {
                continue;
            }
            for (RewardAction action : def.actions()) {
                if (action instanceof RewardAction.CommandAction command) {
                    String root = rootLabel(command.command());
                    if (!root.isEmpty() && !unknown.containsKey(root)) {
                        boolean known;
                        try {
                            known = isKnown.test(root);
                        } catch (Exception ex) {
                            known = true; // probe failed: never misreport "unknown"
                        }
                        if (!known) {
                            unknown.put(root, def.id());
                        }
                    }
                }
            }
        }
        return unknown;
    }

    /** Human list for the load warning: {@code /exp (e.g. reward level_1)}. */
    public static List<String> describeUnknown(Map<String, String> unknownRoots) {
        List<String> parts = new ArrayList<>(unknownRoots.size());
        for (Map.Entry<String, String> entry : unknownRoots.entrySet()) {
            parts.add("/" + entry.getKey() + " (e.g. reward '" + entry.getValue() + "')");
        }
        return parts;
    }
}
