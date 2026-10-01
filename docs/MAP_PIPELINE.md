# 可复现的地图转换链

用户 VPK → BSP/NAV/mission → L4D2 v21 分支解析 → solid brush 凸平面体素化 + displacement 三角面采样 → NAV 有向路线/梯子连接 → 格子化净空适配 → 32×32×32 分块原生 NBT → JSON/SHA256 清单 → 独立维度分批安装 → 原生战役。

工具入口 `python tools/convert_bsp_campaign.py`。输入默认 `maps/workshop/lostschool_extracted/maps`。
必须使用 `bsp_tool.branches.valve.left4dead2`；SDK2013 自动识别会误读本素材 lump header，把文件头当 entity 数据，不能据此宣布几何解析成功。

坐标变换 `(sx/24 + chapter*2048, sz/24 + 80, -sy/24)`。
NAV16/sub14 严格检查版本、长度、重复 ID、缺失边和剩余字节；没有有向路径就失败，不能画直线当通路。
运行描述包含 geometry、chapters、panicEvents、finale 及 start/finish/safeRooms/commonSpawns/hordeSpawns/bossSpawns/itemSpawns。
刷怪只使用已列出的候选点，由 Minecraft 再检查地面、流体、碰撞和玩家距离，不用随机坐标掩盖失败。

每章转换报告记录输入散列、NAV 数量和路线 ID、方块统计与格子化适配数量；MDL 道具未转换是明确限制，不计入已移植。
原始 VPK/BSP/NAV 和解包素材不入 Git。建造数组缓存位于 build，用于独立验证。

单元测试逐份校验 370 份 NBT/SHA/坐标及超过百万方块的真实结构，检查路线净空和支撑。
完整整合包运行检查先按 chunk 临时 forceload，再检查全部路线脚下/脚部/头部方块，最后解除票据。
未加载区块的命令报错不等于地图为空，也不能跳过该错误声称通过。
