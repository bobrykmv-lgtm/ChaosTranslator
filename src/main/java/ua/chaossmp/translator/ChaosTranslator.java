package ua.chaossmp.translator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLocaleChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class ChaosTranslator extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final PlainTextComponentSerializer plainSerializer = PlainTextComponentSerializer.plainText();

    private HttpClient httpClient;
    private File dataFile;
    private YamlConfiguration playerData;

    // UUID -> "auto", "off", или ISO-код языка (например "uk", "ru", "en", "zh-CN")
    private final Map<UUID, String> userModes = new ConcurrentHashMap<>();
    // Ключ "targetLang:message" -> CachedTranslation
    private final Map<String, CachedTranslation> translationCache = new ConcurrentHashMap<>();

    private static final Pattern SKIP_PATTERN = Pattern.compile("^[0-9\\s\\p{Punct}]+$|^(https?://\\S+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALID_LANG_PATTERN = Pattern.compile("^[a-zA-Z]{2,3}(-[a-zA-Z]{2,4})?$");

    private static final List<String> SUGGESTED_LANGS = List.of(
            "auto", "off", "uk", "ru", "en", "pl", "de", "fr", "es", "it",
            "pt", "tr", "cs", "ro", "bg", "ja", "ko", "zh-CN", "zh-TW", "ar", "hi"
    );

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();

        loadPlayerData();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("lang") != null) {
            getCommand("lang").setExecutor(this);
            getCommand("lang").setTabCompleter(this);
        }

        // Периодическая очистка устаревшего кэша каждые 5 минут
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::cleanExpiredCache, 6000L, 6000L);
        getLogger().info("ChaosTranslator enabled! Supporting all Minecraft client locales.");
    }

    @Override
    public void onDisable() {
        savePlayerDataSync();
        translationCache.clear();
    }

    private void loadPlayerData() {
        dataFile = new File(getDataFolder(), "players.yml");
        if (!dataFile.exists()) {
            try {
                getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Не удалось создать players.yml: " + e.getMessage());
            }
        }
        playerData = YamlConfiguration.loadConfiguration(dataFile);
        userModes.clear();
        for (String key : playerData.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String mode = playerData.getString(key, "auto");
                userModes.put(uuid, mode);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void savePlayerDataAsync() {
        Map<UUID, String> snapshot = new HashMap<>(userModes);
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<UUID, String> entry : snapshot.entrySet()) {
                yaml.set(entry.getKey().toString(), entry.getValue());
            }
            try {
                yaml.save(dataFile);
            } catch (IOException e) {
                getLogger().warning("Ошибка сохранения players.yml: " + e.getMessage());
            }
        });
    }

    private void savePlayerDataSync() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, String> entry : userModes.entrySet()) {
            yaml.set(entry.getKey().toString(), entry.getValue());
        }
        try {
            yaml.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Ошибка сохранения players.yml: " + e.getMessage());
        }
    }

    private String resolveTargetLang(Player player) {
        String mode = userModes.getOrDefault(player.getUniqueId(), "auto");
        if ("off".equalsIgnoreCase(mode)) {
            return null;
        }
        if ("auto".equalsIgnoreCase(mode)) {
            return mapMinecraftLocale(player.locale());
        }
        return mode;
    }

    private String mapMinecraftLocale(Locale locale) {
        String lang = locale.getLanguage().toLowerCase(Locale.ROOT);
        String country = locale.getCountry().toUpperCase(Locale.ROOT);

        // Специфические локали Minecraft, где важен регион
        if ("zh".equals(lang)) {
            if ("TW".equals(country) || "HK".equals(country)) {
                return "zh-TW";
            }
            return "zh-CN";
        }
        // Коды Minecraft, отличающиеся от стандартного Google Translate ISO
        return switch (lang) {
            case "he", "iw" -> "iw";
            case "jv" -> "jw";
            case "fil", "tl" -> "tl";
            case "no", "nb", "nn" -> "no";
            default -> lang.isEmpty() ? "en" : lang;
        };
    }

    @EventHandler
    public void onLocaleChange(PlayerLocaleChangeEvent event) {
        Player player = event.getPlayer();
        String mode = userModes.getOrDefault(player.getUniqueId(), "auto");
        if ("auto".equalsIgnoreCase(mode)) {
            String newLang = mapMinecraftLocale(event.locale());
            sendPrefixed(player, getConfig().getString("messages.locale-changed", ""),
                    Placeholder.unparsed("lang", newLang.toUpperCase(Locale.ROOT)));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        String plainMessage = plainSerializer.serialize(event.message());

        int minLength = getConfig().getInt("min-message-length", 3);
        if (plainMessage.length() < minLength || SKIP_PATTERN.matcher(plainMessage).matches()) {
            return;
        }

        // Группируем получателей по целевому языку
        Map<String, List<Player>> targetsByLang = new HashMap<>();
        List<Player> rawRecipients = new ArrayList<>();

        for (Audience audience : event.viewers()) {
            if (!(audience instanceof Player viewer)) {
                continue;
            }
            if (viewer.getUniqueId().equals(sender.getUniqueId())) {
                rawRecipients.add(viewer);
                continue;
            }
            String targetLang = resolveTargetLang(viewer);
            if (targetLang == null) {
                rawRecipients.add(viewer);
            } else {
                targetsByLang.computeIfAbsent(targetLang, k -> new ArrayList<>()).add(viewer);
            }
        }

        if (targetsByLang.isEmpty()) {
            return; // Всем нужен только оригинал, оставляем стандартный рендер
        }

        // Оставляем стандартную доставку только тем, кому не нужен перевод (включая отправителя)
        event.viewers().removeIf(a -> a instanceof Player p && !rawRecipients.contains(p));

        String senderName = sender.getName();
        String chatFormat = getConfig().getString("chat-format",
                "<yellow>▪ <white><player> <gold>» <gray><message> <dark_gray>[<source>→<target>]");
        String hoverFormat = getConfig().getString("hover-format",
                "<gold>Оригинал <dark_gray>(<source>)<gold>:\n<white><original>");

        for (Map.Entry<String, List<Player>> entry : targetsByLang.entrySet()) {
            String targetLang = entry.getKey();
            List<Player> group = entry.getValue();

            translateAsync(plainMessage, targetLang).thenAccept(result -> {
                Component finalComponent;
                String normalizedSource = normalizeLangCode(result.detectedSource());
                String normalizedTarget = normalizeLangCode(targetLang);

                if (normalizedSource.equalsIgnoreCase(normalizedTarget)
                        || result.translatedText().equalsIgnoreCase(plainMessage)) {
                    // Язык совпал — отправляем без пометки перевода
                    finalComponent = mm.deserialize(
                            "<yellow>▪ <white><player> <gold>» <gray><message>",
                            Placeholder.unparsed("player", senderName),
                            Placeholder.unparsed("message", plainMessage)
                    );
                } else {
                    Component hoverText = mm.deserialize(
                            hoverFormat,
                            Placeholder.unparsed("source", normalizedSource.toUpperCase(Locale.ROOT)),
                            Placeholder.unparsed("original", plainMessage)
                    );

                    finalComponent = mm.deserialize(
                            chatFormat,
                            Placeholder.unparsed("player", senderName),
                            Placeholder.unparsed("message", result.translatedText()),
                            Placeholder.unparsed("source", normalizedSource.toUpperCase(Locale.ROOT)),
                            Placeholder.unparsed("target", normalizedTarget.toUpperCase(Locale.ROOT))
                    ).hoverEvent(HoverEvent.showText(hoverText));
                }

                for (Player viewer : group) {
                    if (viewer.isOnline()) {
                        viewer.sendMessage(finalComponent);
                    }
                }
            });
        }
    }

    private String normalizeLangCode(String code) {
        if (code == null || code.isEmpty()) return "AUTO";
        int dash = code.indexOf('-');
        return dash > 0 ? code.substring(0, dash) : code;
    }

    private CompletableFuture<TranslationResult> translateAsync(String text, String targetLang) {
        String cacheKey = targetLang.toLowerCase(Locale.ROOT) + ":" + text;
        long ttlMillis = getConfig().getLong("cache-ttl-seconds", 600) * 1000L;

        CachedTranslation cached = translationCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < ttlMillis) {
            return CompletableFuture.completedFuture(cached.result);
        }

        try {
            String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8);
            String encodedTarget = URLEncoder.encode(targetLang, StandardCharsets.UTF_8);
            String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl="
                    + encodedTarget + "&dt=t&q=" + encodedText;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> {
                        if (response.statusCode() != 200) {
                            return new TranslationResult(text, targetLang);
                        }
                        TranslationResult parsed = parseGoogleResponse(response.body(), text, targetLang);
                        translationCache.put(cacheKey, new CachedTranslation(parsed, System.currentTimeMillis()));
                        return parsed;
                    })
                    .exceptionally(ex -> new TranslationResult(text, targetLang));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(new TranslationResult(text, targetLang));
        }
    }

    private TranslationResult parseGoogleResponse(String json, String fallbackText, String fallbackLang) {
        try {
            JsonArray root = JsonParser.parseString(json).getAsJsonArray();
            JsonArray sentences = root.get(0).getAsJsonArray();
            StringBuilder sb = new StringBuilder();
            for (JsonElement element : sentences) {
                if (element.isJsonArray()) {
                    JsonArray part = element.getAsJsonArray();
                    if (!part.get(0).isJsonNull()) {
                        sb.append(part.get(0).getAsString());
                    }
                }
            }
            String detectedLang = fallbackLang;
            if (root.size() > 2 && !root.get(2).isJsonNull()) {
                detectedLang = root.get(2).getAsString();
            }
            String translated = sb.toString().trim();
            return new TranslationResult(translated.isEmpty() ? fallbackText : translated, detectedLang);
        } catch (Exception e) {
            return new TranslationResult(fallbackText, fallbackLang);
        }
    }

    private void cleanExpiredCache() {
        long ttlMillis = getConfig().getLong("cache-ttl-seconds", 600) * 1000L;
        long now = System.currentTimeMillis();
        translationCache.entrySet().removeIf(e -> (now - e.getValue().timestamp) > ttlMillis);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("chaostranslator.admin")) {
                sendPrefixed(sender, getConfig().getString("messages.no-permission", ""));
                return true;
            }
            reloadConfig();
            translationCache.clear();
            sendPrefixed(sender, getConfig().getString("messages.reloaded", ""));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sendPrefixed(sender, getConfig().getString("messages.player-only", ""));
            return true;
        }

        if (args.length != 1) {
            sendPrefixed(player, getConfig().getString("messages.usage", ""));
            return true;
        }

        String input = args[0].trim();

        if (input.equalsIgnoreCase("auto")) {
            userModes.put(player.getUniqueId(), "auto");
            savePlayerDataAsync();
            String currentLocale = mapMinecraftLocale(player.locale()).toUpperCase(Locale.ROOT);
            sendPrefixed(player, getConfig().getString("messages.set-auto", ""),
                    Placeholder.unparsed("lang", currentLocale));
            return true;
        }

        if (input.equalsIgnoreCase("off")) {
            userModes.put(player.getUniqueId(), "off");
            savePlayerDataAsync();
            sendPrefixed(player, getConfig().getString("messages.set-off", ""));
            return true;
        }

        if (!VALID_LANG_PATTERN.matcher(input).matches()) {
            sendPrefixed(player, getConfig().getString("messages.invalid-lang", ""));
            return true;
        }

        String formattedLang = input.contains("-")
                ? input.split("-")[0].toLowerCase(Locale.ROOT) + "-" + input.split("-")[1].toUpperCase(Locale.ROOT)
                : input.toLowerCase(Locale.ROOT);

        userModes.put(player.getUniqueId(), formattedLang);
        savePlayerDataAsync();
        sendPrefixed(player, getConfig().getString("messages.set-custom", ""),
                Placeholder.unparsed("lang", formattedLang.toUpperCase(Locale.ROOT)));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> completions = new ArrayList<>();
            for (String lang : SUGGESTED_LANGS) {
                if (lang.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    completions.add(lang);
                }
            }
            if (sender.hasPermission("chaostranslator.admin") && "reload".startsWith(prefix)) {
                completions.add("reload");
            }
            return completions;
        }
        return Collections.emptyList();
    }

    private void sendPrefixed(Audience audience, String rawMessage, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        if (rawMessage == null || rawMessage.isEmpty()) return;
        String prefix = getConfig().getString("messages.prefix", "<bold><red>CHAOS<gold>SMP</bold> <dark_gray>| ");
        audience.sendMessage(mm.deserialize(prefix + rawMessage, resolvers));
    }

    private record TranslationResult(String translatedText, String detectedSource) {}
    private record CachedTranslation(TranslationResult result, long timestamp) {}
}
