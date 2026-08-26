# 项目记忆文档（MEMORY）

每次调用前必须通读本文件并严格遵守，内容如下：

## 1. 语言要求
- **必须使用中文回答**，所有与用户的交流均使用中文。

## 2. 文本本地化要求
- 所有游戏内文字显示（物品名、方块名、创造栏 Tab 名、提示/描述等）**严禁硬编码**。
- 必须通过 lang 统一调配：
  - `src/main/resources/assets/torcherino_ae_mod/lang/en_us.json`（英文）
  - `src/main/resources/assets/torcherino_ae_mod/lang/zh_cn.json`（中文）
- 键名约定：物品 `item.<modid>.<name>`、方块 `block.<modid>.<name>`、创造栏 `itemGroup.<modid>`。

## 3. 项目结构简述
- 平台：NeoForge **1.21.1** / Java 21（MDK 模板）。
- modid：`torcherino_ae_mod`；基础包：`com.tianhai.torcherino_ae`。
- 主入口：`Torcherinoaemod.java`：注册各 DeferredRegister、公共 setup 事件。
- 客户端入口：`TorcherinoaemodClient.java`：客户端 setup、配置界面。
- 配置：`Config.java`（NeoForge ModConfigSpec）。
- 注册（现代化 DeferredRegister 风格）：
  - `block/ModBlocks.java` — `DeferredRegister<Block>`，含加速火把占位方块 `torcherino`。
  - `block/TorcherinoBlock.java` — 加速火把方块类（功能暂未实现）。
  - `item/ModItems.java` — `DeferredRegister<Item>`，方块的 BlockItem。
  - `item/ModCreativeTabs.java` — `DeferredRegister<CreativeModeTab>` 创造栏标签。
- 资源：`src/main/resources/assets/torcherino_ae_mod/`
  - `lang/` — 本地化文本
  - `models/block|item/` — 模型
  - `blockstates/` — 方块状态
  - `textures/block|item/` — 贴图
- 构建：Gradle（`gradlew`）；混入配置 `torcherino_ae_mod.mixins.json`（当前无 mixin）。