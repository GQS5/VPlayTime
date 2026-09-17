package site.vackstudio.vplaytime.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;
import site.vackstudio.vplaytime.VPlaytimePlugin;
import site.vackstudio.vplaytime.gui.PlaytimeFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /vplaytime} (aliases {@code /playtime}, {@code /ptr}).
 *
 * <p>No arguments opens the {@code main} menu (players with loaded data).
 * One argument opens the named menu ({@code /vplaytime menu_2}) when such a
 * menu exists. Subcommands: {@code reload} (admin), {@code info} (info),
 * {@code reset}/{@code resetall} (reset). Every subcommand checks its own
 * permission; malformed input gets usage, never an exception. Storage-backed
 * results arrive asynchronously and are delivered back on a safe thread
 * (entity scheduler for players, direct for console).
 */
@NullMarked
public final class VPlaytimeCommand implements BasicCommand {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final VPlaytimePlugin plugin;

    public VPlaytimeCommand(VPlaytimePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            openMenu(sender);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender, args);
            case "info" -> info(sender, args);
            case "reset" -> reset(sender, args);
            case "resetall" -> resetAll(sender, args);
            default -> {
                if (args.length == 1) {
                    openMenuId(sender, args[0].toLowerCase(Locale.ROOT));
                } else {
                    tell(sender, plugin.configManager().message(
                            plugin.configManager().messages().usage()));
                }
            }
        }
    }

    private void openMenu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            tell(sender, plugin.configManager().message(
                    plugin.configManager().messages().playersOnly()));
            return;
        }
        if (plugin.playtimeManager().find(player.getUniqueId()).isEmpty()) {
            tell(sender, plugin.configManager().loadingMessage());
            return;
        }
        plugin.mainMenu().open(player);
        announceOpened(player, plugin.mainMenu().definition().id());
    }

    private void openMenuId(CommandSender sender, String menuId) {
        if (!(sender instanceof Player player)) {
            tell(sender, plugin.configManager().message(
                    plugin.configManager().messages().playersOnly()));
            return;
        }
        if (plugin.playtimeManager().find(player.getUniqueId()).isEmpty()) {
            tell(sender, plugin.configManager().loadingMessage());
            return;
        }
        var menu = plugin.findMenu(menuId).orElse(null);
        if (menu == null) {
            tell(sender, plugin.configManager().message(
                    plugin.configManager().messages().unknownMenu(), Map.of("menu", menuId)));
            return;
        }
        menu.open(player);
        announceOpened(player, menuId);
    }

    /** Opt-in "menu opened" notice (silent by default). */
    private void announceOpened(Player player, String menuId) {
        String raw = plugin.configManager().messages().menuOpened();
        if (raw != null && !raw.isBlank()) {
            tell(player, plugin.configManager().message(raw, Map.of("menu", menuId)));
        }
    }

    private void reload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vplaytime.admin")) {
            tell(sender, plugin.configManager().message(
                    plugin.configManager().messages().noPermission()));
            return;
        }
        if (args.length != 1) {
            tell(sender, plugin.configManager().message(
                    plugin.configManager().messages().usage()));
            return;
        }
        var failure = plugin.vplaytimeReload();
        if (failure.isEmpty()) {
            tell(sender, plugin.configManager().reloadedOkMessage());
            return;
        }
        // Generic configured line first, then the precise reason.
        tell(sender, plugin.configManager().reloadedFailMessage());
        tell(sender, plugin.configManager().message(
                plugin.configManager().messages().reloadDetail(), Map.of("detail", failure.get())));
    }

    private void info(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vplaytime.info")) {
            tell(sender, plugin.configManager().message(
                    plugin.configManager().messages().noPermission()));
            return;
        }
        if (args.length != 2) {
            tell(sender, plugin.configManager().message(
                    plugin.configManager().messages().usage()));
            return;
        }
        Target target = resolve(args[1]);
        if (target == null) {
            tell(sender, plugin.configManager().message(
                    plugin.configManager().messages().unknownPlayer(), Map.of("player", args[1])));
            return;
        }
        plugin.adminService().info(target.uuid()).thenAccept(info -> {
            if (!info.found()) {
                tellAsync(sender, plugin.configManager().message(
                        plugin.configManager().messages().noData(), Map.of("player", target.label())));
                return;
            }
            var messages = plugin.configManager().messages();
            var manager = plugin.configManager();
            List<String> lines = new ArrayList<>();
            lines.add(manager.message(messages.infoHeader(), Map.of("player", target.label())));
            lines.add(manager.message(messages.infoUuid(), Map.of("uuid", info.uuid().toString())));
            lines.add(manager.message(messages.infoProvider(),
                    Map.of("provider", plugin.playtimeManager().providerId())));
            if (info.online()) {
                lines.add(manager.message(messages.infoPlaytimeOnline(), Map.of(
                        "playtime", PlaytimeFormat.format(info.effectiveSeconds()),
                        "stored", PlaytimeFormat.format(info.storedSeconds()))));
            } else {
                lines.add(manager.message(messages.infoPlaytimeOffline(), Map.of(
                        "playtime", PlaytimeFormat.format(info.storedSeconds()))));
            }
            if (info.claimed().isEmpty()) {
                lines.add(manager.message(messages.infoClaimsNone(),
                        Map.of("total", Integer.toString(info.rewardCount()))));
            } else {
                lines.add(manager.message(messages.infoClaims(), Map.of(
                        "claimed", Integer.toString(info.claimed().size()),
                        "total", Integer.toString(info.rewardCount()),
                        "list", String.join(", ", info.claimed()))));
            }
            tellAsync(sender, String.join("<newline>", lines));
        });
    }

    private void reset(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vplaytime.reset")) {
            tell(sender, plugin.configManager().message(
                    plugin.configManager().messages().noPermission()));
            return;
        }
        if (args.length != 3) {
            tell(sender, plugin.configManager().message(
                    plugin.configManager().messages().usage()));
            return;
        }
        Target target = resolve(args[1]);
        if (target == null) {
            tell(sender, plugin.configManager().message(
                    plugin.configManager().messages().unknownPlayer(), Map.of("player", args[1])));
            return;
        }
        String rewardId = args[2].toLowerCase(Locale.ROOT);
        plugin.adminService().reset(target.uuid(), rewardId).thenAccept(outcome -> {
            if (!outcome.ok()) {
                tellAsync(sender, plugin.configManager().message(
                        plugin.configManager().messages().resetFailed(), Map.of(
                                "player", target.label(), "detail", outcome.detail())));
                return;
            }
            String note = outcome.hadClaim() ? "Reward reset." : "Player had no such claim.";
            tellAsync(sender, plugin.configManager().message(
                    plugin.configManager().messages().resetDone(), Map.of(
                            "reward", rewardId, "player", target.label(), "note", note)));
        });
    }

    private void resetAll(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vplaytime.reset")) {
            tell(sender, plugin.configManager().message(
                    plugin.configManager().messages().noPermission()));
            return;
        }
        if (args.length != 2) {
            tell(sender, plugin.configManager().message(
                    plugin.configManager().messages().usage()));
            return;
        }
        Target target = resolve(args[1]);
        if (target == null) {
            tell(sender, plugin.configManager().message(
                    plugin.configManager().messages().unknownPlayer(), Map.of("player", args[1])));
            return;
        }
        plugin.adminService().resetAll(target.uuid()).thenAccept(outcome -> {
            if (!outcome.ok()) {
                tellAsync(sender, plugin.configManager().message(
                        plugin.configManager().messages().resetFailed(), Map.of(
                                "player", target.label(), "detail", outcome.detail())));
                return;
            }
            tellAsync(sender, plugin.configManager().message(
                    plugin.configManager().messages().resetAllDone(), Map.of(
                            "detail", outcome.detail(), "player", target.label())));
        });
    }

    /** Resolves a name or UUID to an online player or a known offline player. */
    private static Target resolve(String input) {
        try {
            UUID uuid = UUID.fromString(input);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                return new Target(uuid, online.getName());
            }
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            return new Target(uuid, offline.getName() != null ? offline.getName() : uuid.toString());
        } catch (IllegalArgumentException notUuid) {
            Player exact = Bukkit.getPlayerExact(input);
            if (exact != null) {
                return new Target(exact.getUniqueId(), exact.getName());
            }
            OfflinePlayer offline = Bukkit.getOfflinePlayer(input);
            if (offline.hasPlayedBefore() || offline.isOnline()) {
                return new Target(offline.getUniqueId(), input);
            }
            return null;
        }
    }

    private record Target(UUID uuid, String label) {
    }

    private void tell(CommandSender sender, String mini) {
        if (mini == null || mini.isBlank()) {
            return;
        }
        sender.sendMessage(MINI.deserialize(mini));
    }

    /**
     * Thread-safe feedback for async completions: players via their entity
     * scheduler (Folia-safe); console directly, mirrored to the server log so
     * RCON/panel admins never lose async outcomes to response timing.
     */
    private void tellAsync(CommandSender sender, String mini) {
        if (mini == null || mini.isBlank()) {
            return;
        }
        Component message = MINI.deserialize(mini);
        if (sender instanceof Player player) {
            if (!player.isOnline()) {
                return;
            }
            player.getScheduler().run(plugin, task -> player.sendMessage(message), null);
        } else {
            sender.sendMessage(message);
            plugin.getLogger().info("[admin] " + mini.replaceAll("<[^>]*>", ""));
        }
    }

    @Override
    public String permission() {
        return "vplaytime.use";
    }

    @Override
    public List<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length <= 1) {
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission("vplaytime.admin")) {
                subs.add("reload");
            }
            if (sender.hasPermission("vplaytime.info")) {
                subs.add("info");
            }
            if (sender.hasPermission("vplaytime.reset")) {
                subs.add("reset");
                subs.add("resetall");
            }
            subs.addAll(plugin.configManager().menus().keySet());
            return subs;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("info")
                || args[0].equalsIgnoreCase("reset")
                || args[0].equalsIgnoreCase("resetall"))) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return names;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("reset")) {
            return new ArrayList<>(plugin.rewardManager().all().keySet());
        }
        return List.of();
    }
}
