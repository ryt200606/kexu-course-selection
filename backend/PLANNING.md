# 志愿分配与排课

两种规划均由 API 服务中的 OR-Tools Java 9.14.6206 单线程 CP-SAT 求解。输入来自 openGauss，求解前释放数据库事务。教务保存预览后，只有明确发布才会写入正式选课或课表。

## 志愿分配

1. 教务在草稿期或开放时间到达前为轮次生成随机种子，公布种子的 SHA-256。创建操作重复执行会读回原承诺。学生可以在开放窗口提交、修改或清空最多八个有序志愿，版本号防止覆盖另一标签页的修改。
2. 该轮关闭或填报到期后，第一次预览封存志愿并公开原始种子；即时选课接口只受理普通轮次。一个可重复读快照收集容量、学生学分余额、在读教学班、上课周次与节次、正式先修成绩及候补状态。已有候补优先；同批可以获得多门，不得重复同一门课程。
3. 每个合格志愿对应一个布尔变量。约束限制班级容量、个人剩余学分、同课多班及实际周次和节次重叠。顺序优化覆盖人数、第一至第八志愿命中数，最后按承诺种子派生的志愿优先权之和打破平局。每阶段证明最优后锁定其目标值再进入下一阶段。固定单线程与排序使同一输入可复算。种子加权和仍可能相同，此时使用确定的模型顺序。
4. 固定贪心对照使用同一套可行边。按种子对学生排序，每一轮每人至多获取一门可行的最高志愿，所有人有机会后再进入下一轮。
5. 发布锁定学期、轮次、受影响教学班和学生，重读快照摘要并逐条调用正式选课资格及计数逻辑。选课、学分、分配明细、通知、审计、幂等回执同事务提交。若数据已变化，整批返回 `STALE_PREVIEW`。学生只能看到本人的正式结果和落选解释；解释展示在当前方案下发生的约束，不声称某一原因是所有可能解中的唯一原因。

## 排课

一项排课请求包含若干草稿教学班。每班一至十二个候选，每个候选表示在连续教学周中每周重复的上课时段与教室；可指定必须使用的候选下标。教务维护的教室容量、教师禁排时段和资源版本对全部正式排课生效。已有已发布教学班固定占用，不允许覆盖。缺少正式资源时可查看示例预览。

先最大化排入的班数，再最小化教师空档。空档按每位教师、每个实际教学周和星期计算，从当天首节到末节的未授课节数求和。再比较候选顺序和教室富余座位。排不全时保留部分最优解并标出被禁用的候选。正式发布要求完整且证明最优，发布时在学期锁内核对教室、禁排、已发布占用、教学班和资源版本。

## 接口

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/v3/admin/planning/rounds/{roundId}/commit` | 创建或读取种子承诺 |
| GET / PUT | `/api/v3/student/preferences/{roundId}` | 本人志愿及正式结果，PUT 提交 `{version,sectionIds}` |
| POST | `/api/v3/admin/planning/allocations/preview` | 提交 `{roundId}` 封存与求解 |
| GET / PUT | `/api/v3/admin/planning/resources/{term}` | 维护学期教室与教师禁排资源 |
| POST | `/api/v3/admin/planning/schedules/preview` | 读取学期资源并计算课程安排 |
| GET | `/api/v3/admin/planning/runs`、`/api/v3/admin/planning/runs/{id}` | 分页查看批次或读取详细预览 |
| POST | `/api/v3/admin/planning/runs/{id}/publish` | 提交 `{snapshotHash}` 和 `Idempotency-Key` |

预览状态 `FEASIBLE` 代表有可行方案但未证明全部目标最优。API 保留目标值、求解界、阶段耗时、逐级证明状态和贪心结果。分配单次限时 20 秒，排课三个阶段分别限时 8、5、5 秒。单节点同时最多一个求解。分配最多接受 2,000 条志愿，排课最多 80 个教学班。报告中的课堂性能数字分别注明机器、用例与数据规模。

## 复现

在项目根目录执行以下命令。依赖为 Java 17、Maven 3.9 和 glibc Linux x86-64 或 Windows x64。教师环境使用 openEuler 上的三容器。Windows JDK 如果私带早于 14.40 的 `msvcp140.dll`，本程序会提示 `SOLVER_RUNTIME_UNAVAILABLE`；请选择自带较新 MSVC 运行库的受支持 Java 17 构建，或在课程 Linux 容器运行。JDK 进程优先加载其私带 DLL，仅更新系统 Redist 可能不能更换其实际加载的版本。

```sh
mvn -f backend/pom.xml -Dtest=PlanningSolverTest,PlanningContractTest package
java -Dloader.main=edu.ustb.coursev2.PlanningBenchmark -cp backend/target/course-selection-v3-3.0.0.jar org.springframework.boot.loader.launch.PropertiesLauncher /tmp/planning-benchmark.json
```

基准命令不连接数据库，输出必须是新路径。志愿按 24、96、320 名学生，各五个志愿递增；排课按 12、30、55 班同时提高资源紧张程度。单线程计时包含建模、求解及输入摘要开销，不能当作 HTTP 延迟；限制条件、阶段界和未排全的结果保存在 JSON 中。集成测试默认 H2，`TEST_APP_MODE=serve` 和原生 `TEST_DB_*` 参数用于已迁移的专用 `course_app_v3_test`。

穷举验证在十个固定小样本逐一检查全部候选子集，按覆盖与各志愿命中构成的向量比较最优值。另有可手算反例（贪心覆盖 1 人、最优覆盖 2 人）、四周排课空档从 16 节降至 0 节、资源版本冲突、志愿过期预览、重复发布和本人结果隔离等回归用例。
