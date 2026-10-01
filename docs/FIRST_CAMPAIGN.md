# 第一张实际战役：逃离学院

原 Back To School 仅是规划，不是当前输入。当前使用用户提供的 Lost School VPK，包内 mission 作者/Workshop ID 为「未名」/ `795271842`。

| 章节 | BSP | 结构数 |
| --- | --- | ---: |
| 9号宿舍 | lost.bsp | 56 |
| 8号宿舍 | lostschool_2.bsp | 78 |
| 大操场 | lostschool_3.bsp | 236 |

总计 1,999,012 方块。24 Source 单位 / 方块，章节 X 偏移 0、2048、4096。
第三章先解析出生房启用的 trigger_teleport（hammer id 114744），找到实际落地点，再匹配 NAV，不把封闭出生房或错误楼层作为玩家入口。

实际资源位于 `src/main/resources/data/muxi_outbreak`：
`outbreak_maps/lostschool.json`、`outbreak_geometry/lostschool.json`、`structure/lostschool/<chapter>/*.nbt`。

两次安全屋、两处原生尸潮、终章 60 秒 / 3 波 / Tank 门槛；单队 1–4 人。
验收层次、脚本使用传送/清敌的边界与第三方素材声明见根目录 README。
静态/动态 MDL 道具、原谜题和演出不是一比一移植，代码 MIT 不意味着地图衍生物获准公开再分发。
