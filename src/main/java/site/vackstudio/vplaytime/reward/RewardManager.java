package site.vackstudio.vplaytime.reward;

import site.vackstudio.vplaytime.config.MenuDefinition;
import site.vackstudio.vplaytime.model.RewardDefinition;
import site.vackstudio.vplaytime.model.RewardState;
import site.vackstudio.vplaytime.playtime.PlayerData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Configuration-driven reward content: lookup and per-player state.
 *
 * <p>Rewards are defined inside menus (rewards.yml), but identity is global:
 * the same reward id may appear in several menus at different slots. This
 * class merges every menu's copies into one content registry — copies must
 * agree on everything except the slot, otherwise loading fails with a
 * message naming both menus. Claim state is shared by id in
 * {@code PlayerData} either way, so claiming in one menu shows everywhere.
 *
 * <p>Definitions are immutable and stored in an unmodifiable map.
 * {@link #parseFromMenus} validates without touching active state and
 * {@link #swap} publishes a pre-validated map, so a failed load (or reload)
 * never corrupts the active definitions.
 */
public final class RewardManager {

    private final Logger logger;
    private volatile Map<String, RewardDefinition> rewards = Map.of();

    public RewardManager(Logger logger) {
        this.logger = logger;
    }

    /**
     * Merges and validates all menus' rewards, then atomically swaps them in.
     *
     * @param menus parsed menus in any order
     * @throws IllegalStateException with a human-readable message on any invalid data
     */
    public synchronized void load(Map<String, MenuDefinition> menus) {
        swap(parseFromMenus(menus));
    }

    /**
     * Merges without swapping: lets callers validate everything before
     * publishing anything (transactional reload).
     *
     * @return validated content registry (first copy wins), file order
     */
    public Map<String, RewardDefinition> parseFromMenus(Map<String, MenuDefinition> menus) {
        Map<String, RewardDefinition> merged = new LinkedHashMap<>();
        Map<String, String> definedIn = new LinkedHashMap<>();
        for (MenuDefinition menu : menus.values()) {
            for (RewardDefinition def : menu.rewards().values()) {
                RewardDefinition existing = merged.get(def.id());
                if (existing == null) {
                    merged.put(def.id(), def);
                    definedIn.put(def.id(), menu.id());
                } else if (!existing.sameContent(def)) {
                    throw new IllegalStateException("Reward '" + def.id() + "' is defined differently in menu '"
                            + definedIn.get(def.id()) + "' and menu '" + menu.id()
                            + "'. Keep every copy identical (except 'slot'), or give one copy a new id.");
                }
            }
        }
        if (merged.isEmpty()) {
            throw new IllegalStateException("No rewards defined in any menu in rewards.yml.");
        }
        return merged;
    }

    /** Publishes a pre-validated map from {@link #parseFromMenus}. */
    public synchronized void swap(Map<String, RewardDefinition> parsed) {
        this.rewards = Map.copyOf(parsed);
        logger.info("Loaded " + parsed.size() + " reward definition(s).");
    }

    public Optional<RewardDefinition> find(String id) {
        return Optional.ofNullable(rewards.get(id));
    }

    public Map<String, RewardDefinition> all() {
        return rewards;
    }

    public int count() {
        return rewards.size();
    }

    /**
     * State of one reward for one player. CLAIMED always wins over playtime.
     * Pure memory read: no storage access.
     *
     * @param effectiveSeconds the player's CURRENT playtime from the active
     *                         {@code PlaytimeProvider} (internal timer or
     *                         external source) — compared against the
     *                         reward's {@code required-seconds}, which stays
     *                         the single source of truth for requirements
     */
    public Optional<RewardState> stateFor(PlayerData data, String rewardId, long effectiveSeconds) {
        RewardDefinition def = rewards.get(rewardId);
        if (def == null || data == null) {
            return Optional.empty();
        }
        if (data.isClaimed(rewardId)) {
            return Optional.of(RewardState.CLAIMED);
        }
        if (effectiveSeconds >= def.requiredSeconds()) {
            return Optional.of(RewardState.CLAIMABLE);
        }
        return Optional.of(RewardState.LOCKED);
    }
}
