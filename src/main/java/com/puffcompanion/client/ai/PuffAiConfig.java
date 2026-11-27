package com.puffcompanion.client.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.puffcompanion.PuffCompanion;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Local client-side configuration for Puff's real-time AI chat (Gemini native & OpenAI-compatible APIs).
 * Stored in {@code config/puffcompanion-ai.json} so API keys are never bundled into the mod jar.
 */
public final class PuffAiConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "puffcompanion-ai.json";

    public static final String PROVIDER_GEMINI = "gemini";
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_OPENAI = "openai";
    public static final List<String> PROVIDERS = List.of(
            PROVIDER_GEMINI,
            PROVIDER_DEEPSEEK,
            PROVIDER_OPENAI
    );

    public static final String DEEPSEEK_ENDPOINT = "https://api.deepseek.com/chat/completions";
    public static final String OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions";

    public static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
    public static final List<String> PRESET_MODELS = List.of(
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-3.1-flash-lite",
            "gemini-flash-lite-latest",
            "gemini-flash-latest"
    );
    public static final List<String> DEEPSEEK_PRESET_MODELS = List.of(
            "deepseek-chat",
            "deepseek-reasoner"
    );
    public static final List<String> OPENAI_PRESET_MODELS = List.of(
            "gpt-4o-mini",
            "deepseek-chat",
            "qwen-plus",
            "gpt-4.1-mini"
    );

    public String provider = PROVIDER_GEMINI;
    public String apiKey = "";
    public String model = DEFAULT_MODEL;
    /** Disable hidden thinking tokens on Gemini 2.5 Flash models to cut cost & latency. */
    public boolean disableThinking = true;
    /** Optional local proxy in "host:port" format (e.g. "127.0.0.1:7890"), or blank for system proxy. */
    public String proxy = "";
    /** Optional custom endpoint / base URL (OpenAI-compatible base URL or Gemini URL with {model}/{key}). */
    public String customEndpoint = "";
    public boolean printToChat = true;
    /** Puff voiceover volume (0.0 = muted, 1.0 = 100%). */
    public float voiceVolume = 1.0F;

    private static PuffAiConfig instance;

    public static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    public static synchronized PuffAiConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static synchronized PuffAiConfig reload() {
        instance = load();
        return instance;
    }

    private static PuffAiConfig load() {
        Path path = configPath();
        if (Files.exists(path)) {
            try {
                String raw = Files.readString(path, StandardCharsets.UTF_8);
                PuffAiConfig cfg = GSON.fromJson(raw, PuffAiConfig.class);
                if (cfg != null) {
                    cfg.provider = normalizeProvider(cfg.provider);
                    if (cfg.apiKey == null) cfg.apiKey = "";
                    if (cfg.model == null || cfg.model.isBlank()) cfg.model = defaultModelFor(cfg.provider);
                    if (cfg.proxy == null) cfg.proxy = "";
                    if (cfg.customEndpoint == null) cfg.customEndpoint = "";
                    if (Float.isNaN(cfg.voiceVolume)) cfg.voiceVolume = 1.0F;
                    cfg.voiceVolume = Math.max(0.0F, Math.min(1.0F, cfg.voiceVolume));
                    return cfg;
                }
            } catch (Exception e) {
                PuffCompanion.LOGGER.warn("[Puff Companion] Failed to read {}: {}", path, e.getMessage());
            }
        }
        PuffAiConfig fresh = new PuffAiConfig();
        fresh.save();
        return fresh;
    }

    public synchronized void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this) + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            PuffCompanion.LOGGER.warn("[Puff Companion] Failed to save {}: {}", path, e.getMessage());
        }
    }

    public boolean hasKey() {
        if (this.apiKey != null && !this.apiKey.isBlank()) return true;
        if (isOpenAiCompatible() && this.customEndpoint != null) {
            String ep = this.customEndpoint.trim().toLowerCase();
            if (ep.contains("localhost") || ep.contains("127.0.0.1")) {
                return true;
            }
        }
        return false;
    }

    public boolean isOpenAiCompatible() {
        String p = normalizeProvider(this.provider);
        if (PROVIDER_DEEPSEEK.equals(p) || PROVIDER_OPENAI.equals(p)) {
            return true;
        }
        if (this.customEndpoint != null && !this.customEndpoint.isBlank()) {
            String ep = this.customEndpoint.trim().toLowerCase();
            if (ep.contains("/chat/completions")
                    || ep.contains("deepseek.com")
                    || ep.contains("openai.com")
                    || ep.contains("siliconflow")
                    || ep.contains("dashscope")
                    || ep.contains("openrouter.ai")) {
                return true;
            }
        }
        return false;
    }

    public static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) return PROVIDER_GEMINI;
        String p = provider.trim().toLowerCase();
        if (PROVIDER_DEEPSEEK.equals(p)) return PROVIDER_DEEPSEEK;
        if (PROVIDER_OPENAI.equals(p) || "openai_compatible".equals(p) || "custom".equals(p)) return PROVIDER_OPENAI;
        return PROVIDER_GEMINI;
    }

    public static String nextProvider(String current) {
        String p = normalizeProvider(current);
        int idx = PROVIDERS.indexOf(p);
        return PROVIDERS.get((idx + 1) % PROVIDERS.size());
    }

    public static String providerLabel(String provider) {
        return switch (normalizeProvider(provider)) {
            case PROVIDER_DEEPSEEK -> "DeepSeek";
            case PROVIDER_OPENAI -> "OpenAI兼容";
            default -> "Gemini";
        };
    }

    public static String defaultModelFor(String provider) {
        return switch (normalizeProvider(provider)) {
            case PROVIDER_DEEPSEEK -> "deepseek-chat";
            case PROVIDER_OPENAI -> "gpt-4o-mini";
            default -> DEFAULT_MODEL;
        };
    }

    public static String defaultEndpointFor(String provider) {
        return switch (normalizeProvider(provider)) {
            case PROVIDER_DEEPSEEK -> DEEPSEEK_ENDPOINT;
            case PROVIDER_OPENAI -> OPENAI_ENDPOINT;
            default -> "";
        };
    }

    public static List<String> presetModelsFor(String provider) {
        return switch (normalizeProvider(provider)) {
            case PROVIDER_DEEPSEEK -> DEEPSEEK_PRESET_MODELS;
            case PROVIDER_OPENAI -> OPENAI_PRESET_MODELS;
            default -> PRESET_MODELS;
        };
    }

    public static String nextPresetModel(String current) {
        return nextPresetModel(PROVIDER_GEMINI, current);
    }

    public static String nextPresetModel(String provider, String current) {
        List<String> presets = presetModelsFor(provider);
        int idx = presets.indexOf(current == null ? "" : current.trim());
        return presets.get((idx + 1) % presets.size());
    }

    public static String shortModelLabel(String model) {
        if (model == null || model.isBlank()) return "3.5-Lite";
        String m = model.trim();
        return switch (m) {
            case "gemini-3.5-flash-lite" -> "3.5-Lite";
            case "gemini-3.5-flash" -> "3.5-Flash";
            case "gemini-3.1-flash-lite" -> "3.1-Lite";
            case "gemini-flash-lite-latest" -> "Lite最新";
            case "gemini-flash-latest" -> "Flash最新";
            case "deepseek-chat" -> "DS-Chat";
            case "deepseek-reasoner" -> "DS-R1";
            case "gpt-4o-mini" -> "4o-mini";
            case "gpt-4.1-mini" -> "4.1-mini";
            case "qwen-plus" -> "Qwen+";
            default -> {
                String stripped = m.startsWith("gemini-") ? m.substring("gemini-".length()) : m;
                yield stripped.length() > 11 ? stripped.substring(0, 10) + "…" : stripped;
            }
        };
    }
}
