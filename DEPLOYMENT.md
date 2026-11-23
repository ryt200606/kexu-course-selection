# 部署与停启

在 openEuler 22.03 LTS SP4、linux/amd64 环境使用 Docker Engine 和 Compose v2。先在仓库根目录完成后端、前端构建。教学环境使用 8 GiB 虚拟机，数据库容器限制 4 GiB、API 1 GiB、Nginx 128 MiB。校园网络的电脑可使用 SSH 转发连接来访，默认只绑定客体本机 18095 端口。

## 准备本地镜像

课程已部署的 openGauss 6.0.2 镜像可直接复用。新环境从课程合法介质取得 `openGauss-Docker-6.0.2-x86_64.tar`，Linux 下依次执行以下步骤。镜像文件约 1.6 GB，单独分发；仓库提供应用和容器配置。初始化入口使用镜像中已有的 `omm`、`gosu`、`gs_initdb` 及 `gsql`。

```sh
set -eu
archive=/path/to/openGauss-Docker-6.0.2-x86_64.tar
printf '%s  %s\n' 1d2c6311eab6853411513df837faaf6d55b37a51d080c3a9805331cac2906357 "$archive" | sha256sum -c -
docker load -i "$archive"
test "$(docker image inspect -f '{{.Id}}' opengauss:6.0.2)" = sha256:a93dae2ccb08901fcff9efd5b54f729eb99e10af7e5156510c1ebbc77b6390e3
docker build --pull=false -t kexu-db:6.0.2 - <<'GAUSS_DOCKERFILE'
FROM opengauss:6.0.2
ENV GAUSSHOME=/usr/local/opengauss PATH=/usr/local/opengauss/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin LD_LIBRARY_PATH=/usr/local/opengauss/lib
GAUSS_DOCKERFILE
docker run --rm --user omm --entrypoint gaussdb kexu-db:6.0.2 --version
docker build -f deploy/Dockerfile.api-prebuilt -t kexu-api:2.0.0 .
nginx_image=nginx@sha256:0dcc88822d45581e65ae329f8be769762bf628d3b2bb7d2a077d4aa5c98b30e3
docker pull "$nginx_image"
```

API 镜像在上述步骤中封装已通过 Maven 验证的 JAR。若希望在镜像内编译，改用 `deploy/Dockerfile.api`。前端 dist 按只读方式挂载至 Nginx。首次获取 JRE、Nginx、前后端构建依赖时需要网络或已有离线缓存。

## 初始化与启动

在与 Docker 相同的 Linux 主机上、仓库根目录执行。先用 `ip route` 与已有 Docker 网络核对 `172.30.134.0/28` 和 `172.30.135.0/28` 可用。准备脚本需 root 身份设置私密文件所属用户。以下示例用新的运行目录；再次访问时按下一节复用已有配置和卷。

```sh
sudo install -d -m 700 /opt/kexu
run_dir=/opt/kexu/run01
db_id=$(docker image inspect -f '{{.Id}}' kexu-db:6.0.2)
api_id=$(docker image inspect -f '{{.Id}}' kexu-api:2.0.0)
web_id=$(docker image inspect -f '{{.Id}}' "$nginx_image")
printf '{"database":"%s","api":"%s","frontend":"%s"}\n' "$db_id" "$api_id" "$web_id" | sudo tee /opt/kexu/images.json >/dev/null
sudo python3 scripts/prepare_deploy.py --execute --output "$run_dir" --images /opt/kexu/images.json --final-runtime deploy/final-runtime --network-reviewed
course_compose() { sudo docker compose -f "$run_dir/compose.json" "$@"; }
course_compose up -d --wait database
course_compose --profile init run --rm migration
course_compose up -d --wait api frontend
curl --fail http://127.0.0.1:18095/api/v2/health
```

数据库、API、Nginx 是三个常驻服务。一次性的 migration 使用 owner 建表及导入虚构教学数据，完成后退出；日常 API 账户只拥有应用所需的读取与修改权限。数据库未发布宿主端口。首次成功初始化后，用浏览器打开 `http://127.0.0.1:18095`。演示教务、教师、学生分别使用用户名 `admin`、`teacher`、`student`，初始口令由准备脚本随机生成，在运行目录的 `private/bootstrap_<角色>_password` 由管理员本机读取。

## 保留数据停启

```sh
course_compose stop -t 180
course_compose up -d --wait database api frontend
curl --fail http://127.0.0.1:18095/api/v2/health
```

再次启动使用同一份 `compose.json`、独立卷及实例标记，跳过首次 migration。初始化中断时检查 `docker compose logs` 和私密运行目录中的初始化日志；未知或部分初始化的卷由入口保留，以便检查。
