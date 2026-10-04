# F 场景交互修复与基础验收（2026-10-04）

1.4.31 的服务端 `OutbreakGame.interact()` 只执行补给拾取，未处理门。使用已发布 Outbreak SHA-256 `6110966386b541f272226a70a9cbbdb9c085c649056d27b3972d5c3d0d26b2e1`、Core `ad96fab54e71f52db4fa2018de14974b94f46c86366d0347839fb68b95f9a8f1` 和框架 `75aead1ee9f55fafeac46cb394c2e3b759737befc8cee1a6efb7852e114342b7`，真实客户端原生 KeyboardHandler 已复现：F 发出一次请求且 TaCZ 点击为 0，普通门和起始安全屋门保持关闭。本地同名 Core build JAR 与发布哈希不同，验收使用匹配发布清单的既有缓存，没有替换或重建 Core。

## 修复行为

- F 在 3.5 格视线范围选择一个目标，优先处理射线命中位置之前的补给；挡在补给前的门先操作，下一次 F 才可拾取门后的补给。
- 起始门及安全屋门使用原有整组门和阶段规则；锁定的后续门不会回落到普通门逻辑。普通木门/铁门使用原生 `DoorBlock.setOpen`，保持上下半部及原生声音同步。
- 活板门、栅栏门、按钮、拉杆调用原生 block-only use。F 不调用手持 item use，不触发枪械、医疗品或放置物品；Core 原有上下文接管和按键队列屏蔽未修改。
- 枪械拾取/交换、近战、医疗包、药丸、肾上腺素、除颤器、高爆弹包、手雷、土制炸弹、胆汁瓶、弹药堆和高爆弹站复用已有库存和数量规则。
- 服务端验证成员、准备状态、存活/倒地、维度、阶段、距离和遮挡，同 tick 重复请求只操作一次。Q/Ctrl+Q、治疗/投掷、蹲下救援保持原操作；没有调整刷怪、武器或医疗平衡。
- 战役仍封闭外部容器；箱子/熔炉等装饰不是补给节点。床保持装饰，不调用会在该维度引爆床的原生 use。原有右键兼容路径保留，场景操作无需依赖右键。

## 验收结果

修复候选 `0.4.1-interaction.1`，JAR SHA-256 `4b37b550a517f4c199497b069e95d61a1446b5ac4b49af124e36ce10cf474465`。

使用隔离服务端及两个真实客户端、默认隐藏窗口、独立端口 25941。最终基础互动共 **102 项断言通过**，包括门组开关与阶段锁、普通门、开关、持枪开门时枪械完整组件不变、每种补给、重复键事件只发一个请求、同 tick 重复操作、距离/墙/倒地拒绝、门前补给择一，以及原生 Q/Ctrl+Q。两个客户端和服务端均正常 exit 0；旧版复现也全部正常 exit 0。

`python -B build.py --test`：24 项 Python 测试（23 通过、1 因私有 VPK 未安装而跳过），导演回归通过，补给规则 3110 断言通过。

回执是原生 `KeyboardHandler.keyPress` 和真实网络路径，测试站位、库存节点和无导演环境为隔离世界辅助夹具。没有 OS 物理键/视觉/SSO 验收，没有进行长章节通关，没有部署008。失败的 QA 加载/快照解析/站位/外部容器测试记录保留，未把仅启动或退出 0 当成互动通过。

## 复跑

复用父目录 `better-mc-remake/scripts/local_mc_debug.py`。工具只写新 Lab，不改共享 Core/Terminal，不复制凭据。为保持本次部署回归可核对，脚本要求 `--core` 的 SHA-1 匹配 1.4.31 清单；用已安装 Java 21 JDK、服务端/客户端资源、LR 0.4.3 和已知 QA 世界副本运行：

```powershell
python -B tools/run_f_interaction_qa.py `
  --outbreak <本仓> --project <better-mc-remake仓> `
  --server <已安装NeoForge服务端> --game <已安装客户端game> `
  --java-home <Java21-JDK> --core <匹配发布SHA的Core.jar> `
  --framework <muxi-minigames-0.2.1-equipment.1.jar> `
  --artifact <muxi-outbreak-0.4.1-interaction.1.jar> --lr <LR-0.4.3.jar> `
  --world <既有隔离测试世界> `
  --instance-root <better-mc-remake仓>/build/local-mc-debug/<全新独立名字> `
  --port <独立空闲端口> --unix-temp <可写短临时目录>
```

指定 1.4.31 Outbreak 成品并添加 `--legacy-repro` 可重现旧版 F 不开门。每次新目录；所有普通方块及补给夹具都只在复制后的隔离世界中创建。最终判断读取 `f-interaction-result.json.passed` 及 `run-result.json` 的完整退出码，不能只看通用 runner 的 hold 连接结果。证据目录和输入摘要见 [验收 JSON](f-interaction-acceptance-20261004.json)。

## 并行修改边界

未修改 Core/Terminal/Minigames 源码。`OutbreakGame.java` 原有未提交的停止事件 `EventPriority.HIGHEST` 行保留在工作树并被候选构建使用，未纳入本次提交；其原始备份及单独 patch 保存于会话交接记录。该行属于预先存在的 dirty，候选二进制不能被误称为只由本次提交无 dirty 构建。所有已发布同名旧 JAR 保留，本候选使用新文件名，不部署生产。
