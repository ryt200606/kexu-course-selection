# 课序 · 学生选课与教学管理系统

课序 V2 2.0.0 是面向学生、教师和教务的三层 Web 应用。学生查询开课、调整课表和查看已发布成绩，教师维护本人教学班成绩，教务配置轮次与课程并查看审计。演示数据为虚构师生和课程。

## 从一次选课读懂三层

1. 展示层由 Nginx 托管 Vue 3、TypeScript 页面。打开[学生课程目录](frontend/src/views/CatalogView.vue)可以看到筛选、12 条分页、详情弹窗与提交按钮。[Nginx 配置](deploy/nginx.conf)将同源的 `/api/` 请求转发给业务容器。
2. 业务层由 Java 17、Spring Boot 3.5.16、Spring Security 与 JDBC 实现。[控制器](backend/src/main/java/edu/ustb/coursev2/ApiController.java)接收已登录学生的选课请求，[选课事务](backend/src/main/java/edu/ustb/coursev2/EnrollmentService.java)重新检查轮次、先修、时间、容量和学分。成功后选课记录、人数、学分及通知在同一事务提交。
3. 数据层为 openGauss 6.0.2。[结构定义](backend/src/main/resources/db/schema.sql)给出 17 张表、3 个视图、完整性约束和索引。开课与具体教学班分别建模，学生选课同时受业务资格与数据库约束保护。

| 关心的问题 | 源码入口 |
|---|---|
| 同一请求重试与重复选课 | [幂等记录与 savepoint](backend/src/main/java/edu/ustb/coursev2/Idempotency.java) |
| 换课失败如何保留原课，候补如何按队列补位 | [选退换与候补事务](backend/src/main/java/edu/ustb/coursev2/EnrollmentService.java)、[后台续接](backend/src/main/java/edu/ustb/coursev2/PromotionWorker.java) |
| 草稿暂空和正式成绩可见性 | [成绩服务](backend/src/main/java/edu/ustb/coursev2/GradeService.java)、[教师工作台](frontend/src/views/TeacherView.vue) |
| 学生、教师、教务能读取哪些资源 | [接口契约](backend/API-CONTRACT.md)、[会话安全](backend/src/main/java/edu/ustb/coursev2/SecurityConfig.java) |
| 并发、回滚与权限如何验证 | [后端契约测试](backend/src/test/java/edu/ustb/coursev2/BusinessContractTest.java)、[HTTP 验证](scripts/http_acceptance.py) |
| 某个索引在此次数据上改变了什么 | [实测 SQL](performance/measure.sql)、[样本与条件](performance/README.md) |

## 本地构建与测试

准备 JDK 17、Maven 3.9 及 Node.js 24、npm。在仓库根目录分别进入两个子目录执行。Windows 可使用普通工作目录和 `npm.cmd`。

```sh
cd backend
mvn -B -ntp verify
cd ../frontend
npm ci
npm test
npm run build
```

Maven 的默认测试使用 H2，真实 openGauss 时钟用例在该模式下跳过。前端生产构建执行 TypeScript 类型检查，输出 `frontend/dist/`。部署到教学 openEuler 环境的三容器步骤见[部署与停启](DEPLOYMENT.md)。

课程验收在独立 openGauss 6.0.2 测试库执行 30 项原生契约检查，并在实际服务上完成 25 项 HTTP 检查。另有 23 项前端单测；这几组结果来自不同层次，不能合并成数据库测试数量。

## 项目分工

数据库与核心事务、总体设计和报告统稿占 40%；三角色前端、权限与成绩业务及联调占 40%；Docker 部署、测试复现与报告校核占 20%。教师提交版报告另列成员信息。
