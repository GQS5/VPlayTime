package site.vackstudio.vplaytime.reward;

import org.bukkit.Material;
import site.vackstudio.vplaytime.config.MenuDefinition;
import site.vackstudio.vplaytime.model.RewardAction;
import site.vackstudio.vplaytime.model.RewardDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Strict reward preflight validation. Pure and side-effect free: reads the
 * already-parsed menus, asks the injected {@link PreflightEnv} about command
 * availability, and returns one verdict per reward. Never executes anything,
 * never touches YAML, storage or players — validation runs only at startup
 * and reload, and the runtime claim path never calls back into here.
 *
 * <p>Severity policy (fail-closed):
 * <ul>
 *   <li>Provably broken (empty command, unknown root, wrong {@code xp}
 *       syntax, bad material/amount, malformed or unknown placeholder,
 *       missing player token on player-targeted commands) →
 *       {@code INVALID}, blocks activation of the whole configuration.</li>
 *   <li>Structurally sound but not statically provable (an {@code @p}
 *       selector target) → {@code UNVERIFIABLE}, reported but not blocking.
 *       Never used to bypass obvious errors: any INVALID issue wins.</li>
 * </ul>
 *
 * <p>Availability is decided from command registration (an enabled plugin
 * or vanilla providing the root). A provider that enables after VPlayTime
 * is picked up by the next reload. Console dispatch itself is never used
 * for validation: zero reward side effects by construction.
 */
public final class RewardPreflightValidator {

    static final String FILE = "rewards.yml";

    /** Placeholders the claim engine substitutes itself (see ClaimManager). */
    private static final Set<String> KNOWN_TOKENS = Set.of("player", "uuid", "claim_id");

    /** Vanilla roots treated as always available (plus every env-registered root). */
    private static final Set<String> VANILLA_ROOTS = Set.of(
            "xp", "give", "say", "tell", "msg", "w", "effect", "clear",
            "enchant", "playsound", "stopsound", "title", "summon",
            "time", "weather", "gamemode", "kick");

    /**
     * Known external integrations: root → provider label + fix hint.
     * Availability still comes from registration, never from this table.
     */
    private static final Map<String, KnownIntegration> KNOWN_EXTERNAL = Map.of(
            "addmoney", new KnownIntegration("economy provider",
                    "Install or enable an economy plugin providing /addmoney"),
            "addshards", new KnownIntegration("shards provider",
                    "Install or enable the shards plugin providing /addshards"),
            "points", new KnownIntegration("points provider",
                    "Install or enable the points plugin providing /points"),
            "cc", new KnownIntegration("crate provider",
                    "Install or enable the crates plugin providing /cc"),
            "eco", new KnownIntegration("economy provider",
                    "Install or enable an economy plugin providing /eco"),
            "lp", new KnownIntegration("LuckPerms",
                    "Install or enable LuckPerms"),
            "luckperms", new KnownIntegration("LuckPerms",
                    "Install or enable LuckPerms"));

    /** Roots whose commands must name the claiming player. */
    private static final Set<String> PLAYER_TARGETED = Set.of(
            "xp", "give", "addmoney", "addshards", "points", "cc", "eco");

    private record KnownIntegration(String provider, String fix) {
    }

    private RewardPreflightValidator() {
    }

    /**
     * Validates every reward in every menu (locked, later pages, invisible,
     * claimed — all of them), collecting every issue without stopping at
     * the first error.
     *
     * @return reward id → verdict, file order
     */
    public static Map<String, PreflightResult> validate(
            Map<String, MenuDefinition> menus, PreflightEnv env) {
        Map<String, PreflightResult> results = new LinkedHashMap<>();
        if (menus == null) {
            return results;
        }
        for (MenuDefinition menu : menus.values()) {
            if (menu == null) {
                continue;
            }
            for (RewardDefinition def : menu.rewards().values()) {
                if (def == null || results.containsKey(def.id())) {
                    continue;
                }
                results.put(def.id(), validateReward(menu.id(), def, env));
            }
        }
        return Map.copyOf(results);
    }

    private static PreflightResult validateReward(String menuId, RewardDefinition def, PreflightEnv env) {
        List<PreflightIssue> invalid = new ArrayList<>();
        List<PreflightIssue> unverifiable = new ArrayList<>();
        String base = "menus." + menuId + ".rewards." + def.id();
        String level = levelLabel(def.id());

        if (def.requiredSeconds() < 0) {
            invalid.add(new PreflightIssue(FILE, base, def.id(), level, -1,
                    PreflightIssue.Type.REQUIRED_PLAYTIME, Long.toString(def.requiredSeconds()),
                    "Required playtime is negative.", "Use 'time: 1h' or a non-negative 'required-seconds'."));
        }
        if (def.display() == null) {
            invalid.add(new PreflightIssue(FILE, base + ".display", def.id(), level, -1,
                    PreflightIssue.Type.DISPLAY, "-",
                    "Reward has no display.", "Add 'display:' with name and per-state materials."));
        } else {
            checkDisplayState(base, def, "locked", invalid);
            checkDisplayState(base, def, "claimable", invalid);
            checkDisplayState(base, def, "claimed", invalid);
        }
        if (def.actions() == null || def.actions().isEmpty()) {
            invalid.add(new PreflightIssue(FILE, base + ".actions", def.id(), level, -1,
                    PreflightIssue.Type.ACTION, "-",
                    "Reward defines no actions.", "Add at least one '- command:' or '- item:' entry."));
        } else {
            for (int index = 0; index < def.actions().size(); index++) {
                validateAction(base, def, index, def.actions().get(index), env, invalid, unverifiable);
            }
        }
        if (!invalid.isEmpty()) {
            return new PreflightResult(def.id(), PreflightResult.Status.INVALID, invalid);
        }
        if (!unverifiable.isEmpty()) {
            return new PreflightResult(def.id(), PreflightResult.Status.UNVERIFIABLE, unverifiable);
        }
        return PreflightResult.valid(def.id());
    }

    private static void checkDisplayState(
            String base, RewardDefinition def, String state, List<PreflightIssue> invalid) {
        var look = def.display().forState(stateFor(state));
        if (look == null || look.material() == null) {
            invalid.add(new PreflightIssue(FILE, base + ".display." + state, def.id(),
                    levelLabel(def.id()), -1, PreflightIssue.Type.MATERIAL, "-",
                    "The " + state + " look has no material.",
                    "Set 'display." + state + ".material:' to a 1.21.11 material."));
        }
    }

    private static site.vackstudio.vplaytime.model.RewardState stateFor(String state) {
        return switch (state) {
            case "claimable" -> site.vackstudio.vplaytime.model.RewardState.CLAIMABLE;
            case "claimed" -> site.vackstudio.vplaytime.model.RewardState.CLAIMED;
            default -> site.vackstudio.vplaytime.model.RewardState.LOCKED;
        };
    }

    private static void validateAction(
            String base,
            RewardDefinition def,
            int index,
            RewardAction action,
            PreflightEnv env,
            List<PreflightIssue> invalid,
            List<PreflightIssue> unverifiable) {
        String path = base + ".actions[" + index + "]";
        if (action == null) {
            invalid.add(new PreflightIssue(FILE, path, def.id(), levelLabel(def.id()), index,
                    PreflightIssue.Type.ACTION, "-",
                    "Action entry is empty.", "Use '- command: \"...\"' or '- item: \"DIAMOND 10\"'."));
            return;
        }
        if (action instanceof RewardAction.ItemAction item) {
            validateItem(path, def, index, item, invalid);
            return;
        }
        if (action instanceof RewardAction.CommandAction command) {
            validateCommand(path, def, index, command.command(), env, invalid, unverifiable);
            return;
        }
        invalid.add(new PreflightIssue(FILE, path, def.id(), levelLabel(def.id()), index,
                PreflightIssue.Type.ACTION, action.toString(),
                "Unsupported action type.", "Use '- command:' or '- item:'."));
    }

    private static void validateItem(
            String path, RewardDefinition def, int index, RewardAction.ItemAction item,
            List<PreflightIssue> invalid) {
        String level = levelLabel(def.id());
        if (item.material() == null || item.material() == Material.AIR) {
            invalid.add(new PreflightIssue(FILE, path, def.id(), level, index,
                    PreflightIssue.Type.MATERIAL, String.valueOf(item.material()),
                    "Material does not exist.", "Replace it with a valid 1.21.11 material."));
            return;
        }
        if (item.amount() < 1 || item.amount() > 64) {
            invalid.add(new PreflightIssue(FILE, path, def.id(), level, index,
                    PreflightIssue.Type.AMOUNT, Integer.toString(item.amount()),
                    "Amount " + item.amount() + " is outside 1..64.",
                    "Use an amount from 1 to 64."));
        }
    }

    static void validateCommand(
            String path,
            RewardDefinition def,
            int index,
            String command,
            PreflightEnv env,
            List<PreflightIssue> invalid,
            List<PreflightIssue> unverifiable) {
        String level = levelLabel(def.id());
        if (command == null || command.isBlank()) {
            invalid.add(new PreflightIssue(FILE, path, def.id(), level, index,
                    PreflightIssue.Type.COMMAND, command == null ? "" : command,
                    "Invalid command definition: blank command.",
                    "Write the full console command, e.g. 'xp add %player% 500'."));
            return;
        }
        String root = CommandFailure.rootLabel(command);
        if (root.isEmpty()) {
            invalid.add(new PreflightIssue(FILE, path, def.id(), level, index,
                    PreflightIssue.Type.COMMAND, command.trim(),
                    "Invalid command definition: no command root.",
                    "Start the line with the command name, e.g. 'xp add %player% 500'."));
            return;
        }
        // Placeholder scan first: unknown or malformed tokens are always
        // wrong — the claim engine substitutes only %player%/%uuid%/%claim_id%
        // and console dispatch resolves nothing else.
        if (!scanPlaceholders(path, def, index, command, invalid)) {
            return;
        }
        List<String> args = argsOf(command);
        if (root.equals("xp") || root.equals("exp")) {
            validateXpCommand(path, def, index, command, args, invalid);
            return;
        }
        if (root.equals("give")) {
            validateGiveCommand(path, def, index, command, args, invalid, unverifiable);
            return;
        }
        if (PLAYER_TARGETED.contains(root) && !hasPlayerToken(command)) {
            invalid.add(new PreflightIssue(FILE, path, def.id(), level, index,
                    PreflightIssue.Type.COMMAND, command.trim(),
                    "Command has no player placeholder.",
                    "Add %player% (or %uuid%) so the grant targets the claiming player."));
        }
        if (!isAvailable(root, env)) {
            KnownIntegration known = KNOWN_EXTERNAL.get(root);
            String provider = known == null ? "plugin providing /" + root : known.provider();
            String fix = known == null
                    ? "Install or enable a plugin providing /" + root + ", fix the command, then reload."
                    : known.fix() + ", then reload.";
            invalid.add(new PreflightIssue(FILE, path, def.id(), level, index,
                    PreflightIssue.Type.EXTERNAL_PROVIDER, root,
                    "Required provider unavailable: " + provider + ".",
                    fix));
        }
    }

    /**
     * The only supported XP form on 1.21.11 is {@code xp add %player%
     * <amount>}. {@code xp give} / {@code exp give} resemble it but dispatch
     * nothing (vanilla has no such subcommand) — and the old money-farm
     * incident showed exactly this shape silently paying earlier actions.
     */
    private static void validateXpCommand(
            String path, RewardDefinition def, int index, String command,
            List<String> args, List<PreflightIssue> invalid) {
        String level = levelLabel(def.id());
        if (args.size() < 3 || !args.get(0).equals("add")) {
            invalid.add(new PreflightIssue(FILE, path, def.id(), level, index,
                    PreflightIssue.Type.COMMAND, command.trim(),
                    "Invalid command definition: unsupported XP syntax.",
                    "Use the supported XP command syntax 'xp add %player% <amount>'."));
            return;
        }
        if (!hasPlayerToken(args.get(1))) {
            invalid.add(new PreflightIssue(FILE, path, def.id(), level, index,
                    PreflightIssue.Type.COMMAND, command.trim(),
                    "XP command has no player placeholder.",
                    "Use 'xp add %player% <amount>'."));
            return;
        }
        if (!isPositiveInt(args.get(2))) {
            invalid.add(new PreflightIssue(FILE, path, def.id(), level, index,
                    PreflightIssue.Type.COMMAND, command.trim(),
                    "XP amount '" + args.get(2) + "' is not a positive integer.",
                    "Use 'xp add %player% <amount>' with a whole number above 0."));
            return;
        }
        if (args.size() > 3 && !args.get(3).equals("levels") && !args.get(3).equals("points")) {
            invalid.add(new PreflightIssue(FILE, path, def.id(), level, index,
                    PreflightIssue.Type.COMMAND, command.trim(),
                    "XP command has an unsupported trailing argument '" + args.get(3) + "'.",
                    "Use 'xp add %player% <amount>' (optionally levels|points)."));
        }
    }

    private static void validateGiveCommand(
            String path, RewardDefinition def, int index, String command,
            List<String> args, List<PreflightIssue> invalid, List<PreflightIssue> unverifiable) {
        String level = levelLabel(def.id());
        if (args.size() < 2) {
            invalid.add(new PreflightIssue(FILE, path, def.id(), level, index,
                    PreflightIssue.Type.COMMAND, command.trim(),
                    "Invalid command definition: 'give' needs a target and an item.",
                    "Use 'give %player% <ITEM> [amount]'."));
            return;
        }
        String target = args.get(0);
        if (!hasPlayerToken(target) && !target.startsWith("@")) {
            invalid.add(new PreflightIssue(FILE, path, def.id(), level, index,
                    PreflightIssue.Type.COMMAND, command.trim(),
                    "Give command has no player placeholder.",
                    "Use 'give %player% <ITEM> [amount]'."));
            return;
        }
        if (target.startsWith("@")) {
            unverifiable.add(new PreflightIssue(FILE, path, def.id(), level, index,
                    PreflightIssue.Type.COMMAND, command.trim(),
                    "Give target '" + target + "' cannot be proven to be the claiming player.",
                    "Prefer 'give %player% <ITEM> [amount]' when possible."));
        }
        String item = args.size() > 1 ? args.get(1) : "";
        if (item.contains("%")) {
            unverifiable.add(new PreflightIssue(FILE, path, def.id(), level, index,
                    PreflightIssue.Type.PLACEHOLDER, command.trim(),
                    "Give item '" + item + "' is dynamic and cannot be proven valid.",
                    "Prefer a literal 1.21.11 material when possible."));
        } else if (Material.matchMaterial(item.toUpperCase(Locale.ROOT)) == null) {
            invalid.add(new PreflightIssue(FILE, path, def.id(), level, index,
                    PreflightIssue.Type.MATERIAL, item,
                    "Material does not exist.", "Replace it with a valid 1.21.11 material."));
        }
        if (args.size() > 2 && !isPositiveInt(args.get(2)) && !args.get(2).contains("%")) {
            invalid.add(new PreflightIssue(FILE, path, def.id(), level, index,
                    PreflightIssue.Type.AMOUNT, args.get(2),
                    "Give amount '" + args.get(2) + "' is not a positive integer.",
                    "Use an amount from 1 to 64."));
        }
    }

    /**
     * Scans {@code %...%} tokens. Returns false when an INVALID placeholder
     * issue was recorded (callers stop: the command is already rejected).
     *
     * <p>Resync rule: a pair whose inner text contains a space cannot be a
     * token ({@code 100% legit %player%}), so the opener is treated as
     * literal text and scanning resumes after it. A trailing lone percent
     * ({@code 100%}) is likewise literal. Empty pairs ({@code %%}) and
     * non-token inners ({@code %Player%}) are malformed.
     */
    static boolean scanPlaceholders(
            String path, RewardDefinition def, int index, String command,
            List<PreflightIssue> invalid) {
        String level = levelLabel(def.id());
        int i = 0;
        while (i < command.length()) {
            int open = command.indexOf('%', i);
            if (open < 0) {
                return true;
            }
            int close = command.indexOf('%', open + 1);
            if (close < 0) {
                return true; // lone percent: literal text
            }
            String inner = command.substring(open + 1, close);
            if (inner.isEmpty()) {
                invalid.add(new PreflightIssue(FILE, path, def.id(), level, index,
                        PreflightIssue.Type.PLACEHOLDER, command.trim(),
                        "Malformed placeholder '%%'.",
                        "Use %player%, %uuid% or %claim_id%, or remove the token."));
                return false;
            }
            if (inner.indexOf(' ') >= 0 || inner.indexOf('\t') >= 0) {
                i = open + 1; // not a token: opener is literal, resync after it
                continue;
            }
            if (!isTokenChars(inner)) {
                invalid.add(new PreflightIssue(FILE, path, def.id(), level, index,
                        PreflightIssue.Type.PLACEHOLDER, command.trim(),
                        "Malformed placeholder '%" + inner + "%'.",
                        "Use %player%, %uuid% or %claim_id%, or remove the token."));
                return false;
            }
            if (!KNOWN_TOKENS.contains(inner)) {
                invalid.add(new PreflightIssue(FILE, path, def.id(), level, index,
                        PreflightIssue.Type.PLACEHOLDER, command.trim(),
                        "Unknown placeholder '%" + inner + "%'. Only %player%, %uuid% and"
                                + " %claim_id% are substituted.",
                        inner.equals("player_name")
                                ? "Use %player% instead of %player_name%."
                                : "Remove the token or replace it with %player%, %uuid% or %claim_id%."));
                return false;
            }
            i = close + 1;
        }
        return true;
    }

    private static boolean isTokenChars(String inner) {
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (!(c == '_' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    static boolean isAvailable(String root, PreflightEnv env) {
        if (VANILLA_ROOTS.contains(root)) {
            return true;
        }
        if (env == null) {
            return false;
        }
        try {
            return env.commandRegistered(root);
        } catch (Exception ex) {
            return false;
        }
    }

    static boolean hasPlayerToken(String text) {
        return text != null && (text.contains("%player%") || text.contains("%uuid%"));
    }

    private static List<String> argsOf(String command) {
        String trimmed = command.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
        }
        String[] parts = trimmed.split("\\s+");
        List<String> args = new ArrayList<>(Math.max(0, parts.length - 1));
        for (int i = 1; i < parts.length; i++) {
            args.add(parts[i]);
        }
        return List.copyOf(args);
    }

    private static boolean isPositiveInt(String text) {
        try {
            return Integer.parseInt(text) > 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** Numeric suffix of {@code level_N} ids for diagnostics; "" otherwise. */
    static String levelLabel(String rewardId) {
        if (rewardId != null && rewardId.startsWith("level_")) {
            try {
                int level = Integer.parseInt(rewardId.substring("level_".length()));
                if (level > 0) {
                    return Integer.toString(level);
                }
            } catch (NumberFormatException ignored) {
                // Not a numeric level id: no label.
            }
        }
        return "";
    }
}
