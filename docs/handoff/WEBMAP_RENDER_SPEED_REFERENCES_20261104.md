# WebMarket 地图渲染提速参考（2026-11-04）

目标是减少 512×512 二维瓦片的重复解码、压缩和父层写入，同时保留坐标、取色、透明区域及现有缩放采样规则。下面记录的是查阅真实源码后选用的机制；本项目自行实现，不复制这些项目的代码。引用不表示使用它们作为运行依赖。

## 已核对的来源

- **Squaremap，MIT，`2e158c2f11b22923c29bdd91ed0dbab74d055d15`**：
  [RegionImageSaveQueue](https://github.com/jpenilla/squaremap/blob/2e158c2f11b22923c29bdd91ed0dbab74d055d15/common/src/main/java/xyz/jpenilla/squaremap/common/render/output/RegionImageSaveQueue.java) 将基础图至父层的保存作为一条有界任务链，同一瓦片依次处理；[TileImages](https://github.com/jpenilla/squaremap/blob/2e158c2f11b22923c29bdd91ed0dbab74d055d15/common/src/main/java/xyz/jpenilla/squaremap/common/render/output/TileImages.java) 使用原子替换及显式 PNG 编码参数。[许可证](https://github.com/jpenilla/squaremap/blob/2e158c2f11b22923c29bdd91ed0dbab74d055d15/LICENSE)。
- **BlueMap，MIT，`96ae320d1d39581c10058d8222f2290f2fcb5333`**：
  [LowresLayer](https://github.com/BlueMap-Minecraft/BlueMap/blob/96ae320d1d39581c10058d8222f2290f2fcb5333/core/src/main/java/de/bluecolored/bluemap/core/map/lowres/LowresLayer.java) 复用已解码瓦片、按修改集合保存；[BmMap](https://github.com/BlueMap-Minecraft/BlueMap/blob/96ae320d1d39581c10058d8222f2290f2fcb5333/core/src/main/java/de/bluecolored/bluemap/core/map/BmMap.java) 合并连续保存请求。[许可证](https://github.com/BlueMap-Minecraft/BlueMap/blob/96ae320d1d39581c10058d8222f2290f2fcb5333/LICENSE)。
- **Dynmap，Apache 2.0，`93b454efb8802dc7406d6873434f2aeec5c636f4`**：
  [TileHashManager](https://github.com/webbukkit/dynmap/blob/93b454efb8802dc7406d6873434f2aeec5c636f4/DynmapCore/src/main/java/org/dynmap/storage/filetree/TileHashManager.java) 在压缩前识别未变化图像；[ImageIOManager](https://github.com/webbukkit/dynmap/blob/93b454efb8802dc7406d6873434f2aeec5c636f4/DynmapCore/src/main/java/org/dynmap/utils/ImageIOManager.java) 避免小图使用临时磁盘缓存。[许可证](https://github.com/webbukkit/dynmap/blob/93b454efb8802dc7406d6873434f2aeec5c636f4/LICENSE)。本项目使用严格像素比较，不只凭 CRC 判定图像相同。
- **Pl3xMap 的可访问维护分支 `granny/Pl3xMap`，MIT，`9876e4b651b7f136823f150ae943f3b1b388e23b`**：
  [IO](https://github.com/granny/Pl3xMap/blob/9876e4b651b7f136823f150ae943f3b1b388e23b/core/src/main/java/net/pl3x/map/core/image/io/IO.java) 提供编码参数、释放 writer 并原子替换输出。[许可证](https://github.com/granny/Pl3xMap/blob/9876e4b651b7f136823f150ae943f3b1b388e23b/LICENSE)。此处明确引用维护分支，不将其当作原仓库。

## 本项目的取舍与验证

1. 使用有容量上限、按世界隔离的解码像素缓存；多次部分更新共享已读取的基础图及缩略层。相同像素不重新编码；清缓存、外部文件变化和世界切换必须使相应条目失效。
2. 合并连续更新时保留每一批触及的像素，失败工作继续保留；共享父瓦片的读改写需要保持顺序。不得把简单增加图片线程数当作安全的并行方案，也不改变当前中心取样规则。
3. **保留 JDK 默认 PNG 压缩参数。** 本轮编码比较中，降低压缩令部分缩略图体积增至约三倍，因此选择局部内存 ImageIO 流来去掉临时磁盘缓存，要求同一输入得到相同 PNG 字节。不改变全局 `ImageIO.setUseCache`，避免影响其他模组。
4. 网页单独每两秒读取有界瓦片变更流，仅在地图已挂载且页面可见时工作；打开或恢复可见时立即检查。市场等元数据维持三十秒刷新，瓦片按坐标刷新并保留每批最多 64 张的限制。

验收应分别记录解码／编码次数、输出体积、分配内存、首次可见延迟和完整保存耗时；对负坐标、部分透明瓦片、共享父层、写入失败重试、暂停恢复及停服恢复做像素一致性检查。后台微基准、自动回归和实际服务器表现需分别说明，不从微基准推断线上 TPS 提升。
