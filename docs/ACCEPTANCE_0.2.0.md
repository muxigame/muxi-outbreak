# Outbreak 0.2.0 本地验收记录

产物 `muxi-outbreak-0.2.0.jar`，677593 bytes。
SHA-256：`8b311e3b938c4ed55ccaa403379a1b347f31d77ccb07626c7d3f7cec77ae051d`。

真实转换 3 章、1,999,012 方块、370 结构 NBT。
8 项 Python 回归、Java Director 回归通过；真实双协议客户端端到端 45 项断言全部通过。
完整 Better MC 独立实例加载 338 个现有服务端 JAR + Outbreak，4,166 处实际路线方块全部匹配。
干净 NeoForge 图形客户端已进入真实战役并显示建筑、HUD 和临时装备；图形客户端及两个测试服务端均正常退出，exit 0。

已校验并安装到：

- `C:\Users\Administrator\WorkSpace\muxigame\bmc5server\mods\muxi-outbreak-0.2.0.jar`
- `C:\Users\Administrator\WorkSpace\muxigame\bmc5client-repo\game\mods\muxi-outbreak-0.2.0.jar`
- `C:\Users\Administrator\WorkSpace\muxigame\better-mc-remake\pack\source\Better MC Remake [FORGE]\mods\muxi-outbreak-0.2.0.jar`

部署不启停生产服，不上传 GitHub/OSS。下一次正常重启才加载新版。客户端旧0.1.0已备份并替换。

## 证据

`build/acceptance.json` 汇总；`build/conversion-report.json`、`build/e2e-report.json`、`build/full-server-report.json`、`build/graphical-client-report.json`、`build/deployment-report.json` 保存细节和散列；`build/verification-final.log` 是最终完整构建测试日志。

## 边界

网络测试通过管理员传送抵达真实事件坐标，以命令清除感染者，未用强制胜利命令或改倒计时。
这不是全程人工走图与战斗平衡验收。图形客户端测试为干净 NeoForge + Outbreak，不是完整 Better MC 图形端逐模组验收。
静态/动态 MDL 道具和 Source 谜题/演出没有一比一移植；不打包原纹理与音乐。
地图源与衍生物不因自编代码的 MIT 许可自动取得公开再分发权。
