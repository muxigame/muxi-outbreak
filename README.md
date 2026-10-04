# muxi Outbreak 0.3.0

Minecraft 1.21.1 / NeoForge 21.1.250 / TaCZ 1.1.8 / LR Tactical 0.4.3。
当前三章战役为「逃离学院」。真实转换了1,999,012个原版方块、370份原生结构NBT。

## 0.3.0 装备与补给

配装对齐本整合包僵尸枪战：MP5 A5 + Glock 17，匹配9mm备用188发（按实际30+17发弹匣×4计算），不再用弓和箭。
五个快捷装备位依次为主武器、副武器、一个投掷物、一个大型医疗/支援品、一个小药。
133个真实BSP补给节点采用队伍共享库存，固定弹药堆无限补足匹配备用弹药；医疗和投掷物拿走后不免费刷新。

详细规则、原版差异、Source遗漏、导演脚本审计和第三方依赖风险见 [装备/补给对照](docs/EQUIPMENT_PARITY_0.3.0.md)。
**LR依赖是非官方早期移植，作者警告可能损坏重要存档；启用前必须有可恢复的完整世界备份。**

## 安装与开局

客户端和服务端均需要本模组、TaCZ和锁定的LR JAR。三个JAR的版本/摘要以build/release.json、dependencies.json和验收报告为准。
旧版Outbreak不能与新版同时放在mods中。本项目部署脚本不启停正式服，下一次正常重启才激活。

```text
/muxioutbreak prepare lostschool
/muxioutbreak inspect lostschool
/muxioutbreak start lostschool
/muxioutbreak join <房间短ID>
/muxioutbreak supplies
/muxioutbreak supply <节点ID>
/muxioutbreak leave
```

每队1–4人，同图同时一队。地图独立维度muxi_outbreak:campaign，首次分批安装而不是对生存世界执行巨型fill。
战役中按 F 对准场景交互：普通门、安全屋门、活板门、栅栏门、按钮/拉杆，以及武器拾取/交换和各类补给。服务端按 3.5 格视线选择一个目标，安全屋阶段规则及外部容器封闭规则保留；F 不调用手持枪械或医疗品使用。Q 丢一个、Ctrl+Q 丢整组；治疗/投掷和蹲下救援保持原操作。修复与原生键路由验收见 [F 交互记录](docs/f-interaction-20261004.md)。
急救包长按使用5秒；给近处队友使用需蹲下。倒地救援在队友附近蹲下5秒，肾上腺素可加速。

## 开发和验证

```powershell
python build.py --test
python tools/convert_bsp_campaign.py
python tools/qa_server.py --heap 1536 --engine-tests
# 隔离服运行后：
python tools/qa_rcon.py outbreakqa run
python tools/qa_rcon.py outbreakqa status
python tools/qa_rcon.py stop
```

QA固定127.0.0.1:25683/25684和build/qa-server独立存档，不使用正式服存档。
图形客户端工具qa_client.py和完整整合包模式--full分别测试联网展示与整合包加载。不要使用旧版纯原版协议机器人证明0.3已验证。
测试专用JAR只在build的测试实例中生成，不能部署正式服。

## 0.2.0 历史记录

旧地图转换/旧装备验收保留在 [0.2.0验收](docs/ACCEPTANCE_0.2.0.md)，其中弓箭配装和旧网络测试结果不适用于0.3.0。

## 地图素材

地图源为用户提供的myl4d2addons_wm_lostschool.vpk；包内标注作者未名、Workshop 795271842。
自编代码许可不授予原作者地图及衍生结构的再分发权；原始VPK、Source音乐/纹理和依赖模组素材不随自编JAR打包。
静态/动态MDL道具、Source谜题和演出不是一比一移植。本版属于原生合作生存改编，不能称为完整L4D2引擎移植。

---

## Original master branch documentation

# muxi Outbreak

Cooperative infected campaign/survival minigame for muxigame Minecraft.

## Status

- AI Director prototype
- Left 2 Mine clean-room metadata conversion pipeline
- NeoForge 1.21.1 server-side architecture

## Development

The repository is split into:

- native maps: muxigame original campaigns
- legacy maps: converted gameplay metadata from compatible sources
- tools: world analysis and conversion helpers

Third-party maps/assets are not bundled unless redistribution rights are available.
