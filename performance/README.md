# 容量、查询计划与求解规模

原生数据库为 openGauss 6.0.2。机器为 openEuler 22.03 LTS SP4、4 vCPU、客体分配 8 GiB，API Hikari 连接池上限 12。读写使用各自独立的技术指标。

## HTTP 混合负载

在备份恢复后的独立性能数据库中准备合成身份。数据包含 10000 名学生、600 个教学班、100000 条选课，每名学生开始时持有 10 门课。生成器要求以本机私密文件提供一个实验专用 bcrypt 摘要，使用该摘要对应的口令登录合成账号。应用库和个人实验库不参与测量。

```sh
python3 scripts/generate_performance.py --execute --output /tmp/kexu-perf --password-hash-file /private/perf-hash
```

在空的 `course_app_v3_perf_test` 以 owner 执行生成的 `load.sql`，然后以 owner 执行 V3 migrate。运行对照时，来自 V2 固定提交的服务和 V3 分别连接内容、规模、索引相同的独立初始数据库。正式测量期间仅被测的单套三层服务接受测试流量。客户端从课程宿主机访问 Nginx，登录预热与正式样本分开。每客户端顺序循环三次读（目录、学业汇总、选课资格）和一次写（本人原教学班交替退课、重新选课）。均匀负载按确定映射分配教学班；热点负载优先指向 PS000。

```sh
export KEXU_COMPOSE=/private/perf-compose.json
export KEXU_MEASURE_DB=course_app_v3_perf_test
python3 performance/http_capacity.py --profile uniform --origin http://127.0.0.1:18096 --password-file /private/perf-password --output /tmp/uniform.json --audit-command python3 performance/reconcile.py
```

切换 `--profile hot` 和输出文件可运行热点负载。每档预热后独立测三轮，按全部请求计算经验分位数。技术错误与预期业务冲突分别计数；每轮结束独立 SQL 核对席位、同课重复与学分。三轮全部满足读 P95 ≤ 500 ms、写 P95 ≤ 1000 ms、技术错误率 < 0.1%，且对账差异为零才计为达标。闭环并发表示同时工作的客户端数。结果记录成功档、首次失守档以及每档样本量。任何有界试验都只覆盖列出的数据和机器。

## SQL 索引决策

运行 `python3 performance/query_matrix.py` 生成供专库 gsql 执行的 SQL。使用 `gsql -XqAt -v ON_ERROR_STOP=1` 保存 stdout；可用 `python3 performance/query_matrix.py --parse raw.txt` 检查各查询结果摘要与七个有效样本。候选索引只在测量事务内创建，在原索引上评估两项增量。每轮千行状态写入均在保存点回滚。两个候选总计占 7274496 字节，千行更新中位数 98.521 → 113.552 ms，五个读场景均未观察到改善。正式迁移保留现有索引。教务列表另通过批量加载先修将关联查询恒定为一次，由 20 行和 100 行的调用计数用例核验。

[实测执行计划](results/sql-plans.txt) · [结果集、样本与索引大小](results/sql-matrix.json) · [求解规模复现](../backend/PLANNING.md)

## V2 历史单查询实验

# 一项索引实验

在独立数据库 `course_app_v2_perf_test` 装入 1 万名学生、600 个教学班和 10 万条选课记录。在相同数据上查询指定教学班及状态，连接学生、开课、课程和教师表并排序，两种情况下都得到 167 行。测量 SQL 在事务内移除一个非约束索引，再按原定义创建。两阶段各执行一次预热及七次正式测量。

| 条件 | 七次执行时间（毫秒） | 中位数（毫秒） |
|---|---|---:|
| 无教学班/状态索引 | 10.081、9.711、9.168、10.026、9.240、9.550、9.580 | 9.580 |
| 重建该索引后 | 2.062、1.878、2.182、1.938、1.675、1.533、1.637 | 1.878 |

这是指定查询在课程 openGauss 环境中测得的计划执行时间。无索引时需要扫描更多记录；重建后计划包含 Bitmap Index Scan。这些数据用于解释本查询的优化效果。

原始 SQL 保存在 [measure.sql](measure.sql)。该历史样本仅描述一个班级名单查询。
