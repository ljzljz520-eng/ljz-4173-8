# 调度员黑启动培训复盘系统（仿真桌面端）

> 本系统用于调度员黑启动培训复盘：梳理**电源启动 → 母线充电 → 线路送电 → 负荷恢复**的先后关系。
> 所有电网对象与响应均来自**内置教学仿真器**，**不连接任何真实电网控制系统**。

## 1. 技术栈

| 组成 | 选型 |
|---|---|
| 桌面端 | JavaFX 21（Controls + WebView），JDK 17 可运行 |
| 仿真事件接入 | Akka Streams 2.6（SourceQueue + 自研乱序重排 GraphStage） |
| 会话存储 | SQLite（sqlite-jdbc），事件/口令只追加 + SHA-256 哈希链 |
| 频率/电压曲线 | ECharts 5（WebView，CDN 加载，离线有降级提示） |
| 构建/测试 | Maven、JUnit 5 |

## 2. 构建与运行

```bash
mvn test           # 全部 22 个用例（含 4 项交付测试）
mvn javafx:run     # 启动桌面端（或 ./run.sh）
mvn package        # 打包
```

- Linux aarch64 上 JavaFX 需 ≥ 21.0.12（pom 已固定，跨平台由 openjfx 自动选择本地库）。
- 数据库默认 `~/.bstraining/training.db`。
- ECharts 通过 CDN 加载；离线时曲线页给出提示，训练/复盘不受影响（可把 `echarts.min.js`
  放到 `src/main/resources/web/` 改为本地引用）。

## 3. 培训与复盘的设计原则

1. **场景冻结**：网架、可用机组、启动电源（黑启动机组/外部厂用电）、保护限制、隐藏故障
   在会话开始时深拷贝存档（sessions.frozen_scenario_json），场景库事后修改不影响历史会话。
2. **口令按序记录**：学员口令（含发令/复诵/汇报/联系）严格按提交序号记录；
   前、后潮流快照与口令一一对应。
3. **规则引擎只标候选，不做裁判**。仅产生三类候选：
   - `PRECONDITION` 越过前置条件（如母线无电先恢复负荷、两侧无电先送线、未同期强合、非黑启动机组无厂用电启动）；
   - `FREQUENCY_VOLTAGE` 频压越界（口令后快照逐岛/逐母线核对冻结的保护门槛）；
   - `COMMUNICATION` 通信遗漏（状态变更口令前后窗口内无复诵/汇报/联系）。
   **教员结合系统响应（事件流/曲线/快照）人工确认或驳回候选并签署评价。**
4. **原事件不可编辑**：`events`、`command_records` 表由 SQLite 触发器禁止 UPDATE/DELETE；
   每条事件带 SHA-256 哈希链，复盘页一键校验，任何字段改动都会断链。
5. **复盘可定位任一口令前后的潮流快照**（直接读取冻结存档）；另提供“从冻结场景 + 事件流重放”
   与存档交叉校验，原事件不参与重算、不会被修改。

## 4. 仿真器模型（教学级简化，非真实 EMS/SCADA）

- **拓扑**：闭合线路 + 带电母线构成电气岛（连通分量），岛内有运行机组即带电；
- **频率**：`f = 50 + (Pgen − Pload) × 调差`，节拍内按惯量向准稳态收敛；低于定值触发**低频减载**；
- **电压**：以机组母线为 1.0 p.u.，沿闭合路径按阻抗×下游负荷估算压降；
- **同期/误并列**：并列点采集频差/压差/角差；普通合闸用更严的硬合角差门槛，
  越限则发 `SYNC_CHECK_FAILED → ISLAND_MERGED → MISPARALLEL_TRIP`（冲击后保护跳闸解列）；
- **隐藏故障**：黑启动机组自启动失败、线路充电即跳，仅在命中操作时暴露；
- 口令类型：`START_GEN / RAISE_GEN / CLOSE_LINE / SYNC_TIE / RESYNC_ALIGN / OPEN_LINE /
  RESTORE_LOAD / DISCONNECT_LOAD / REPORT`。

## 5. Akka 事件链路

```
EmulatedSimulator
   └─ EventTransport
       └─(可选 ShufflingTransport 注入乱序)
          └─ SourceQueue(backpressure)
              └─ ReorderingFlow      // 按 seq 重排；缺口超时跳空并告警；重复 seq 丢弃；支持背压
                  └─ Sink → TrainingEngine.orderedTransport()
                            ├─ SHA-256 哈希链
                            ├─ SQLite 追加（记录 seq 与物理到达次序 arrival_ord）
                            └─ COMMAND_COMPLETE 边界 → 截取“口令后”快照 + 规则评估
```

## 6. 交付测试（`src/test/java/...`）

| 测试类 | 覆盖点 |
|---|---|
| `stream/MessageReorderTest` | **消息乱序**：分块洗牌后重排恢复严格升序；缺口超时跳空不卡死；乱序传输不丢包 |
| `stream/AkkaGatewayIntegrationTest` | 随机延迟乱序经 Akka 全链路落库仍有序、哈希链连续 |
| `sim/IslandMisparallelTest` | **孤岛误并**：双岛频差/角差直接硬合→误并列跳闸；同期调整后 `SYNC_TIE`→成功合并 |
| `sim/ScenarioRestartTest` | **场景重启**：恢复冻结初始全黑状态、线路分位、机组停机；事件流追加不清除；隐藏故障仍在 |
| `sim/SameNameBusTest` | **同名母线**：两条“10kV I段母线”按唯一 id 区分，充北不误充南、负荷恢复不误命中 |
| `sim/DemoWalkthroughSmokeTest` | 标准黑启动顺序全流程 + 乱序操作拒绝 + BC 隐藏故障 |
| `rules/RuleEngineTest` | 三类候选的产生/抑制 |
| `persistence/PersistenceTest` | 触发器禁改禁删、前后快照定位、冻结场景存档、哈希链篡改检测、评价签署 |
| `engine/ReplayServiceTest` | 任一口令前后快照定位、重放与存档一致性、冻结场景来自会话存档 |

## 7. 界面页签

1. **事件流**：仿真器响应（受理/启动/充电/跳闸/同期/低频减载…）与哈希链前缀；
2. **口令与潮流**：口令台（类型/设备/目标母线/口令原文）+ 口令前后母线带电/电压/相角/电气岛表；
3. **频率/电压曲线**：ECharts 双图，带频率 49.8/50.2、低频减载 49.0、电压 0.95/1.07 限值线；
4. **候选问题与教员评价**：候选清单（确认/驳回）+ 教员签署评分；
5. **复盘定位**：选择会话与口令序号查看前/后快照、重放校验、哈希链校验。

## 8. 目录

```
src/main/java/org/bstraining/
├── model/        冻结场景、口令、事件、快照、候选、评价（不可变 record）
├── sim/          SimulatorPort、EmulatedSimulator、场景夹具/加载、JSON、EventTransport
├── stream/       AkkaEventGateway、ReorderingFlow、ShufflingTransport、EventHashChain
├── persistence/  Schema(触发器)、SessionRepository、SQLite 实现
├── rules/        RuleEngine（仅三类候选）
├── engine/       TrainingEngine（编排/快照/时序）、ReplayService（定位/重放/校验）
└── ui/           MainApp、AppController、ChartPane(ECharts)
```
