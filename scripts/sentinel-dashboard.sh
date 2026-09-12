#!/usr/bin/env bash
# Sentinel 控制台（只读观测用）启动脚本。
#
# 为什么不做成 docker 容器：被监控的 4 个应用跑在宿主机（IDEA 启动），Sentinel 客户端
# 在宿主机开 8719+ 传输端口，控制台必须回连这些端口才能拉到实时 QPS/熔断状态。
# 放容器里要跨 Docker Desktop 网络回连宿主机，多一层不确定性；控制台与被监控端同机才一致。
#
# 为什么 jar 不进仓库：22 MB 二进制，且 Maven Central 上没有 com.alibaba.csp:sentinel-dashboard
# （只有无关的 dev.taie 分支），阿里云镜像 404，只能从 GitHub 官方 release 取。
# 按本项目工具约定（JMeter/oha 都在 C:\Soft_Common\）放在仓库外。
#
# 用法：bash scripts/sentinel-dashboard.sh          # 前台启动，Ctrl+C 停
#       bash scripts/sentinel-dashboard.sh --bg     # 后台启动，日志写 /tmp/sentinel-dashboard.log

set -euo pipefail

DASHBOARD_HOME="/c/Soft_Common/sentinel-dashboard"
JAR="$DASHBOARD_HOME/sentinel-dashboard-1.8.6.jar"
VERSION="1.8.6"
PORT=8858
# 官方 release 的 sha256，防止下到半个文件或被替换
EXPECT_SHA256="e7331c6f900b3568638ec43acb4e1fe124618d025027491720e032c9af50035c"
DOWNLOAD_URL="https://github.com/alibaba/Sentinel/releases/download/${VERSION}/sentinel-dashboard-${VERSION}.jar"

if [[ ! -f "$JAR" ]]; then
    cat <<EOF
[缺失] 没找到 $JAR

GitHub 官方 release 需要代理才能下（本机代理 127.0.0.1:7892）：

  mkdir -p "$DASHBOARD_HOME"
  curl -L -x http://127.0.0.1:7892 -o "$JAR" "$DOWNLOAD_URL"

下完这个脚本会自动校验 sha256。
EOF
    exit 1
fi

ACTUAL_SHA256="$(sha256sum "$JAR" | awk '{print $1}')"
if [[ "$ACTUAL_SHA256" != "$EXPECT_SHA256" ]]; then
    echo "[校验失败] sha256 不匹配，jar 可能下载不完整"
    echo "  期望: $EXPECT_SHA256"
    echo "  实际: $ACTUAL_SHA256"
    exit 1
fi

# 端口被占说明控制台已经在跑，别再起一个（Sentinel 客户端只会往 8858 发心跳）
if netstat -ano | grep "LISTENING" | grep -q ":$PORT "; then
    echo "[跳过] $PORT 已在监听，控制台可能已经启动：http://localhost:$PORT"
    exit 0
fi

JAVA_OPTS=(
    # 只做观测，不给它多大堆；WSL2 内存预算里已经住着 MySQL/Redis/RocketMQ/Nacos
    -Xms128m -Xmx256m
    -Dserver.port="$PORT"
    # 控制台自己也作为一个应用注册进去，便于确认链路通
    -Dcsp.sentinel.dashboard.server="localhost:$PORT"
    -Dproject.name=sentinel-dashboard
)

echo "启动 Sentinel 控制台：http://localhost:$PORT （账号/密码 sentinel/sentinel）"
echo "规则以代码为准（SentinelRuleConfig / SentinelGatewayConfig），控制台只读观测，不要在里面改规则。"

if [[ "${1:-}" == "--bg" ]]; then
    nohup java "${JAVA_OPTS[@]}" -jar "$JAR" > /tmp/sentinel-dashboard.log 2>&1 &
    echo "后台 PID $!，日志 /tmp/sentinel-dashboard.log"
else
    exec java "${JAVA_OPTS[@]}" -jar "$JAR"
fi
