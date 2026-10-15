package com.mystipixel.royalsacks.message;

import com.mystipixel.royalsacks.util.BundledYaml;
import com.mystipixel.royalsacks.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Player-facing text from {@code messages.yml}. The jar's copy backs every missing key, so an older
 * server file still shows the shipped wording. Reloadable via {@code /sacks reload}.
 */
public final class MessageManager {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

    private final JavaPlugin plugin;
    private FileConfiguration messages;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        if (!new File(plugin.getDataFolder(), "messages.yml").exists()) {
            plugin.saveResource("messages.yml", false);
        }
        reload();
    }

    public void reload() {
        this.messages = BundledYaml.load(plugin, "messages.yml");
    }

    /** The raw text for {@code key}, or "" when neither the server file nor the jar has it. */
    public String get(String key) {
        String raw = messages.getString(key);
        return raw == null ? "" : raw;
    }

    /** {@link #get} with {@code {name}} placeholders filled in one pass, so a value is never expanded again. */
    public String format(String key, Map<String, String> placeholders) {
        return fill(get(key), placeholders);
    }

    public List<String> lines(String key) {
        return messages.getStringList(key);
    }

    /** Send with the prefix. Blank text is skipped, so a message can be silenced by emptying it. */
    public void send(CommandSender target, String key) {
        String raw = get(key);
        if (!raw.isBlank()) {
            target.sendMessage(Text.of(get("prefix") + raw));
        }
    }

    /** Send with the prefix and {@code {name}} placeholders. */
    public void send(CommandSender target, String key, Map<String, String> placeholders) {
        String raw = format(key, placeholders);
        if (!raw.isBlank()) {
            target.sendMessage(Text.of(get("prefix") + raw));
        }
    }

    /** Send without the prefix (command output and lists), with {@code {name}} placeholders. */
    public void sendPlain(CommandSender target, String key, Map<String, String> placeholders) {
        String raw = format(key, placeholders);
        if (!raw.isBlank()) {
            target.sendMessage(Text.of(raw));
        }
    }

    static String fill(String raw, Map<String, String> placeholders) {
        if (placeholders.isEmpty()) {
            return raw;
        }
        Matcher m = PLACEHOLDER.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = placeholders.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }
}
