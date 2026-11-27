package com.puffcompanion.client.ai;

import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffStories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight local on-demand knowledge base (client-side mini RAG) for all mods in the test client.
 * Adds 0 extra tokens during normal small talk; only injects up to 3 relevant concise cards
 * when the player asks about a mod topic, holds a mod item, or looks at a mod block/entity.
 */
public final class PuffModKnowledgeBase {

    private static final int MAX_CARDS = 3;

    private record KnowledgeCard(String modId, String title, String[] keywords, String content) {}

    private static final Map<String, String> TRACKED_MOD_NAMES = new LinkedHashMap<>();
    static {
        TRACKED_MOD_NAMES.put("puffcompanion", "帕芙伙伴");
        TRACKED_MOD_NAMES.put("rats", "老鼠(Rats)");
        TRACKED_MOD_NAMES.put("create", "机械动力(Create)");
        TRACKED_MOD_NAMES.put("goety", "诡厄巫法(Goety)");
        TRACKED_MOD_NAMES.put("irons_spellbooks", "铁魔法(Iron's Spellbooks)");
        TRACKED_MOD_NAMES.put("refinedstorage", "精致存储(RS)");
        TRACKED_MOD_NAMES.put("attributeslib", "神化属性(ApothicAttributes)");
        TRACKED_MOD_NAMES.put("enigmaticlegacy", "神秘遗物(Enigmatic Legacy)");
        TRACKED_MOD_NAMES.put("celestial_artifacts", "星界遗物(Celestial Artifacts)");
        TRACKED_MOD_NAMES.put("moonstone", "月石饰品(Moonstone)");
        TRACKED_MOD_NAMES.put("mine_fargo", "法狗魂石(Mine Fargo)");
        TRACKED_MOD_NAMES.put("terracurio", "泰拉饰品(TerraCurio)");
        TRACKED_MOD_NAMES.put("curios", "饰品栏(Curios)");
        TRACKED_MOD_NAMES.put("lootr", "战利品箱(Lootr)");
        TRACKED_MOD_NAMES.put("jei", "JEI配方查询");
        TRACKED_MOD_NAMES.put("jade", "玉(Jade信息显示)");
        TRACKED_MOD_NAMES.put("patchouli", "帕秋莉手册");
        TRACKED_MOD_NAMES.put("ftbessentials", "FTB传送指令");
        TRACKED_MOD_NAMES.put("ftbteams", "FTB组队");
    }

    private static final List<KnowledgeCard> CARDS = List.of(
            // ---- Puff Companion ----
            new KnowledgeCard(
                    "puffcompanion", "帕芙伙伴玩法",
                    new String[]{"帕芙", "好感", "成长", "阶段", "换装", "称呼", "饱食", "剧情", "饰品栏", "puff"},
                    "帕芙有4个成长阶段，通过喂奶酪、互动、冒险和触发剧情提升好感度与成长；右键帕芙可打开界面切换称呼、换装、自由聊天与剧情；帕芙也支持装备饰品并继承战斗属性，还能指挥驯服的老鼠小队一起战斗。"
            ),

            // ---- Rats Reboot ----
            new KnowledgeCard(
                    "rats", "老鼠模组·奶酪与驯服",
                    new String[]{"老鼠", "鼠鼠", "奶酪", "驯服", "炼药锅", "鼠笼", "rats", "cheese", "rat"},
                    "制作奶酪：把牛奶桶倒进炼药锅，等待凝固即可取出奶酪块（分解得奶酪）。驯服野生老鼠：把奶酪扔在地上让它吃，吃够几块冒爱心即驯服；右键可下达跟随/原地/巡逻/搬运指令，也可用鼠笼收起携带。"
            ),
            new KnowledgeCard(
                    "rats", "老鼠模组·鼠笛鼠杖与升级",
                    new String[]{"鼠笛", "鼠杖", "奶酪杖", "升级", "芯片", "厨师", "采矿", "伐木", "收集", "搬运", "合成", "飞龙", "以太", "鼠神", "upgrade", "flute", "staff"},
                    "奶酪杖可指定老鼠的箱子存取点与工作区；鼠笛可群体切换附近老鼠指令。右键打开驯服老鼠界面可放入升级芯片（如厨师/采矿/伐木/收集/合成/护甲/战士/飞龙/以太/鼠神等），用合成升级还能装多张芯片。"
            ),
            new KnowledgeCard(
                    "rats", "老鼠模组·鼠兰蒂斯与Boss",
                    new String[]{"鼠兰蒂斯", "传送门", "代币", "海盗鼠", "飞艇", "新鼠魔像", "鼠人", "ratlantis", "token", "golem", "baron"},
                    "前往鼠兰蒂斯：收集鼠兰蒂斯代币碎片合出代币，手持代币右键奶酪块即可生成传送门。维度内有海盗鼠、飞艇男爵、新鼠魔像、荷兰人幽灵船等强敌与专属升级材料。"
            ),
            new KnowledgeCard(
                    "rats", "老鼠模组·黑死病与瘟疫医生",
                    new String[]{"黑死病", "瘟疫", "瘟疫医生", "疫病", "药草", "plague", "black death", "doctor"},
                    "被染疫老鼠攻击可能感染黑死病，可用草药或治疗药水缓解；世界会刷新瘟疫医生，可交易鼠类物资；若瘟疫医生被雷劈会变成强力Boss「黑死病」。"
            ),

            // ---- Create ----
            new KnowledgeCard(
                    "create", "机械动力·基础动力与思索",
                    new String[]{"机械动力", "应力", "转速", "水车", "风车", "蒸汽", "齿轮", "传动", "思索", "create", "ponder", "su", "rpm"},
                    "机械动力核心是应力(SU)和转速(RPM)：早期用水车/大型水车与风车轴承发电，后期用烈焰人燃烧室+流体储罐+蒸汽引擎供能；大小齿轮咬合可2倍变速。对着任意机械动力方块按住 W 可打开「思索(Ponder)」看动态3D教学！"
            ),
            new KnowledgeCard(
                    "create", "机械动力·加工与自动化",
                    new String[]{"安山合金", "黄铜", "压片", "搅拌", "工作盆", "鼓风机", "粉碎轮", "机械手", "序列组装", "列车", "皮带", "漏斗", "brass", "andesite", "deployer"},
                    "科技线：安山岩+铁粒/锌粒合成安山合金→安山机壳→动力压片机(压板)+动力搅拌器/工作盆(合黄铜等)→烈焰人燃烧室→黄铜机壳→动力合成器/粉碎轮/机械手序列组装。鼓风机吹水/火/岩浆/灵魂火可分别洗涤、烟熏、熔炼、缠魂。"
            ),

            // ---- Goety ----
            new KnowledgeCard(
                    "goety", "诡厄巫法·灵魂能量与聚晶",
                    new String[]{"诡厄", "巫法", "灵魂", "图腾", "灵魂方舟", "聚晶", "法杖", "黑书", "goety", "soul", "focus", "totem", "wand"},
                    "诡厄巫法核心是灵魂能量：携带灵魂图腾击杀生物可吸收灵魂，也能在灵魂方舟储存；将聚晶装入黑暗/死灵/亡灵/风暴/地狱等法杖即可施法（按聚晶切换键更换法术），入门强烈推荐合成《黑书》查看全配方与仪式结构。"
            ),
            new KnowledgeCard(
                    "goety", "诡厄巫法·祭坛仪式与巫妖",
                    new String[]{"暗黑祭坛", "祭坛", "仪式", "基座", "巫妖", "仆从", "召唤", "使徒", "维齐尔", "红石巨兽", "ritual", "altar", "lich", "apostle"},
                    "暗黑祭坛需连结灵魂方舟或放在诅咒笼上供能，周围摆放基座并按仪式类型搭建环境方块（如锻造/魔法/死灵/动画/下界/风暴/安息日等）后右键激活。高阶可通过安息日仪式转化为巫妖，并挑战使徒、维齐尔、红石巨兽等Boss。"
            ),

            // ---- Iron's Spellbooks ----
            new KnowledgeCard(
                    "irons_spellbooks", "铁魔法·法术书与铭文台",
                    new String[]{"铁魔法", "法术", "法术书", "卷轴", "铭文台", "魔力", "法力", "irons", "spell", "spellbook", "scroll", "inscription"},
                    "铁魔法有火/冰/雷/神圣/末影/鲜血/唤魔/自然/诡秘9大学派。在铭文台(Inscription Table)里把法术卷轴装进法术书，装备法术书在饰品栏或手里，按法术轮盘键切法术、施法键释放；法术书等级决定槽位和最大魔力。"
            ),
            new KnowledgeCard(
                    "irons_spellbooks", "铁魔法·卷轴锻造与奥术铁砧",
                    new String[]{"卷轴锻造台", "奥术铁砧", "墨水", "奥术精粹", "灌注", "法袍", "炼金大锅", "forge", "arcane", "ink", "essence"},
                    "卷轴锻造台用「墨水+纸+学派催化物」自制指定法术卷轴；奥术铁砧可将两张同级卷轴合升一级，或把卷轴灌注到武器/护甲上；奥术精粹和不同品质墨水可在炼金大锅里拆解多余卷轴回收或升级。"
            ),

            // ---- Refined Storage ----
            new KnowledgeCard(
                    "refinedstorage", "精致存储·基础网络",
                    new String[]{"精致存储", "rs", "磁盘", "驱动器", "控制器", "合成终端", "终端", "线缆", "无线", "refinedstorage", "grid", "disk", "controller"},
                    "精致存储基础四件套：控制器(供能，接FE电)+磁盘驱动器(放入1k/4k/16k/64k存储磁盘)+合成终端(随时存取与合成)+线缆连接。想接已有大箱子用「外部存储」，远程打开需无线发射器+绑定网络发射卡的无线终端。"
            ),
            new KnowledgeCard(
                    "refinedstorage", "精致存储·自动合成与物流",
                    new String[]{"自动合成", "样板", "样板终端", "合成器", "输入总线", "输出总线", "升级卡", "crafter", "pattern", "importer", "exporter"},
                    "自动合成流程：在样板终端录制配方/处理样板→放入连接网络的合成器(处理样板让合成器面向熔炉/机械动力等机器，并用输入总线抽回成品)→在合成终端里点击缺货物品即可一键下单自动合成。"
            ),

            // ---- Apothic Attributes ----
            new KnowledgeCard(
                    "attributeslib", "神化属性·战斗数值",
                    new String[]{"神化属性", "属性面板", "暴击", "吸血", "生命偷取", "破甲", "护甲穿透", "护甲粉碎", "闪避", "超量治疗", "attributeslib", "apothic"},
                    "神化属性扩展了暴击率/暴击伤害、生命偷取、超量治疗、护甲穿透、护甲粉碎、闪避率、箭矢速度、挖掘速度等详细属性，可在背包属性面板查看；饰品和附魔提供的这些属性会大幅强化你和帕芙的战斗力。"
            ),

            // ---- Enigmatic Legacy ----
            new KnowledgeCard(
                    "enigmaticlegacy", "神秘遗物·七咒之戒与遗物",
                    new String[]{"神秘遗物", "七咒", "七咒之戒", "诅咒", "灵魂水晶", "扭曲之心", "星界", "enigmatic", "curse", "ring"},
                    "神秘遗物最具代表性的是「七咒之戒」：戴上后无法普通摘下，会带来受伤翻倍、怪物仇恨、护甲削弱等7重诅咒，但同时解锁专属战利品（扭曲之心、诅咒掉落等）和强力终局神器；另有磁铁环、无限水桶、星辰卷轴等实用遗物。"
            ),

            // ---- Celestial Artifacts ----
            new KnowledgeCard(
                    "celestial_artifacts", "星界遗物·饰品体系",
                    new String[]{"星界遗物", "星界", "灾厄", "星辰", "护符", "卷轴", "celestial", "artifact"},
                    "星界遗物新增海量装备在 Curios 饰品栏的强力饰品（项链、手镯、戒指、护符、卷轴、头饰、心等），涵盖生存、输出、探索与套装联动效果，多通过地牢宝箱、击杀特定生物或星界材料合成获取。"
            ),

            // ---- Moonstone ----
            new KnowledgeCard(
                    "moonstone", "月石饰品·基因病毒与融合",
                    new String[]{"月石", "病毒", "基因", "纳米", "噩梦", "黑洞", "扭曲", "血肉", "融合", "moonstone", "dna", "virus"},
                    "月石饰品主打科幻与诡异风格的 Curios 饰品（基因/病毒/纳米/噩梦/黑洞/血肉等流派），很多饰品自带进化或融合机制，需要装备着完成特定条件（如受伤、击杀、收集样本）来成长或合成高阶形态。"
            ),

            // ---- Mine Fargo ----
            new KnowledgeCard(
                    "mine_fargo", "法狗魂石·魔石与魂",
                    new String[]{"法狗", "魂石", "魔石", "精华", "力量", "fargo", "soul", "enchantment"},
                    "法狗魂石(Mine Fargo)参考泰拉瑞亚 Fargo's Souls：先收集各类材料合成单个「魔石」获得独特被动，再把同系魔石合成为「力量/精华」，最终聚合成终极「魂」装入饰品栏，叠加海量强力效果。"
            ),

            // ---- TerraCurio ----
            new KnowledgeCard(
                    "terracurio", "泰拉饰品·合成树与工匠作坊",
                    new String[]{"泰拉", "泰拉饰品", "靴子", "赫尔墨斯", "幽灵靴", "泰拉闪耀靴", "护盾", "十字章", "云朵瓶", "工匠", "terracurio", "terra"},
                    "泰拉饰品把泰拉瑞亚经典饰品带进MC：探索宝箱或打怪获取基础饰品（如靴子、多段跳瓶、护盾、手套、徽章），再用工匠作坊(Workshop)将多个饰品一路合成进阶成复合神器（如霜花靴/泰拉闪耀靴、十字章护盾等）。"
            ),

            // ---- Curios ----
            new KnowledgeCard(
                    "curios", "饰品栏(Curios)",
                    new String[]{"饰品", "饰品栏", "戒指", "项链", "护符", "槽位", "curios", "trinket"},
                    "饰品栏(Curios)在背包界面左上角有专属按钮打开，提供戒指、项链、护符、手镯、腰带、背部等独立槽位；神秘遗物、星界遗物、月石、法狗魂石、泰拉饰品、铁魔法法术书都装备在这里。"
            ),

            // ---- Lootr ----
            new KnowledgeCard(
                    "lootr", "战利品箱(Lootr)",
                    new String[]{"战利品箱", "宝箱", "箱子", "lootr", "loot"},
                    "Lootr 会把自然生成的宝箱和木桶变成独立战利品箱：金色表示你还没开过，打开后变蓝色且每人都有一份专属战利品，联机也不用怕箱子被队友拿空！"
            ),

            // ---- JEI / Jade / Patchouli / FTB ----
            new KnowledgeCard(
                    "jei", "JEI配方查询与Jade/手册",
                    new String[]{"jei", "配方", "怎么合成", "怎么做", "合成表", "用途", "jade", "玉", "手册", "攻略", "patchouli", "书"},
                    "查配方小技巧：在背包或 JEI 右侧栏把鼠标指着任意物品，按 R 看合成表、按 U 看用途、按 A 收藏；准星指着方块/生物时顶部 Jade 会显示血量、采集工具和所属模组；诡厄巫法/神秘遗物等还能合成帕秋莉图文手册查详细机制。"
            ),
            new KnowledgeCard(
                    "ftbessentials", "FTB传送指令与组队",
                    new String[]{"传送", "回家", "指令", "命令", "组队", "home", "sethome", "back", "tpa", "rtp", "ftb"},
                    "FTB Essentials 常用指令：/sethome 设家、/home 回家、/back 返回上次死亡或传送点、/rtp 随机传送、/tpa 玩家名 请求传送；FTB Teams 可在背包左上角建队共享权限。"
            )
    );

    private PuffModKnowledgeBase() {}

    /**
     * Builds compact real-time perception of what the player is holding and looking at,
     * plus loaded mod summary if the user explicitly asks what mods are installed.
     */
    public static String buildPerceptionContext(Minecraft mc, String userMessage) {
        if (mc == null || mc.player == null) return "";
        LocalPlayer player = mc.player;
        StringBuilder sb = new StringBuilder();

        ItemStack main = player.getMainHandItem();
        if (!main.isEmpty()) {
            sb.append("；玩家主手拿着=").append(describeItem(main));
        }
        ItemStack off = player.getOffhandItem();
        if (!off.isEmpty()) {
            sb.append("；副手=").append(describeItem(off));
        }

        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bhr && mc.level != null) {
            BlockState state = mc.level.getBlockState(bhr.getBlockPos());
            if (!state.isAir()) {
                ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                if (id != null && !"minecraft".equals(id.getNamespace())) {
                    sb.append("；玩家正看着方块=").append(state.getBlock().getName().getString())
                            .append("(").append(modDisplayName(id.getNamespace())).append(")");
                }
            }
        } else if (hit instanceof EntityHitResult ehr) {
            Entity target = ehr.getEntity();
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(target.getType());
            if (id != null && !"minecraft".equals(id.getNamespace()) && !"puffcompanion".equals(id.getNamespace())) {
                sb.append("；玩家正看着生物=").append(target.getDisplayName().getString())
                        .append("(").append(modDisplayName(id.getNamespace())).append(")");
            }
        }

        if (asksAboutModList(userMessage)) {
            List<String> loaded = getLoadedTrackedModNames();
            if (!loaded.isEmpty()) {
                sb.append("\n当前已加载的主要模组：").append(String.join("、", loaded)).append("。");
            }
        }
        return sb.toString();
    }

    /**
     * Builds a compact (~60-140 char) memory summary of completed stories, branch choices,
     * and unlocked endings so Puff genuinely remembers her journey with the player at tiny token cost.
     */
    public static String buildStoryMemoryContext(PuffEntity puff) {
        if (puff == null) return "";
        List<String> memories = new ArrayList<>();
        if (isDone(puff, PuffStories.PROLOGUE)) memories.add("草丛初遇");
        if (isDone(puff, PuffStories.CHAPTER1)) memories.add("秘密小窝藏宝");
        if (isDone(puff, PuffStories.CHAPTER2)) memories.add("共建奶酪工坊");
        if (isDone(puff, PuffStories.CHAPTER3)) memories.add("共闯下界末地险境");
        if (isDone(puff, PuffStories.SIDE1)) memories.add("一起躲过大猫天敌");
        if (isDone(puff, PuffStories.SIDE2)) memories.add("海边草帽夏日约会");
        if (isDone(puff, PuffStories.SIDE3_STARLIGHT)) memories.add("星夜露营许下约定");
        if (isDone(puff, PuffStories.SIDE4_CHEESE_FEAST)) memories.add("深夜同吃秘制芝士火锅");
        if (isDone(puff, PuffStories.SIDE5_RAT_PARADE)) memories.add("月下鼠群大巡游");
        if (isDone(puff, PuffStories.AFTER_MORNING)) memories.add("婚后双份奶酪清晨");

        List<String> crossovers = new ArrayList<>();
        if (isDone(puff, PuffStories.MOD_GOETY) || isDone(puff, PuffStories.MOD_GOETY_REV)) crossovers.add("诡厄巫法");
        if (isDone(puff, PuffStories.MOD_IRONS)) crossovers.add("铁魔法");
        if (isDone(puff, PuffStories.MOD_TRAVELOPTICS)) crossovers.add("星光法术");
        if (isDone(puff, PuffStories.MOD_ALEXSCAVES)) crossovers.add("亚历克斯洞穴");
        if (isDone(puff, PuffStories.MOD_CATACLYSM)) crossovers.add("灾变试炼");

        List<String> endings = new ArrayList<>();
        if (isDone(puff, PuffStories.FINALE)) endings.add("誓约伴侣");
        if (isDone(puff, PuffStories.ENDING_ADVENTURE)) endings.add("群星共行者");
        if (isDone(puff, PuffStories.ENDING_HEARTH)) endings.add("暖灯奶酪窝");
        if (isDone(puff, PuffStories.ENDING_QUEEN)) endings.add("奶酪王国小小女王");
        if (isDone(puff, PuffStories.ENDING_TRUE_VOW)) endings.add("永不落幕的双星真结局");

        Set<String> flags = puff.getSyncedStoryFlags();
        List<String> choices = new ArrayList<>();
        if (flags.contains("promise_stars")) choices.add("约好一起看遍群星维度");
        if (flags.contains("promise_home")) choices.add("约好建一座永远亮灯的奶酪小窝");
        if (flags.contains("promise_together")) choices.add("约定只要彼此在身边去哪都好");
        if (flags.contains("route_hearth_feed")) choices.add("亲手吹凉芝士喂过帕芙");
        if (flags.contains("rat_kingdom_knight")) choices.add("自封为帕芙的首席护卫骑士");
        if (flags.contains("rat_kingdom_coruler")) choices.add("与帕芙约定做共同国王");

        if (memories.isEmpty() && endings.isEmpty() && choices.isEmpty() && crossovers.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n【你与玩家的共同回忆（请在聊天中自然记住这些经历与约定，不要生硬报菜名）】");
        if (!memories.isEmpty()) sb.append("\n- 共同经历：").append(String.join("、", memories));
        if (!crossovers.isEmpty()) sb.append("\n- 跨模组冒险：").append(String.join("、", crossovers));
        if (!choices.isEmpty()) sb.append("\n- 玩家做过的关键选择：").append(String.join("；", choices));
        if (!endings.isEmpty()) sb.append("\n- 已达成的关系/结局：").append(String.join("、", endings));
        sb.append("\n");
        return sb.toString();
    }

    private static boolean isDone(PuffEntity puff, PuffStories.Entry entry) {
        int idx = PuffStories.indexOf(entry.id());
        return idx >= 0 && puff.isStoryDone(idx);
    }

    /**
     * Retrieves up to 3 most relevant concise knowledge cards based on user query,
     * held items, and looked-at block/entity. Returns empty string for casual chat (0 extra tokens).
     */
    public static String retrieveKnowledge(String userMessage, Minecraft mc) {
        String lowerMsg = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        Set<String> contextMods = collectContextModIds(mc);
        String contextNames = collectContextNames(mc).toLowerCase(Locale.ROOT);

        boolean askingAboutHeldOrTarget = containsAny(lowerMsg,
                "这个", "这是", "手里", "拿着", "手上", "干嘛", "怎么用", "有什么用", "啥用", "怎么合成", "怎么做", "怎么玩", "是什么", "怎么搞");

        record ScoredCard(KnowledgeCard card, int score) {}
        List<ScoredCard> matched = new ArrayList<>();

        for (KnowledgeCard card : CARDS) {
            if (!isModLoaded(card.modId())) continue;
            int score = 0;

            for (String kw : card.keywords()) {
                String lkw = kw.toLowerCase(Locale.ROOT);
                if (lowerMsg.contains(lkw)) {
                    score += (lkw.length() >= 3 ? 6 : 4);
                } else if (askingAboutHeldOrTarget && !contextNames.isEmpty() && contextNames.contains(lkw)) {
                    score += 3;
                }
            }

            if (contextMods.contains(card.modId())) {
                score += askingAboutHeldOrTarget ? 5 : (score > 0 ? 3 : 1);
            }

            // Only inject cards with meaningful relevance (score >= 4) so casual chat costs 0 tokens
            if (score >= 4) {
                matched.add(new ScoredCard(card, score));
            }
        }

        if (matched.isEmpty()) return "";

        matched.sort(Comparator.comparingInt(ScoredCard::score).reversed());
        StringBuilder sb = new StringBuilder();
        sb.append("\n【当前相关模组知识（仅供参考，请用帕芙的软萌口吻自然回答，不要生硬背书；若细节未列出可提醒用JEI按R/U或模组手册查看）】\n");
        int count = Math.min(MAX_CARDS, matched.size());
        for (int i = 0; i < count; i++) {
            KnowledgeCard c = matched.get(i).card();
            sb.append("- ").append(c.title()).append("：").append(c.content()).append("\n");
        }
        return sb.toString();
    }

    private static Set<String> collectContextModIds(Minecraft mc) {
        Set<String> mods = new LinkedHashSet<>();
        if (mc == null || mc.player == null) return mods;
        addItemMod(mods, mc.player.getMainHandItem());
        addItemMod(mods, mc.player.getOffhandItem());
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bhr && mc.level != null) {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(mc.level.getBlockState(bhr.getBlockPos()).getBlock());
            if (id != null && !"minecraft".equals(id.getNamespace())) mods.add(id.getNamespace());
        } else if (hit instanceof EntityHitResult ehr) {
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(ehr.getEntity().getType());
            if (id != null && !"minecraft".equals(id.getNamespace())) mods.add(id.getNamespace());
        }
        return mods;
    }

    private static String collectContextNames(Minecraft mc) {
        if (mc == null || mc.player == null) return "";
        StringBuilder sb = new StringBuilder();
        if (!mc.player.getMainHandItem().isEmpty()) {
            sb.append(mc.player.getMainHandItem().getHoverName().getString()).append(' ');
        }
        if (!mc.player.getOffhandItem().isEmpty()) {
            sb.append(mc.player.getOffhandItem().getHoverName().getString()).append(' ');
        }
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bhr && mc.level != null) {
            sb.append(mc.level.getBlockState(bhr.getBlockPos()).getBlock().getName().getString()).append(' ');
        } else if (hit instanceof EntityHitResult ehr) {
            sb.append(ehr.getEntity().getDisplayName().getString()).append(' ');
        }
        return sb.toString();
    }

    private static void addItemMod(Set<String> mods, ItemStack stack) {
        if (stack.isEmpty()) return;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id != null && !"minecraft".equals(id.getNamespace())) {
            mods.add(id.getNamespace());
        }
    }

    private static String describeItem(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        String name = stack.getHoverName().getString();
        if (id == null || "minecraft".equals(id.getNamespace())) {
            return name;
        }
        return name + "(" + modDisplayName(id.getNamespace()) + ")";
    }

    private static String modDisplayName(String modId) {
        return TRACKED_MOD_NAMES.getOrDefault(modId, modId);
    }

    private static boolean isModLoaded(String modId) {
        return "puffcompanion".equals(modId) || ModList.get().isLoaded(modId);
    }

    private static List<String> getLoadedTrackedModNames() {
        List<String> list = new ArrayList<>();
        for (Map.Entry<String, String> e : TRACKED_MOD_NAMES.entrySet()) {
            if (isModLoaded(e.getKey())) {
                list.add(e.getValue());
            }
        }
        return list;
    }

    private static boolean asksAboutModList(String msg) {
        if (msg == null) return false;
        String lower = msg.toLowerCase(Locale.ROOT);
        return containsAny(lower, "哪些模组", "什么模组", "装了什么", "有什么模组", "模组列表", "整合包", "mod列表", "哪些mod");
    }

    private static boolean containsAny(String text, String... terms) {
        for (String t : terms) {
            if (text.contains(t)) return true;
        }
        return false;
    }
}
