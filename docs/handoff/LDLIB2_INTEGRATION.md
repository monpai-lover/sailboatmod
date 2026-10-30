# LDLib2 2.2.42 正式接入

遊戲內 UI 已整合完整回傳包，並由 Codex 修復初始化、主題生命週期、庫存選擇與版面問題。正式專案使用 Minecraft 1.21.1、NeoForge 21.1.259、Java 21。詳細驗證見 UI_REPAIR_2026-10-13.md。

## 必要相依

- 客戶端與伺服器均安裝 LDLib2 2.2.42、GeckoLib 4.9.3。
- LDLib2 是獨立模組，metadata 鎖定 [2.2.42]，主模組不再嵌套整份 LDLib2。
- build.gradle 使用 libs/ldlib2-neoforge-1.21.1-2.2.42-all.jar。
- libs/ldlib2-compile/taffy-1.1.5.jar 與 yoga-1.0.0.jar 從該 JAR 的 JarJar 原樣提取，只提供 compileOnly/testCompileOnly。執行期使用 LDLib2 自帶的版本。
- GeckoLib 仍是外部必要模組。其他既有內嵌函式庫與 SQLite 保留。
- Elementa、UniversalCraft、舊 BlockUI 畫面已由此次 UI 遷移移除。

| 檔案 | SHA-256 |
| --- | --- |
| LDLib2 all.jar | e98758b5089d40ca5ae5c8d928b9057feddff10b000bc37c0e3a480e1e19911b |
| taffy-1.1.5.jar | dd89c3fd93a0377062f67575c12fa74bb8dcd842c1ea2929150f1a50db1e8532 |
| yoga-1.0.0.jar | bacd086489858ca9fcd438721c75373ce8deab5e9fead23425a42a16fe1dc9a7 |

## 建置與開發

正式建置不需要舊交接包的開發 init script：

```powershell
.\gradlew.bat test releaseMod --console=plain -PfixIndex=5
.\gradlew.bat runClient
.\gradlew.bat runServer
```

兩種主題放在 assets/sailboatmod/lss/ledger.lss 與 trading_hall.lss。`.sm-window` 控制視窗表面；`.sm-controls` 供視窗、全螢幕市場浮動選單和道路規劃器共用控制項配色，不能替它加上整片視窗背景。

可視化編輯器使用單人世界命令 `/ldlib2_ui_editor`。XML/LSS/圖片必須回存 mod resources，不能只留在遊戲目錄。

目前網路協定為 16：開著的市場畫面會收到其他玩家造成的即時更新；獨立交通地圖物品使用專用界面與路線請求／回覆；庫存概覽與上架請求仍傳送物品元件識別。客戶端與伺服器須一同更新，舊版協定會拒絕連線。

上游：https://github.com/Low-Drag-MC/LDLib2
