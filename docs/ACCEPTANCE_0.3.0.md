# Outbreak 0.3.0 验收与本地安装记录

## 产物

`muxi-outbreak-0.3.0.jar`，726,161 bytes。
SHA-256：`1cc42c54875f89a1d621ebe74b35f904d367ea1f87cdb3473d538d5e30321d55`。
依赖原有TaCZ1.1.8和新增LR Tactical0.4.3。LR JAR SHA-256：`b492f2d37a993d76a8ffd6b79bea89a64de91d4710b1b4841f63d65982f610ac`。
最终测试、实际客户端与安装的Outbreak摘要完全一致。

## 执行证据

- `build/equipment-verification-release.log`：12项Python测试、Java Director回归、3,110条补给策略断言全部通过。
- `build/equipment-engine-report.json`：完整Better MC副本内51条实际服务器引擎断言通过，包含医疗/单槽限制/共享库存/原生枪弹和手雷/安全屋/自然通关/状态恢复。
- `build/full-server-report.json`：338个现有服务器JAR与新版及依赖同载，4,166处真实路线方块匹配，零差异。
- `build/graphical-client-report.json`：真实NeoForge网络客户端持枪、开火、中文补给显示、O键拾取药包、原生LR M67显示/使用/消耗与离场恢复均完成。
- `build/deployment-report.json`：三个指定安装目录的两份新JAR均通过摘要校验，旧Outbreak JAR有备份。

51项引擎断言使用真实ServerPlayer对象与真实世界tick，但没有网络传输；它们不是51项双网络客户端测试。
另外的图形端是干净NeoForge+TaCZ+LR+Outbreak真实网络客户端，不冒充完整Better MC图形端人工通关。
最后两张原生F2截图保留在 `build/qa-client/screenshots/`。
完整测试服务端和图形测试服务端、客户端均正常退出；测试玩家和临时区块票据已清理。

## 实际功能

配装读取同整合包僵尸枪战：MP5 A5/Glock17，实际30+17发弹匣与188发9mm备用。
133个真实BSP补给节点，14处固定无限弹药堆，世界共享有限库存，不再是每人靠近各领一份。
一个主武器位、一个副武器位、一个所有投掷物共用位、一个大型医疗/支援位、一个小药位。
医疗物品消耗与临时生命、被伤打断、5秒救援/肾上腺素加速、除颤器、高爆弹药包和真实LR手雷已测试。
安全屋保留幸存者伤势，不免费重生药品和投掷物。普通弹药堆不自动换弹，不补RPG。

## 安装范围

- `C:\Users\Administrator\WorkSpace\muxigame\bmc5server\mods`
  - `muxi-outbreak-0.3.0.jar`
  - `LesRaisins-Tactical-Equipements-1.21.1-0.4.3.jar`
- `C:\Users\Administrator\WorkSpace\muxigame\bmc5client-repo\game\mods`
  - `muxi-outbreak-0.3.0.jar`
  - `LesRaisins-Tactical-Equipements-1.21.1-0.4.3.jar`
- `C:\Users\Administrator\WorkSpace\muxigame\better-mc-remake\pack\source\Better MC Remake [FORGE]\mods`
  - `muxi-outbreak-0.3.0.jar`
  - `LesRaisins-Tactical-Equipements-1.21.1-0.4.3.jar`

没有重启/停止正式服，没有公开发布OSS manifest或GitHub Release，没有把QA测试JAR安装到正式目录。
本记录中的“安装”仅指文件更新，正在运行的服务器不会自动换成新加载的代码。

## 必须保留的限制

导演供应权重、200/300尸潮的原图Source脚本、原物理道具与演出没有逐字节/逐逻辑完整移植；详见 `EQUIPMENT_PARITY_0.3.0.md`。
146个源weapon实体中133个导入，72个有小范围落点适配，13个不支持或找不到可站立位置的源道具明确列在source-supply-report，而非偷偷替换为药品。
LR1.21.1是非官方早期移植，作者警告可能损坏世界/数据。即使隔离验证通过也不能保证长期存档安全。
正式启用前必须有可恢复的完整世界备份；本轮仅备份JAR，没有声称备份在线世界。
代码/测试/文档提交到main，二进制依赖和用户原始VPK不纳入Git；地图衍生物的再分发许可仍需单独确认。
