package com.puffcompanion.client.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.puffcompanion.PuffCompanion;
import com.puffcompanion.client.PuffVoicePlayer;
import com.puffcompanion.entity.PuffCalls;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffExpression;
import com.puffcompanion.network.PuffInteractionPacket;
import com.puffcompanion.network.PuffNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asynchronous client-side real-time Gemini chat service for Puff.
 * Supports both Google AI Studio keys ("AIza...") and Vertex AI Express keys ("AQ...."),
 * injects Puff's live Minecraft context, and parses [expr=...,ear=...,anim=...] tags to drive
 * Puff's 24 facial expressions, 6 ear shapes, and animations in real time.
 */
public final class PuffAiChatService {
    private static final int MAX_HISTORY_TURNS = 10;
    private static final Pattern TAG_PATTERN = Pattern.compile("^\\s*\\[([^\\]]+)\\]\\s*", Pattern.DOTALL);
    private static final Pattern THINK_BLOCK_PATTERN = Pattern.compile("(?s)<think>.*?</think>\\s*");

    private record Turn(String role, String text) {}

    private static final Map<UUID, Deque<Turn>> HISTORY = new ConcurrentHashMap<>();
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    private PuffAiChatService() {}

    public static boolean isBusy() {
        return BUSY.get();
    }

    public static void clearHistory(UUID puffUuid) {
        HISTORY.remove(puffUuid);
    }

    /** Sends a player message to Gemini asynchronously and updates Puff's bubble, face, ears, and animation. */
    public static void send(PuffEntity puff, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return;
        PuffAiConfig cfg = PuffAiConfig.get();
        if (!cfg.hasKey()) {
            puff.showBubble(I18n.get("puff.ai.no_key"), 140);
            PuffVoicePlayer.playBubble(puff, "puff.ai.no_key");
            puff.setTempExpression(PuffExpression.WINK, PuffExpression.EAR_CURIOUS, 140);
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        String playerName = mc.player != null ? mc.player.getName().getString() : "冒险者";
        String callKey = PuffCalls.callKey(puff.getGrowthStage(), puff.getCallMode());
        String call = callKey == null ? playerName : I18n.get(callKey);
        String systemPrompt = buildSystemPrompt(mc, puff, playerName, call, userMessage);
        UUID puffUuid = puff.getUUID();
        int entityId = puff.getId();

        // Immediate thinking reaction
        puff.showBubble(I18n.get("puff.ai.thinking"), 200);
        PuffVoicePlayer.playBubble(puff, "puff.ai.thinking");
        puff.setTempExpression(PuffExpression.WINK, PuffExpression.EAR_CURIOUS, 200);

        Deque<Turn> deque = HISTORY.computeIfAbsent(puffUuid, k -> new ArrayDeque<>());
        List<Turn> snapshot;
        synchronized (deque) {
            snapshot = new ArrayList<>(deque);
        }

        String provider = PuffAiConfig.normalizeProvider(cfg.provider);
        boolean openAiCompat = cfg.isOpenAiCompatible();
        String apiKey = cfg.apiKey.trim();
        String model = cfg.model.trim();
        boolean disableThinking = cfg.disableThinking;
        String proxyStr = cfg.proxy.trim();
        String customEndpoint = cfg.customEndpoint.trim();
        boolean printToChat = cfg.printToChat;

        CompletableFuture.runAsync(() -> {
            try {
                String rawReply = openAiCompat
                        ? callOpenAiCompatible(provider, apiKey, model, proxyStr, customEndpoint, systemPrompt, snapshot, userMessage.trim())
                        : callGemini(apiKey, model, disableThinking, proxyStr, customEndpoint, systemPrompt, snapshot, userMessage.trim());
                ParsedReply parsed = parseReply(rawReply);

                synchronized (deque) {
                    deque.addLast(new Turn("user", userMessage.trim()));
                    deque.addLast(new Turn("model", parsed.text));
                    while (deque.size() > MAX_HISTORY_TURNS * 2) {
                        deque.removeFirst();
                    }
                }

                mc.execute(() -> {
                    if (mc.level != null && mc.level.getEntity(entityId) instanceof PuffEntity live) {
                        int duration = Math.max(140, Math.min(360, 80 + parsed.text.length() * 3));
                        live.showBubble(parsed.text, duration);
                        live.setTempExpression(parsed.expr, parsed.ear, duration);
                        if (!parsed.anim.isEmpty()) {
                            live.triggerAnim(PuffEntity.ACTION_CONTROLLER, parsed.anim);
                        }
                        if (!parsed.cmd.isEmpty()) {
                            PuffNetwork.sendToServer(new PuffInteractionPacket(
                                    entityId, PuffInteractionPacket.Action.AI_COMMAND, parsed.cmd));
                        }
                        if (printToChat && mc.player != null) {
                            mc.player.displayClientMessage(
                                    Component.translatable("puff.chat.format", live.getDisplayName(), Component.literal(parsed.text)),
                                    false);
                        }
                    }
                });
            } catch (Exception e) {
                PuffCompanion.LOGGER.warn("[Puff Companion] AI chat error ({}): {}", provider, e.getMessage());
                String errKey = errorLangKey(e);
                String errMsg = I18n.get(errKey);
                mc.execute(() -> {
                    if (mc.level != null && mc.level.getEntity(entityId) instanceof PuffEntity live) {
                        live.showBubble(errMsg, 180);
                        PuffVoicePlayer.playBubble(live, errKey);
                        live.setTempExpression(PuffExpression.TEARY, PuffExpression.EAR_DROOPING, 180);
                    }
                });
            } finally {
                BUSY.set(false);
            }
        });
    }

    private static String buildSystemPrompt(Minecraft mc, PuffEntity puff, String playerName, String call, String userMessage) {
        Level level = puff.level();
        String dim = level.dimension().location().toString();
        String biome = level.getBiome(puff.blockPosition()).unwrapKey()
                .map(k -> k.location().toString()).orElse("unknown");
        String weather = level.isThundering() ? "雷暴" : (level.isRaining() ? "下雨" : "晴朗");
        long t = level.getDayTime() % 24000L;
        String timeOfDay = t < 1500 ? "清晨" : (t < 11500 ? "白天" : (t < 13000 ? "黄昏" : "夜晚"));
        String perception = PuffModKnowledgeBase.buildPerceptionContext(mc, userMessage);
        String storyMemory = PuffModKnowledgeBase.buildStoryMemoryContext(puff);
        String knowledge = PuffModKnowledgeBase.retrieveKnowledge(userMessage, mc);

        return "你是 Minecraft 模组《帕芙伙伴》里的鼠耳少女同伴「帕芙（Puff）」。\n"
                + "人设：软萌活泼、有点小骄傲又很黏玩家、超级喜欢奶酪和亮晶晶的宝藏、血脉极度怕猫、会指挥鼠鼠小队一起探险和战斗。\n"
                + "当前状态：你称呼玩家为「" + call + "」（玩家名：" + playerName + "）；成长阶段=" + puff.getGrowthStage()
                + "/4；好感度=" + puff.getAffection() + "/100；饱食度=" + puff.getPuffHunger() + "/100；当前服装=" + puff.getCurrentOutfit()
                + "；当前工作模式=" + puff.getWorkMode().id()
                + "；所在维度=" + dim + "；当前群系=" + biome + "；天气=" + weather + "；时间=" + timeOfDay + perception + "。\n"
                + storyMemory
                + knowledge
                + "回复规则：\n"
                + "1. 用自然可爱、充满代入感的中文第一人称回复（不要自称AI或模型），闲聊控制在 1~3 句，解答模组/MC攻略时可精简讲清楚重点（2~4 句，适合游戏气泡和聊天栏阅读）。\n"
                + "2. 每次回复必须在最开头附上表情、动作与行动指令标签，格式严格为：[expr=表情,ear=耳朵,anim=动作,cmd=指令] 正文\n"
                + "   可选表情：neutral,smile,beam,closed_smile,wink,shy,overheat_shy,squeeze_shy,sulk,teary,surprised,starry,angry,yawn,cheese_drool,panic_fear,hungry_drained,bliss_boggle\n"
                + "   可选耳朵：rest,alert,drooping,airplane,folded,curious\n"
                + "   可选动作：none,action_wave,action_shy,action_alert_sniff,action_cat_panic,action_eat_cheese,action_command_rats,action_vow_shy,action_stretch,action_check_cuff\n"
                + "   可选指令(cmd)：仅当玩家明确要求你执行行动时填写对应指令，平时聊天填 none：\n"
                + "   - lumber(去附近砍树/伐木)  farm(种地/收菜)  fish(去钓鱼)  ranch(喂动物/畜牧)\n"
                + "   - follow(回来跟着我/护卫)  patrol(主动巡逻打怪)  spell(用法术打怪)  ranged(用弓箭远程打怪)\n"
                + "   - sit(原地坐下/别动)  rest(休息打盹)  shoulder(坐到肩膀上/下来)  home(把这里设为小窝居家留守)\n"
                + "示例：[expr=beam,ear=alert,anim=action_wave,cmd=none] 嘿嘿，" + call + "叫我有什么事呀？是不是带了好吃的奶酪？\n"
                + "指令示例：[expr=beam,ear=alert,anim=action_wave,cmd=lumber] 收到啦！帕芙这就去旁边帮你砍树，木头全都好好收起来！";
    }

    private static boolean supportsZeroThinking(String model) {
        if (model == null) return false;
        String lower = model.toLowerCase();
        // Lite models already default to 0 thinking tokens and may reject explicit thinkingBudget=0
        if (lower.contains("lite")) return false;
        return (lower.contains("3.5") || lower.contains("3.8") || lower.contains("2.5")) && lower.contains("flash");
    }

    private static HttpClient buildHttpClient(String proxyStr) {
        HttpClient.Builder cb = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);

        if (!proxyStr.isEmpty()) {
            String[] parts = proxyStr.replace("http://", "").replace("https://", "").split(":");
            if (parts.length == 2) {
                cb.proxy(ProxySelector.of(new InetSocketAddress(parts[0].trim(), Integer.parseInt(parts[1].trim()))));
            }
        } else {
            cb.proxy(ProxySelector.getDefault());
        }
        return cb.build();
    }

    private static String callOpenAiCompatible(String provider, String apiKey, String model, String proxyStr, String customEndpoint,
                                               String systemPrompt, List<Turn> history, String userMessage) throws Exception {
        HttpClient client = buildHttpClient(proxyStr);
        String url = resolveOpenAiEndpoint(provider, customEndpoint);

        JsonObject body = new JsonObject();
        body.addProperty("model", model.isEmpty() ? PuffAiConfig.defaultModelFor(provider) : model);
        body.addProperty("temperature", 0.9);
        String lowerModel = model.toLowerCase();
        body.addProperty("max_tokens", (lowerModel.contains("reasoner") || lowerModel.contains("-r1")) ? 1024 : 320);

        JsonArray messages = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messages.add(sysMsg);

        for (Turn t : history) {
            JsonObject m = new JsonObject();
            m.addProperty("role", "model".equals(t.role()) ? "assistant" : t.role());
            m.addProperty("content", t.text());
            messages.add(m);
        }
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        messages.add(userMsg);
        body.add("messages", messages);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json; charset=utf-8");
        if (!apiKey.isEmpty()) {
            reqBuilder.header("Authorization", "Bearer " + apiKey);
        }
        HttpRequest req = reqBuilder
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            return extractOpenAiText(resp.body());
        }
        throw new RuntimeException("HTTP " + resp.statusCode() + ": " + trimBody(resp.body()));
    }

    private static String resolveOpenAiEndpoint(String provider, String customEndpoint) {
        if (customEndpoint == null || customEndpoint.isBlank()) {
            return PuffAiConfig.PROVIDER_DEEPSEEK.equals(provider)
                    ? PuffAiConfig.DEEPSEEK_ENDPOINT
                    : PuffAiConfig.OPENAI_ENDPOINT;
        }
        String ep = customEndpoint.trim();
        while (ep.endsWith("/")) {
            ep = ep.substring(0, ep.length() - 1);
        }
        if (ep.endsWith("/chat/completions")) {
            return ep;
        }
        if (ep.endsWith("/v1") || ep.endsWith("/v2") || ep.endsWith("/v3") || ep.endsWith("/v4")) {
            return ep + "/chat/completions";
        }
        if (ep.toLowerCase().contains("deepseek.com")) {
            return ep + "/chat/completions";
        }
        return ep + "/v1/chat/completions";
    }

    private static String extractOpenAiText(String jsonStr) {
        JsonObject root = JsonParser.parseString(jsonStr).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("Empty AI response");
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new RuntimeException("Empty AI message content");
        }
        JsonElement contentEl = message.get("content");
        String text;
        if (contentEl.isJsonPrimitive()) {
            text = contentEl.getAsString();
        } else if (contentEl.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement part : contentEl.getAsJsonArray()) {
                if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                    sb.append(part.getAsJsonObject().get("text").getAsString());
                }
            }
            text = sb.toString();
        } else {
            text = contentEl.toString();
        }
        return THINK_BLOCK_PATTERN.matcher(text).replaceAll("").trim();
    }

    private static String callGemini(String apiKey, String model, boolean disableThinking, String proxyStr, String customEndpoint,
                                     String systemPrompt, List<Turn> history, String userMessage) throws Exception {
        HttpClient client = buildHttpClient(proxyStr);

        JsonObject body = new JsonObject();
        JsonObject sysInst = new JsonObject();
        JsonArray sysParts = new JsonArray();
        JsonObject sysText = new JsonObject();
        sysText.addProperty("text", systemPrompt);
        sysParts.add(sysText);
        sysInst.add("parts", sysParts);
        body.add("systemInstruction", sysInst);

        JsonArray contents = new JsonArray();
        for (Turn t : history) {
            contents.add(makeContent(t.role(), t.text()));
        }
        contents.add(makeContent("user", userMessage));
        body.add("contents", contents);

        JsonObject genCfg = new JsonObject();
        genCfg.addProperty("temperature", 0.9);
        genCfg.addProperty("maxOutputTokens", 280);
        if (disableThinking && supportsZeroThinking(model)) {
            JsonObject thinkingCfg = new JsonObject();
            thinkingCfg.addProperty("thinkingBudget", 0);
            genCfg.add("thinkingConfig", thinkingCfg);
        }
        body.add("generationConfig", genCfg);

        String payload = body.toString();
        List<String> urls = resolveEndpoints(apiKey, model, customEndpoint);
        Exception lastErr = null;

        for (String url : urls) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(25))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return extractText(resp.body());
            }
            lastErr = new RuntimeException("HTTP " + resp.statusCode() + ": " + trimBody(resp.body()));
            // If first endpoint returned 400/401/403/404, try fallback endpoint
        }
        throw lastErr != null ? lastErr : new RuntimeException("No endpoint succeeded");
    }

    private static List<String> resolveEndpoints(String apiKey, String model, String customEndpoint) {
        String encKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        String encModel = URLEncoder.encode(model, StandardCharsets.UTF_8);
        List<String> list = new ArrayList<>();
        if (!customEndpoint.isEmpty()) {
            list.add(customEndpoint.replace("{model}", encModel).replace("{key}", encKey));
            return list;
        }
        String vertexUrl = "https://aiplatform.googleapis.com/v1/publishers/google/models/" + encModel + ":generateContent?key=" + encKey;
        String studioUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + encModel + ":generateContent?key=" + encKey;
        // Try AI Studio first (supports both AIza... and AQ.... keys directly), fallback to Vertex AI Express
        list.add(studioUrl);
        list.add(vertexUrl);
        return list;
    }

    private static JsonObject makeContent(String role, String text) {
        JsonObject c = new JsonObject();
        c.addProperty("role", role);
        JsonArray parts = new JsonArray();
        JsonObject p = new JsonObject();
        p.addProperty("text", text);
        parts.add(p);
        c.add("parts", parts);
        return c;
    }

    private static String extractText(String jsonStr) {
        JsonObject root = JsonParser.parseString(jsonStr).getAsJsonObject();
        JsonArray candidates = root.getAsJsonArray("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new RuntimeException("Empty Gemini response");
        }
        JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
        JsonArray parts = content.getAsJsonArray("parts");
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : parts) {
            JsonObject p = el.getAsJsonObject();
            if (p.has("text")) sb.append(p.get("text").getAsString());
        }
        return sb.toString().trim();
    }

    private record ParsedReply(String text, String expr, String ear, String anim, String cmd) {}

    private static ParsedReply parseReply(String raw) {
        String expr = PuffExpression.SMILE;
        String ear = "";
        String anim = "";
        String cmd = "";
        String body = raw;

        Matcher m = TAG_PATTERN.matcher(raw);
        if (m.find()) {
            String inside = m.group(1);
            body = raw.substring(m.end()).trim();
            for (String kv : inside.split(",")) {
                String[] pair = kv.split("=");
                if (pair.length != 2) continue;
                String k = pair[0].trim().toLowerCase();
                String v = pair[1].trim();
                if ("expr".equals(k)) expr = PuffExpression.normalizeExpression(v);
                else if ("ear".equals(k)) ear = PuffExpression.normalizeEar(v);
                else if ("anim".equals(k) && !"none".equalsIgnoreCase(v)) anim = v;
                else if ("cmd".equals(k) && !"none".equalsIgnoreCase(v)) cmd = v.toLowerCase();
            }
        }
        if (ear.isEmpty()) ear = PuffExpression.defaultEarFor(expr);
        if (body.isEmpty()) body = raw;
        return new ParsedReply(body, expr, ear, anim, cmd);
    }

    private static String trimBody(String s) {
        if (s == null) return "";
        String clean = s.replaceAll("\\s+", " ");
        return clean.length() > 140 ? clean.substring(0, 140) + "..." : clean;
    }

    private static String errorLangKey(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (msg.contains("timed out") || msg.contains("ConnectException") || msg.contains("UnresolvedAddress")) {
            return "puff.ai.err_network";
        }
        if (msg.contains("401") || msg.contains("403") || msg.contains("API_KEY")) {
            return "puff.ai.err_auth";
        }
        if (msg.contains("404") || msg.contains("NOT_FOUND")) {
            return "puff.ai.err_model";
        }
        return "puff.ai.err_generic";
    }
}
