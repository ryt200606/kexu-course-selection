# 课序 · 学生选课与教学管理系统

**[直接体验三角色交互预览](https://ryt200606.github.io/kexu-course-selection/)**　·　**[运行 openGauss 三层系统](DEPLOYMENT.md)**　·　**[查看实际测量](performance/results/)**

课序 V3（3.0.0）是一项数据库课程设计。在一个学期内，学生比较课表、提交志愿、候补选课并复核成绩，教师管理成绩，教务发布培养方案、课表与分配结果。预览在浏览器内处理虚构资料；实际部署由 Nginx、Spring Boot 与 openGauss 6.0.2 三个常驻服务组成。

| 从这个问题开始 | 源码与证据 |
|---|---|
| 谁获得热门课，为什么？ | [分配规则与接口](backend/PLANNING.md) · [逐级优化模型](backend/src/main/java/edu/ustb/coursev2/AllocationSolver.java) · [独立穷举对照](backend/src/test/java/edu/ustb/coursev2/PlanningSolverTest.java) |
| 如何避免整批课表发布一半？ | [排课求解](backend/src/main/java/edu/ustb/coursev2/ScheduleSolver.java) · [发布事务与输入版本](backend/src/main/java/edu/ustb/coursev2/PlanningService.java) |
| 一次成绩复核会改变哪些数据？ | [四步复核与原子更新](backend/src/main/java/edu/ustb/coursev2/AcademicReviews.java) · [学业进度与成绩测试](backend/src/test/java/edu/ustb/coursev2/AcademicFlowTest.java) |
| 多人争课、重发请求和长队列如何处理？ | [选课事务](backend/src/main/java/edu/ustb/coursev2/EnrollmentService.java) · [持久候补轮转](backend/src/main/java/edu/ustb/coursev2/PromotionWorker.java) · [契约测试](backend/src/test/java/edu/ustb/coursev2/BusinessContractTest.java) |
| 一台课程机到底能跑多大？ | [方法与复现](performance/README.md) · [测量结果](performance/results/) · [真实 SQL 计划](performance/results/sql-plans.txt) |

## 本地构建

环境为 JDK 17、Maven 3.9、Node.js 24 和 npm。Windows 用具备兼容 VC++ 运行库的 JDK 17，详见 [OR-Tools 运行说明](backend/PLANNING.md)。Linux 采用 x86_64。H2 用于主机测试，真实数据库验收另附运行环境和版本。

```sh
(cd backend && mvn -B -ntp verify)
(cd frontend && npm ci && npm test && npm run build)
```

[学业与结算接口](backend/ACADEMIC.md) 使用 `/api/v3`，会话、目录、即时选退课和成绩录入保留 `/api/v2`。写操作使用同源会话及 CSRF 校验。运行时数据库角色、owner 和三角色密码由部署管理员在本地注入。

原有 V2 的源码固定在 [998a4646](https://github.com/ryt200606/kexu-course-selection/tree/998a46461ba9664bbd05cb8373a7900a4a74f599)。教学报告单独提交。
