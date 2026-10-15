package com.mystipixel.royalsacks.command;

import com.mystipixel.royalsacks.RoyalSacksPlugin;
import com.mystipixel.royalsacks.gui.SackMenu;
import com.mystipixel.royalsacks.message.MessageManager;
import com.mystipixel.royalsacks.sack.SackDefinition;
import com.mystipixel.royalsacks.sack.SackKey;
import com.mystipixel.royalsacks.sack.SackRegistry;
import com.mystipixel.royalsacks.sack.SackService;
import com.mystipixel.royalsacks.sack.SackState;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /sacks [pickup [on|off]]}, and for admins {@code /sacks reload} and
 * {@code /sacks admin <give|take|view> <player> [item] [amount]}.
 */
public final class SacksCommand implements TabExecutor {

    private final RoyalSacksPlugin plugin;

    public SacksCommand(RoyalSacksPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        MessageManager messages = plugin.messages();
        SackService service = plugin.service();
        SackMenu menu = plugin.menu();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> {
                if (!(sender instanceof Player player)) {
                    messages.send(sender, "players-only");
                    return true;
                }
                menu.openMain(player);
            }
            case "pickup" -> {
                if (!(sender instanceof Player player)) {
                    messages.send(sender, "players-only");
                    return true;
                }
                boolean on = args.length > 1 ? args[1].equalsIgnoreCase("on") : !service.pickupEnabled(player);
                service.setPickup(player, on);
                messages.send(player, on ? "pickup-on" : "pickup-off");
            }
            case "reload" -> {
                if (!sender.hasPermission("royalsacks.admin")) {
                    messages.send(sender, "no-permission");
                    return true;
                }
                plugin.reload();
                messages.send(sender, "reloaded");
            }
            case "admin" -> admin(sender, args);
            default -> messages.sendPlain(sender, "usage", Map.of("label", label));
        }
        return true;
    }

    private void admin(CommandSender sender, String[] args) {
        MessageManager messages = plugin.messages();
        SackService service = plugin.service();
        if (!sender.hasPermission("royalsacks.admin")) {
            messages.send(sender, "no-permission");
            return;
        }
        if (args.length < 3) {
            messages.sendPlain(sender, "admin-usage", Map.of());
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            messages.sendPlain(sender, "player-not-found", Map.of("player", args[2]));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("view")) {
            SackKey key = service.keyFor(target);
            SackState state = key == null ? null : service.state(key);
            if (state == null) {
                messages.send(sender, "loading");
                return;
            }
            messages.sendPlain(sender, "admin-view-header", Map.of("player", target.getName(), "scope", key.scope()));
            for (SackDefinition sack : service.registry().all()) {
                for (String item : sack.items()) {
                    long amount = state.amount(item);
                    if (amount > 0) {
                        messages.sendPlain(sender, "admin-view-line", Map.of("sack", sack.display(),
                                "item", item, "amount", String.format("%,d", amount)));
                    }
                }
            }
            return;
        }
        if (args.length < 5 || !(action.equals("give") || action.equals("take"))) {
            messages.sendPlain(sender, "admin-usage", Map.of());
            return;
        }
        String item = SackRegistry.normalize(args[3]);
        long amount;
        try {
            amount = Long.parseLong(args[4]);
        } catch (NumberFormatException e) {
            amount = 0;
        }
        if (amount <= 0 || service.registry().sackOf(item) == null) {
            messages.sendPlain(sender, "admin-usage", Map.of());
            return;
        }
        Map<String, String> values = Map.of("player", target.getName(), "item", item, "amount", String.format("%,d", amount));
        if (action.equals("give")) {
            messages.sendPlain(sender, service.give(target, item, amount) ? "admin-gave" : "admin-failed", values);
        } else {
            service.take(target, item, amount, ok -> messages.sendPlain(sender, ok ? "admin-took" : "admin-failed", values));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> options = new ArrayList<>();
        boolean admin = sender.hasPermission("royalsacks.admin");
        if (args.length == 1) {
            options.add("pickup");
            if (admin) {
                options.add("reload");
                options.add("admin");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("pickup")) {
            options.addAll(List.of("on", "off"));
        } else if (admin && args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            options.addAll(List.of("give", "take", "view"));
        } else if (admin && args.length == 3 && args[0].equalsIgnoreCase("admin")) {
            Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
        } else if (admin && args.length == 4 && args[0].equalsIgnoreCase("admin")) {
            options.addAll(plugin.service().registry().items());
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }
}
