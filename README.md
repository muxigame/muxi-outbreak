# muxi Outbreak 0.2.0

Minecraft 1.21.1 / NeoForge 21.1.250 / Java 21+ 的合作战役小游戏。
实际可运行地图为「逃离学院」：9号宿舍 → 8号宿舍 → 大操场。

## 安装与开局

将 `build/libs/muxi-outbreak-0.2.0.jar` 放入服务端和客户端 `mods/`，移走旧版同名模组，不能同时安装两版。
正常重启后才会加载新模组。本项目的部署过程不启停服务器。
地图结构已打入 JAR，不需要 Amulet/WorldEdit，也不需要客户端持有 VPK。

管理员可先准备地图：

```text
/muxioutbreak prepare lostschool
/muxioutbreak inspect lostschool
```

`prepare` 每 tick 最多写入 4096 个方块，并使用约 6ms 的时间预算。
首次准备等待 `geometryReady=true`。落盘完成后保存安装标记；再次使用还会检查实际出生点，避免空地图误判就绪。

```text
/muxioutbreak list
/muxioutbreak start lostschool
/muxioutbreak join <房间短ID>
/muxioutbreak leave
```

每队 1–4 人，同一地图同时只允许一队。玩家可直接创建房间，地图就绪后开始 10 秒倒计时。
使用独立 `muxi_outbreak:campaign` 维度，不向生存、家园或冒险维度粘贴建筑。

## 本版玩法

AI Director 调节普通感染者、尸潮、特殊感染者与 Boss。两处推进事件位于真实 NAV 路线上。
安全屋要求所有仍在战斗的队员到齐，不能留下倒地队友；等待 8 秒后清理感染者、恢复队伍并进入下一章。
终章完成 60 秒、3 波坚守，实际生成并击败 Tank，队伍回到撤离范围才判定胜利。

致命伤进入倒地，附近队友蹲下进行 3 秒救援；队伍全倒地结束本局。
进入时保存原背包、经验、食物/效果、模式和位置，发放临时铁剑、弓、盾、箭与补给。
通关、团灭、退出、断线和异常遗留登录均有恢复逻辑。临时装备不能丢弃。
本版未接入 TACZ 枪械配装，不修改玩家的永久装备。

刷怪、Director、跳关等手动调试命令仅供管理员。原有 metadata-only 样例已禁用，文件保留用于回归，不能作为正式可玩地图。

## 构建和验收

```powershell
python build.py --test
python tools/convert_bsp_campaign.py
```

构建使用相邻 `../bmc5server/libraries` 中已有的库。`--test` 真实执行 Python 单元测试和可执行 Java Director 回归，不是被忽略的参数。

```powershell
python tools/qa_server.py --heap 1024
# 其他终端：
node tools/qa_bots.cjs
python tools/qa_e2e.py
# 只停止上述隔离实例：
python tools/qa_rcon.py stop
```

QA 固定使用 `127.0.0.1:25683/25684` 和 `build/qa-server` 独立存档，不使用正式服存档。
完整整合包启动检查使用 `--full --heap 3072`，复制现有服务端模组，只在测试副本禁用账户认证、昵称网络查询和 Web 附属服务。
不要把不同模组集合的测试存档互相覆盖，先归档旧实例。

`build/release.json` 记录产物散列；`build/e2e-report.json` 与 `build/full-server-report.json` 的 jarSha256 应与之相同。
完整 Better MC 独立实例检查覆盖启动与 4166 处实际路线方块。
网络端到端使用两个实际建立 Minecraft 协议连接的测试玩家，而非 mock session。
脚本传送至真实事件坐标并用测试命令清敌，验证实体生成、触发器、倒计时、胜负和状态恢复；这不等于人工逐室走图与战斗平衡验收。
干净 NeoForge 图形客户端检查也不等于整个 Better MC 图形客户端的全部模组兼容性验收。

## 改编范围与第三方素材

输入来自用户提供的 `myl4d2addons_wm_lostschool.vpk`。包内 mission 标明作者「未名」，名称「逃离学院」，Workshop 项目 `795271842`。
转换输出 1,999,012 个原版方块、370 份原生结构 NBT。使用 BSP 凸 brush、displacement 地形与 NAV 路线，24 Source 单位 / 方块。
不打包 Source 纹理、音轨或道具模型；原材质名只用于选择 Minecraft 方块。

这不是一比一 Source 引擎移植：静态/动态 MDL 道具尚未逐网格转换；钥匙谜题、机关脚本、演出和救援载具由原生尸潮、安全屋与坚守撤离规则替代。
门洞、楼梯、梯子和主路线净空进行有记录的格子化适配。

MIT 仅适用于自编代码，不授予原作者地图及衍生结构的额外权利。尚未独立确认地图的公开再分发许可，不得把这些结构当作 MIT 公共素材。
本轮仅覆盖用户本地工程、测试和指定安装目录，没有发布 GitHub Release 或 OSS manifest。
