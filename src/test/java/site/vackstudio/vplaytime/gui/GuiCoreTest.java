package site.vackstudio.vplaytime.gui;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import site.vackstudio.vplaytime.gui.MenuClickHandler.ClickAction;
import site.vackstudio.vplaytime.model.RewardState;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure GUI logic: durations, placeholders, state resolution, click decisions.
 */
class GuiCoreTest {

    // ---- PlaytimeFormat ----

    @Test
    void formatsDurations() {
        assertEquals("0 seconds", PlaytimeFormat.format(0));
        assertEquals("0 seconds", PlaytimeFormat.format(-5));
        assertEquals("1 second", PlaytimeFormat.format(1));
        assertEquals("45 seconds", PlaytimeFormat.format(45));
        assertEquals("1 minute", PlaytimeFormat.format(60));
        assertEquals("12 minutes", PlaytimeFormat.format(720));
        assertEquals("1 minute 1 second", PlaytimeFormat.format(61));
        assertEquals("1 hour", PlaytimeFormat.format(3600));
        assertEquals("1 hour 2 minutes", PlaytimeFormat.format(3720));
        assertEquals("2 hours 5 minutes", PlaytimeFormat.format(7500));
    }

    // ---- Placeholders ----

    @Test
    void resolvesAllPlaceholders() {
        Placeholders.Context ctx = new Placeholders.Context(3720L, 1800L, RewardState.CLAIMABLE);
        assertEquals("1 hour 2 minutes", Placeholders.resolve("%playtime%", ctx));
        assertEquals("3720", Placeholders.resolve("%playtime_seconds%", ctx));
        assertEquals("62", Placeholders.resolve("%playtime_minutes%", ctx));
        assertEquals("1", Placeholders.resolve("%playtime_hours%", ctx));
        assertEquals("30 minutes", Placeholders.resolve("%required_playtime%", ctx));
        assertEquals("CLAIMABLE", Placeholders.resolve("%reward_status%", ctx));
    }

    @Test
    void leavesUnknownPlaceholdersAlone() {
        Placeholders.Context ctx = new Placeholders.Context(10L, 10L, RewardState.LOCKED);
        assertEquals("Hello %player%!", Placeholders.resolve("Hello %player%!", ctx));
        assertEquals("", Placeholders.resolve("", ctx));
    }

    // ---- RewardStateRenderer ----

    private static site.vackstudio.vplaytime.model.RewardDefinition def() {
        var locked = new site.vackstudio.vplaytime.model.RewardDisplay.DisplayState(
                Material.RED_STAINED_GLASS_PANE, java.util.List.of("<red>%required_playtime%"), false);
        var claimable = new site.vackstudio.vplaytime.model.RewardDisplay.DisplayState(
                Material.GRAY_STAINED_GLASS_PANE, java.util.List.of("%playtime% / %reward_status%"), true);
        var claimed = new site.vackstudio.vplaytime.model.RewardDisplay.DisplayState(
                Material.LIME_STAINED_GLASS_PANE, java.util.List.of("done"), false);
        var display = new site.vackstudio.vplaytime.model.RewardDisplay("R %playtime_hours%h", locked, claimable, claimed);
        return new site.vackstudio.vplaytime.model.RewardDefinition(
                "r1", 11, 1800L, display, java.util.List.of());
    }

    @Test
    void resolvesLockedVisual() {
        RenderedReward rendered = RewardStateRenderer.resolve(def(), RewardState.LOCKED, 100L);
        assertEquals(Material.RED_STAINED_GLASS_PANE, rendered.material());
        assertEquals(java.util.List.of("<red>30 minutes"), rendered.lore());
        assertEquals(false, rendered.glow());
        assertEquals("R 0h", rendered.name());
    }

    @Test
    void resolvesClaimableVisualWithGlow() {
        RenderedReward rendered = RewardStateRenderer.resolve(def(), RewardState.CLAIMABLE, 3720L);
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, rendered.material());
        assertEquals(true, rendered.glow());
        assertEquals(java.util.List.of("1 hour 2 minutes / CLAIMABLE"), rendered.lore());
        assertEquals("R 1h", rendered.name());
    }

    @Test
    void resolvesClaimedVisual() {
        RenderedReward rendered = RewardStateRenderer.resolve(def(), RewardState.CLAIMED, 9999L);
        assertEquals(Material.LIME_STAINED_GLASS_PANE, rendered.material());
        assertEquals(false, rendered.glow());
    }

    // ---- MenuSounds.canonicalKey ----
    // (Live-registry resolution is covered by MenuSoundsTest with a fake
    // registry plus the Folia smoke test.)

    @Test
    void canonicalizesSoundKeys() {
        assertEquals("minecraft:block.chest.open", MenuSounds.canonicalKey("block.chest.open"));
        assertEquals("minecraft:block.chest.open", MenuSounds.canonicalKey("minecraft:block.chest.open"));
        assertEquals("minecraft:block.chest.open", MenuSounds.canonicalKey("  Minecraft:Block.Chest.Open  "));
        assertEquals("minecraft:entity.player.levelup", MenuSounds.canonicalKey("entity.player.levelup"));
    }

    // ---- MenuClickHandler ----

    private static final Map<Integer, String> SLOTS = Map.of(11, "reward_1", 13, "reward_2");

    @Test
    void clickDecisions() {
        // Not our menu / bottom inventory / out of range -> ignore.
        assertEquals(ClickAction.IGNORE,
                MenuClickHandler.decide(false, true, 11, 27, SLOTS, RewardState.CLAIMABLE).action());
        assertEquals(ClickAction.IGNORE,
                MenuClickHandler.decide(true, false, 11, 27, SLOTS, RewardState.CLAIMABLE).action());
        assertEquals(ClickAction.IGNORE,
                MenuClickHandler.decide(true, true, 40, 27, SLOTS, RewardState.CLAIMABLE).action());
        // Unmapped slot / unknown state -> ignore.
        assertEquals(ClickAction.IGNORE,
                MenuClickHandler.decide(true, true, 0, 27, SLOTS, RewardState.CLAIMABLE).action());
        assertEquals(ClickAction.IGNORE,
                MenuClickHandler.decide(true, true, 11, 27, SLOTS, null).action());
        // States.
        assertEquals(ClickAction.LOCKED_FEEDBACK,
                MenuClickHandler.decide(true, true, 11, 27, SLOTS, RewardState.LOCKED).action());
        assertEquals(ClickAction.CLAIMED_FEEDBACK,
                MenuClickHandler.decide(true, true, 13, 27, SLOTS, RewardState.CLAIMED).action());
        var claim = MenuClickHandler.decide(true, true, 11, 27, SLOTS, RewardState.CLAIMABLE);
        assertEquals(ClickAction.CLAIM, claim.action());
        assertEquals("reward_1", claim.rewardId());
    }

    @Test
    void staleVisualNeverMatters() {
        // The handler only sees CURRENT core state. A menu still showing
        // CLAIMABLE while core says CLAIMED yields feedback, never a claim.
        var decision = MenuClickHandler.decide(true, true, 11, 27, SLOTS, RewardState.CLAIMED);
        assertEquals(ClickAction.CLAIMED_FEEDBACK, decision.action());
    }

    // ---- MenuHolder ----

    @Test
    void holderCarriesOnlyIdentity() {
        UUID uuid = UUID.randomUUID();
        MenuHolder holder = new MenuHolder(uuid, "main");
        assertEquals(uuid, holder.playerId());
        assertEquals("main", holder.menuId());
        assertNull(holder.getInventory());
        assertEquals(true, holder instanceof org.bukkit.inventory.InventoryHolder);
    }
}
