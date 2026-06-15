# P2P 货运 Phase 5：下单弹窗 UI（运输模式 + 收货地）设计

日期：2026-06-15
状态：已获用户分节确认，待审阅

## 背景与问题

后端的点对点货运三模式逻辑已完成且正确：
- ③ **卖家发货（SELLER_SHIP）**：卖家把货发到买家选定的收货仓。
- ② **自动自提（AUTO_PICKUP）**：买家空载具空驶到源仓，装货后返回收货仓。
- ① **真人自提（REAL_PICKUP）**：货物锁定，等买家真人驾车进场装载。

`MarketBlockEntity` 在下单时已为所有模式写入 `targetWarehousePos`，`forwardAutoPickupIfNeeded` 也正确使用它。**端到端的缺口在前端**：两端购买入口都硬编码 `SELLER_SHIP` + `BlockPos.ZERO`，玩家无从选择运输模式与收货仓；且 `resolveBuyerTargetWarehouse` 在收货仓缺失时**危险回退到卖家源仓（linkedDockPos）**，会把货发回卖家——这是审查缺口的根因。

Phase 5 的目标：在网页端与客户端**各加一个下单确认弹窗**，让买家选择**运输模式（三选一）**与**收货地（下拉）**；收货候选由后端基于绑定玩家所在 town 统一计算，随 `MarketOverviewData` 下发，两端同源消费；并修掉危险回退。

本期在三模式选择之上再补两项体验：

- **模式可达性**：运输模式是否可选，取决于「源仓 → 玩家现选收货仓」之间是否存在可达航线或道路。不可达的模式在弹窗里置灰并提示原因，从源头杜绝下注定派不出车的单。载具（船/马车）由后端按港口/驿站终端类型自动分流挑选，玩家不手选站点或车辆。
- **排队 + ETA（仅卖家发货）**：③卖家发货用的是卖家的运力，买家不掌握；卖家无空闲载具时订单进队列，买家在「我的订单」看到排队位次 + 粗估等待时间。②自动自提用买家自己的载具，买家自知运力，不做排队可视化。

## 设计原则

- **同源**：收货候选、模式可选性、排队位次/ETA 均由后端在 overview 组装的**单一计算点**产出，客户端与网页端都只读下发结果，不各算一套（方案 A1）。
- **复用现有签名与能力**：网络包 5-arg 构造器、网页 6-arg 重载均已就绪，只改调用方（方案 B1）；可达性校验与载具分流复用现有 `resolveDispatchTerminalPlan`（真路由，不可达返回 null）；排队复用现有「无车订单留队列、后台 tick 重试」的隐式队列，只做显式化。后端核心逻辑与包编解码零改。
- **融入现有视觉**：网页弹窗严格复用现网页设计系统（CSS 变量、玻璃质感、暖橙强调、Aptos 字体、亮/暗双主题），不另起一套美学；精致来自令牌一致性与克制，而非堆砌动效。

## 1. 后端数据层（同源计算）

### 1.1 新增 `WarehouseOption`
作为 `MarketOverviewData` 的嵌套 record：
```java
public record WarehouseOption(BlockPos pos, String displayName, String townName) {}
```

### 1.2 `MarketBlockEntity.receivingWarehouseOptionsForViewer(String buyerUuid)`
替代现有 `receivingWarehouseCandidatesFor`，口径从「整个 nation 所有 town 仓库」**收窄为当前 town 内、该玩家可写入的仓库**：
1. `NationSavedData.getMember(uuid)` → `townId`；无 town → 返回空列表。
2. `TownWarehouseRegistry.get(level, townId)` 枚举该 town 仓库。
3. 过滤「可写入」：沿用仓库已有的成员/权限判定，无权限的剔除。
4. 每个产出 `WarehouseOption(pos, 仓库显示名, townName)`；显示名复用现有 `warehouseDisplayNameFor`。
5. 顺序：**默认仓库排第一**（现有 `defaultReceivingWarehouseFor` 的那个），其余跟后。

仓库枚举与权限判定抽成可注入的纯函数，便于单测脱离 MC 运行时。

### 1.3 修危险回退 `resolveBuyerTargetWarehouse(buyerUuid, chosen)`
- 现状：`chosen` 为空时回退到 `linkedDockPos`（卖家源仓）——审查缺口。
- 改为：`chosen` 有效 → 用 `chosen`；否则用 `defaultReceivingWarehouseFor`；**再没有 → 返回 null**（彻底移除 linkedDock 回退）。
- 调用方据 null 决定：②③ 模式下 null 应在下单前就被两端 UI 拦截（见 §3/§4 置灰规则），不会误发。

### 1.4 `canChooseReceiving`
`= !receivingWarehouseOptionsForViewer(uuid).isEmpty()`。表示该玩家是否有可选收货仓。两端据此决定收货下拉是否可用，并作为 ②③ 置灰的条件之一（另一条件是 §2.5 的模式可达性；两者皆满足才可选 ②③）。

## 2. `MarketOverviewData` 下发字段（同源契约）

在字段列表尾部追加两个字段（保持现有顺序不动）：
```java
List<WarehouseOption> receivingWarehouseOptions,
boolean canChooseReceiving
```
- 紧凑构造器对 `receivingWarehouseOptions` 做 `null → List.of()` + `List.copyOf` 防御，与现有所有 List 字段一致。
- `canChooseReceiving = !receivingWarehouseOptions.isEmpty()`。

**单点填充**：`MarketOverviewData` 由 `MarketBlockEntity` 统一组装（client 走网络同步、web 经 `buildOverviewForIdentity` 读同一 overview 对象）。只在那一处用 `viewerUuid` 调一次 `receivingWarehouseOptionsForViewer`，填进两个新字段。

**序列化**：`WarehouseOption` 含 `BlockPos` + 两个 String，沿现有 overview 网络编解码模式逐字段写读补上。

## 2.5 模式可达性探测

模式可选性取决于「源仓 → 玩家现选收货仓」的真路由。因收货仓由玩家在弹窗里现选、可切换，采用**按需实时探测**（不预算全部组合）：

- **新增查询往返**：客户端/网页端在「打开弹窗」与「切换收货仓」时，带 `listingId` + 候选收货仓 `pos` 向服务端请求一次探测，服务端回三个布尔。
  - 客户端：新增一对 `encode/decode/handle` 网络包（`ProbeFulfillmentModesPacket` 请求 + 回包，按 `ModNetwork` 现有 packetId++ 模式追加）。
  - 网页端：新增查询端点（如 `/probe-modes`，body 带 `listingIndex` + `targetWarehouse`，返回三布尔 JSON）。
- **后端探测逻辑** `probeFulfillmentModes(listingId, targetWarehousePos)`：
  - ③ SELLER_SHIP 可达 ⟺ 卖家源仓 town 存在可达载具方案到收货仓，即 `resolveDispatchTerminalPlan(卖家源仓, 收货仓, 任一 kind) != null`（遍历 PORT/POST_STATION，真路由，载具自动挑）。
  - ② AUTO_PICKUP 可达 ⟺ 买家有车的 town 存在可达载具方案到收货仓。
  - ① REAL_PICKUP **恒为 true**（玩家自己开去，不依赖系统派车可达性）。
- **载具不让玩家选**：船/马车由 `resolveDispatchTerminalPlan` 按终端类型自动分流并挑最优，UI 不出现「发车站点」下拉。
- **两端 UI 据此置灰**：返回的不可达模式置灰 + 行尾提示「无可达航线/道路」；① 恒可选。与 `canChooseReceiving`（无收货仓）的置灰**叠加**——两者皆为真才可选 ②③。

## 2.6 排队与 ETA（仅卖家发货）

仅 ③SELLER_SHIP 做排队可视化；②自动自提即便在等也不显示位次/ETA（买家用自己的车，自知运力）。

- **复用隐式队列**：无空闲载具的卖家发货单本就留在 `getOpenOrdersForSourceDock(货源)`，后台 tick 反复重试。本期将其**显式化**：
  - 按货源站点（`linkedDockPos`）对「`SELLER_SHIP` 且仍 open 待发」的单**稳定排序**（按下单时间/插入序）。
  - 每单算 `queuePosition`（在本货源队列里的 1-based 序号）。
  - `queueEtaSeconds` **粗估** = `queuePosition × 本货源单轮平均派发耗时`（常量或滑动均值，不真算路程）。
- **`OrderEntry` 新增两字段**（随订单下发，两端同源）：
  ```java
  int queuePosition;     // 0 = 不在排队/已发车/非卖家发货
  int queueEtaSeconds;   // 粗估秒数；0 = 不适用
  ```
  仅 ③SELLER_SHIP 的排队单填非零；②自动自提与已发车单填 0。
- **展示位置**：下单后在「我的订单」列表。`queuePosition > 0` 时显示「排第 N 位 · 约 X 分钟」；车一空出发车后归 0，UI 自动变「派送中」。下单弹窗本身不预告排队。

## 3. 客户端 Elementa 下单弹窗

客户端 GUI 使用 **Elementa** 库（`gg.essential.elementa`），无原生 modal/dropdown，靠 `MarketScreen` 既有 helper 拼装。复用既有全屏 `overlay`（`rebuildUi` 行 390）+ 状态字段 + `rebuildUi()`/`runPreservingScroll` 重渲染模式。

### 3.1 触发改造
footer 的 buy 按钮与数量输入 `onActivate` **不再直发** `sendBuy()`，改为打开弹窗 `openBuyModal()`。数量输入从 footer 迁入弹窗。

### 3.2 新增状态字段
```java
private boolean showBuyModal = false;
private String buyFulfillment = "SELLER_SHIP";   // 默认③
private BlockPos buyReceivingPos = null;          // null = 用默认仓
private boolean modeSellerShipReachable = false;  // 探测回包填充
private boolean modeAutoPickupReachable = false;  // 探测回包填充
```
打开时：`showBuyModal=true`；`buyFulfillment` 复位 `SELLER_SHIP`；`buyReceivingPos` 复位为 `data.receivingWarehouseOptions` 首个（默认仓）pos，空列表则 null；发一次 `ProbeFulfillmentModesPacket(listingId, buyReceivingPos)` 探测；`rebuildUi()`。探测回包到达后再 `rebuildUi()` 刷新置灰态。

### 3.3 渲染
在主面板构建后、`overlay` 之上 `if (showBuyModal)` 铺一层弹窗（全屏半透明 `UIBlock` 吃掉背景点击 + 居中 `createPanel` 卡片）：
1. 标题 + 商品名/单价/总价（`createText`）。
2. 数量：`createInput`，绑 `buyQtyValue`。
3. 运输模式三选一：3 个 `createDropdownOption`，labelKey 对应 ③②①；点击改 `buyFulfillment` + `runPreservingScroll`。置灰规则（叠加）：`!data.canChooseReceiving`（无收货仓）或该模式探测不可达时，②③ 置灰不可点 + 行尾提示（「无可用收货仓库」或「无可达航线/道路」），① 恒可选。
4. 收货地下拉：仅当 `buyFulfillment != REAL_PICKUP` 时显示；铺 `data.receivingWarehouseOptions` 每项一个 `createDropdownOption`，label 用 `displayName`（+ `townName` 副标），选中=`buyReceivingPos`，点击改之并**重新探测可达性**（发 `ProbeFulfillmentModesPacket`，回包刷新模式置灰态）；列表空则不显示下拉、显示提示。
5. 确认 / 取消两个 `createButton`：取消 → `showBuyModal=false` + `rebuildUi()`；确认 → `sendBuy()`（发包即关弹窗，结果由后续 overview 同步反映，与现有 fire-and-forget 发包一致）。

### 3.4 `sendBuy()` 改造（5-arg，B1）
```java
ModNetwork.CHANNEL.sendToServer(new PurchaseMarketListingPacket(
        data.marketPos(),
        listing.listingId(),
        parsePositive(buyQtyValue, 1),
        buyFulfillment,
        buyReceivingPos != null ? buyReceivingPos : BlockPos.ZERO));
showBuyModal = false;
```
① 真人自提时 `buyReceivingPos` 为 null → 发 `BlockPos.ZERO`，后端不需要它。②③ 在 `canChooseReceiving=false` 时进不到确认（按钮置灰），不会误发 ZERO。

### 3.5 ESC
`handleEscape()` 最高优先级加一档：弹窗开着时 ESC 先关弹窗、不关整个界面。

### 3.6 订单列表排队展示
渲染「我的订单」时，若 `OrderEntry.queuePosition > 0`，在该订单行追加「排第 N 位 · 约 X 分钟」（`queueEtaSeconds` 折算分钟，用 `createText`）；否则照旧。仅卖家发货排队单会有非零值。

## 4. 网页端下单弹窗

### 4.1 后端四处（B1）
1. **overview JSON 下发新字段**（`MarketWebService`，`root.add*` 批旁）：
   - `root.addProperty("canChooseReceiving", overview.canChooseReceiving())`
   - 新增 `receivingWarehouseOptions(overview)` 辅助方法产 `JsonArray`，每项 `{pos:"x,y,z", displayName, townName}`，`root.add("receivingWarehouseOptions", ...)`。
2. **`/purchase` 路由读 fulfillment + targetWarehouse**（`MarketWebServer`）：4-arg 改 6-arg 重载：
   ```java
   service.purchaseListing(minecraftServer, identity, marketId,
           intValue(body, "listingIndex", -1),
           intValue(body, "quantity", 1),
           stringValue(body, "fulfillment", "SELLER_SHIP"),
           parseWarehousePos(stringValue(body, "targetWarehouse", "")));
   ```
   `parseWarehousePos` 把 `"x,y,z"` 解析成 `BlockPos`，空串/非法 → `null`（后端据 null 走默认仓）。
3. **新增 `/probe-modes` 端点**：body 带 `listingIndex` + `targetWarehouse`，转后端 `probeFulfillmentModes`，返回 `{sellerShip, autoPickup, realPickup}` 三布尔 JSON（realPickup 恒 true）。
4. **订单数组补排队字段**：overview 的 `sourceOrders`/订单序列化里每项加 `queuePosition`、`queueEtaSeconds`（来自 `OrderEntry` 新字段）。

### 4.2 前端三处（`app.js`）
1. **purchase 按钮改为开弹窗**：click 不再直发，改 `openPurchaseModal(listingIndex)`。
2. **新增 purchase modal**（app.js 无现成 modal，新建轻量组件）：固定定位遮罩 + 居中卡片，DOM 动态插入，沿用现有 `escapeHtml`/`t()`/`number()` 习惯。内容：商品名/单价、数量输入（默认 `state.settings.defaultPurchaseQuantity||1`）、运输模式三选一、收货地下拉（option value=pos 字符串、文本=displayName）。
   - 打开弹窗与切换收货下拉时，POST `/probe-modes`（带 listingIndex + 当前 targetWarehouse），回包刷新三模式可选性。
   - `canChooseReceiving===false` 或某模式探测不可达：②③ 对应项禁用 + 提示（「无可用收货仓库」或「无可达航线/道路」），① 恒可选。
   - 模式=① 真人自提：隐藏收货下拉。
   - 确认 → `postMarketAction("/purchase", { listingIndex, quantity, fulfillment, targetWarehouse })`，成功关弹窗；取消关弹窗。
3. **订单列表排队展示**：渲染订单时，若 `queuePosition > 0`，在该行显示「排第 N 位 · 约 X 分钟」（`queueEtaSeconds` 折算分钟）；否则照旧。仅卖家发货排队单会有非零值。

**state 缓存**：加载 overview 时把 JSON 的 `receivingWarehouseOptions` 与 `canChooseReceiving` 存进 `state`，弹窗读 `state`，不重新请求——网页端消费同源下发的落点。

### 4.3 网页弹窗视觉规格
**判断**：不发明新美学，把现网页「液态玻璃 + 暖橙」语言用到极致，让弹窗像从现有界面里长出来。新增 `.purchase-modal-*` 类，全部复用现有 CSS 变量与质感：

| 元素 | 规格 |
|---|---|
| 遮罩 backdrop | `position:fixed; inset:0;` 高 z-index；`rgba(15,20,27,0.42)` + `backdrop-filter: blur(8px) saturate(1.1)`；`opacity` 120ms 淡入 |
| 卡片 | 居中 `max-width:460px`；玻璃叠层背景（亮/暗各一套，对应现有 `--panel` 玻璃语言）；`border:1px solid var(--line-strong)`；`border-radius: var(--radius-lg)`；`box-shadow: var(--shadow)`；进场 `translateY(8px)→0` + 淡入，一次编排 |
| 标题区 | 商品名（`--text` 加粗）+ 单价/总价（`--muted`，`tabular-nums`）；左侧 `--accent` 竖条 |
| 数量输入 | 复用现有 `input` 基样，聚焦 `--accent` 描边 |
| 运输模式三选一 | 分段按钮（segmented），选中格 `--accent-soft` + `--accent-strong` + `--accent` 描边，未选 `--panel-soft`；置灰态 `opacity:.45; cursor:not-allowed` + `--warning` 小字提示 |
| 收货地下拉 | 现有 `select` 基样；① 时整行 `display:none` |
| 按钮区 | 右对齐：取消 `button.secondary`、确认主橙 `button`；禁用用现有 `:disabled` 样式 |
| 动效 | 仅进场编排 + 分段选中 140ms 颜色过渡，其余静默 |

## 5. i18n 与测试

### 5.1 i18n（三处文案源，均双语）
1. **客户端 lang**（`assets/sailboatmod/lang/en_us.json` + `zh_cn.json` 同步）：
   - `screen.sailboatmod.market.buy.modal.title`
   - `…mode.seller_ship` / `…mode.auto_pickup` / `…mode.real_pickup`
   - `…receiving.label` / `…receiving.none` / `…mode.need_warehouse`
   - `…mode.unreachable`（无可达航线/道路）
   - `…queue.position`（排第 %s 位 · 约 %s 分钟）
   - `…confirm` / `…cancel`
2. **网页端 `t()` 字典**（zh-CN / en-US）：`buy_modal_title`、`mode_seller_ship`/`mode_auto_pickup`/`mode_real_pickup`、`receiving_label`、`receiving_none`、`mode_need_warehouse`、`mode_unreachable`、`queue_position`、`confirm`/`cancel`（部分若已存在则复用）。
3. **措辞统一**：三模式中文 **卖家发货 / 自动自提 / 真人自提**；英文 **Seller ships / Auto pickup / Self pickup**。

### 5.2 测试策略（沿用本仓「源码契约断言 + 纯逻辑」两类）
**纯逻辑单测（JUnit）：**
- `receivingWarehouseOptionsForViewer`：无 town→空；多仓→默认仓首位；无写权限仓被剔除。
- `resolveBuyerTargetWarehouse`：chosen 有效→用 chosen；chosen 为 ZERO/空→默认仓；无默认仓→**返回 null（不再回退 linkedDock）**。
- `canChooseReceiving` = 选项非空。
- `probeFulfillmentModes`：① 恒 true；②③ 在无可达载具方案时为 false（mock `resolveDispatchTerminalPlan` 返回 null）、有方案时为 true。
- 排队：按货源稳定排序后 `queuePosition` 正确递增（1-based）；`queueEtaSeconds` 随位次单调；仅 SELLER_SHIP 排队单非零，AUTO_PICKUP/已发车单为 0。

**源码契约断言（沿用 `PickupWiringContractTest` 风格）：**
- `MarketScreen.sendBuy()` 含 5-arg `PurchaseMarketListingPacket` 构造。
- `MarketWebServer` `/purchase` 分支调 6-arg `purchaseListing` 且读 `fulfillment`/`targetWarehouse`；存在 `/probe-modes` 路由。
- `MarketWebService` JSON 含 `receivingWarehouseOptions` 与 `canChooseReceiving`；订单序列化含 `queuePosition`/`queueEtaSeconds`。
- `MarketOverviewData` 含 `receivingWarehouseOptions` 字段与 `WarehouseOption` record；`OrderEntry` 含 `queuePosition`/`queueEtaSeconds`。
- 新增 `ProbeFulfillmentModesPacket` 已注册进 `ModNetwork`。
- 两份 lang 文件均含新 key（防漏译）。

**构建验证**：`./gradlew build -x test` 出 jar（完整 build 会因 6 个无关 web map 测试中断）；新测试单独 `./gradlew test --tests ...`。

### 5.3 回归手测清单（实现后人工验）
- A 在 B 店买货 → ③卖家发货 + 选收货仓 → 货到 A 的收货仓（核心缺口验证）。
- ②自动自提：空车去源仓 → 装货 → 回收货仓。
- ①真人自提：货物锁定，等真人驾车装载。
- 切换收货仓 → 模式可选性实时刷新；选到无可达航线/道路的仓 → ②③ 置灰提示，①仍可选。
- ③卖家发货且卖家无空闲车 → 订单进队列，「我的订单」显示「排第 N 位 · 约 X 分钟」；卖家车空出 → 自动派发、位次消失。
- 马车走陆路、帆船走水路各验一遍。
- 钱货结算：A 付款、B 收款、货入 A 的仓库。

## 范围边界（YAGNI）

- 不引入 UI 自动化测试框架（沿用源码契约 + 纯逻辑）。
- 不改网络包编解码、不改后端三模式核心逻辑与现有调度/可达性算法（已验证正确，只新增探测/排队的查询与展示包装）。
- 不做收货仓「跨 town / 跨 nation」选择——本期仅当前 town 可写入仓库。
- 自动自提载具**不让玩家手选站点/车辆**——由后端自动分流挑选。
- 排队可视化**仅限 ③卖家发货**；②自动自提不显示位次/ETA。
- ETA 为**粗估**（位次 × 单轮均耗），不做按实际路程的精算。
- 网页弹窗不发明新视觉体系，严格复用现有设计令牌。
