# CLAUDE.md — 帕芙（Puff）Rats 附属模组开发规范

> **使用方式**：将本文件放到你的 Minecraft 1.20.1 Forge 模组工程根目录，Claude Code 每次启动都会自动读取它作为最高开发准则。

---

## 一、技术栈与版本红线（绝对禁止越界）

* **Minecraft 版本**：`1.20.1`
* **模组加载器**：`MinecraftForge 47.1.0+`（推荐 `47.2.x`）
* **Java 版本**：`Java 17`
* **前置依赖**：
  * `rats`（Rats: Reboot `8.1.5`）
  * `citadel`（`2.2+`）
  * `geckolib`（`4.8.4` ~ `4.x`，GeckoLib 4 API）
* **严禁犯的错误**：
  1. **严禁使用 NeoForge 包名或 API**（`net.neoforged.*` 一律禁止）。
  2. **严禁使用 MC 1.20.5+ / 1.21+ API**（例如 `DataComponentType`、`CustomData`、`Codec` 新版注册或 `RegistryAccess` 1.21 写法）。1.20.1 物品附加数据一律使用 `ItemStack#getOrCreateTag()`（`CompoundTag`）。
  3. **严禁引入外部 AI / HTTP API**：所有对话与剧情必须**100% 本地无 API 运行**。
  4. **严禁在 Java 代码里硬编码长段中文对白**：全部走 `assets/<modid>/lang/zh_cn.json` 与 `data/<modid>/puff_stories/*.json`。

---

## 二、人设与设计文档索引

开发任何功能、对白、剧情或服装切换前，必须先查阅以下设计文档：
* `docs/puff_npc_docs/01_人设与阶段设定.md`（外貌、四阶段成长、称呼、五套服装设定）
* `docs/puff_npc_docs/02_主线与支线剧情脚本.md`（8 幕 Galgame 剧情与触发条件）
* `docs/puff_npc_docs/03_交互菜单与事件触发对话库.md`（事件总览、右键菜单、送礼分级、随机台词池）

---

## 三、已验证的 `Rats: Reboot 8.1.5` 真实类与 ID 清单

**规则**：需要调用 `rats` 的类或方法时，**禁止凭记忆瞎猜**；必须先用 `javap -cp libs/Rats-Reboot-Forge-1.20.1-8.1.5.jar <全类名>` 查看真实方法签名后再写代码。

### 1. 核心实体类
* 普通老鼠：`com.github.alexthe666.rats.server.entity.rat.Rat`（父类：`AbstractRat`、`InventoryRat`）
* 老鼠指令枚举：`com.github.alexthe666.rats.server.entity.rat.RatCommand`
* 瘟疫医生：`com.github.alexthe666.rats.server.entity.misc.PlagueDoctor`
* 鼠药师：`com.github.alexthe666.rats.server.entity.misc.MouseApothecary`
* 老鼠 AI Goal 参考：`com.github.alexthe666.rats.server.entity.ai.goal.*`（如 `RatWorkGoal`、`RatDepositGoal`、`RatSeekCheeseGoal`、`RatFollowOwnerGoal`）

### 2. 注册表与物品/方块 ID
* 物品注册表：`com.github.alexthe666.rats.registry.RatsItemRegistry`
* 方块注册表：`com.github.alexthe666.rats.registry.RatsBlockRegistry`
* 鼠特兰蒂斯物品/实体注册表：`RatlantisItemRegistry`、`RatlantisEntityRegistry`
* 常用奶酪物品 ID（可通过 `ForgeRegistries.ITEMS` 或 `TagKey` 兼容）：
  * `rats:cheese`
  * `rats:blue_cheese`
  * `rats:block_of_cheese`
  * `rats:block_of_blue_cheese`
  * `rats:block_of_nether_cheese`
  * `rats:chunky_cheese_token`

### 3. 维度 ID
* 鼠特兰蒂斯：`rats:ratlantis`
* 下水道：`rats:sewer`

---

## 四、项目架构规范

### 1. 实体与数据持久化（`PuffEntity`）
* 继承 `TamableAnimal` 并实现 GeckoLib 4 `GeoEntity`。
* 所有核心状态必须同时支持：
  * `SynchedEntityData`（用于客户端渲染换装、表情、气泡状态同步）
  * `addAdditionalSaveData` / `readAdditionalSaveData`（NBT 持久化）
* 必存字段：
  * `OwnerUUID`（主人 UUID）
  * `Affection`（好感度 `0~100`）
  * `GrowthStage`（成长阶段 `1~4`）
  * `CallMode`（阶段二/三称呼倾向：`brother` 哥哥 / `sister` 姐姐）
  * `CurrentOutfit`（`early` / `mid` / `late` / `swim` / `wedding`）
  * `PuffHunger`（帕芙饱食度 `0~100`）
  * `CompletedStories`（已完成的剧情 ID 列表）
  * `HoardInventory`（秘密小窝 9~18 格小背包）

### 2. 三层本地无 API 交互架构
1. **剧情演出层（Galgame DialogueScreen）**
   * 从 `data/<modid>/puff_stories/*.json` 加载剧本。
   * 支持：角色名、表情切换、打字机文本、选项分支、好感增减、服装/称呼解锁。
   * 由服务端检查触发条件并记录已读，通过 Forge `SimpleChannel` 发包通知客户端打开 `DialogueScreen`，玩家选择选项后发回服务端结算奖励。
2. **主动交互菜单层（InteractionScreen）**
   * 右键帕芙打开交互面板：聊天话题树、赠送主手物品、指令切换（跟随/等待/坐下/坐肩膀/摸头）、换装按钮（仅显示已解锁服装）。
3. **事件触发气泡/聊天栏层**
   * 服务端定时/事件驱动检查（冷却时间、优先级），向主人客户端发送 `PuffBubblePacket`（含语言键与参数），客户端使用 `I18n.get` 替换 `{player}` / `{call}` 后渲染头顶气泡或聊天栏消息。

### 3. GeckoLib 4 模型与换装约定
* 五套服装尽量复用同一套主体骨骼（`.geo.json`），通过切换贴图或控制衣服骨骼组（bone `setHidden(true/false)`）实现换装。
* 常见动画状态命名：`idle`、`walk`、`sit`、`shy_freeze`（摸头当机）、`cat_panic`（遇猫炸毛）、`cheese_happy`（奶酪开心磨牙）、`alert_ear`（竖耳警戒）。

---

## 五、每次修改后的强制验证流程

1. 每完成一个独立模块，必须运行 `./gradlew compileJava` 确认零编译错误。
2. 涉及资源文件、实体渲染、GUI 或发包的改动，必须提示用户运行 `./gradlew runClient` 并给出明确的**游戏内验收步骤**。
3. 严禁在一个任务里同时大改多个无关系统；每次只完成一个明确的小目标。
