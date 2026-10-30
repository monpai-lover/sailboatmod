# SailboatMod 開發入口

本專案目標 Minecraft 1.21.1、NeoForge 21.1.259、Java 21；網路協定為 **16**。

先讀 [README.md](README.md) 的建置與安裝方式，再讀 [AGENTS.md](AGENTS.md) 的架構與 [LDLib2 接入說明](docs/handoff/LDLIB2_INTEGRATION.md)。GeckoLib 4.9.3 與 LDLib2 2.2.42 為必要外置模組；Tellus 0.8.3、Create 6.0.10 是可選整合，其編譯用 JAR 在 `libs/compile-only/`。

本次回傳的功能、設計背景與原作者已做／未做的驗證，見 [Tellus 適配回交說明](docs/handoff/TELLUS_ADAPTATION_RETURN.md)。這是基於 fix_5 的更新，請保留其市場貨款保管、退款授權、庫存元件識別、原生容器操作、UI 草稿狀態與生命週期修復。

舊的完整 UI 原生驗證來源包與截圖屬獨立交接附件，不是編譯依賴；本機可在 `artifacts/` 找到。不要把 Git 快取、世界、日誌或舊的交接 manifest 當作新功能來源提交。
