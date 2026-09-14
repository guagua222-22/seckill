# 只读验收：不创建用户、不下单、不预热、不停服务；逐层检查指标、采集和看板表达式。
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
foreach ($port in 19080,19081,19082,19083) {
    $response = Invoke-WebRequest -Uri "http://127.0.0.1:$port/actuator/prometheus" -UseBasicParsing -TimeoutSec 10
    if ($response.Content -notmatch 'jvm_memory_used_bytes') { throw "管理端口 $port 未返回 JVM 指标" }
    Write-Host "PASS 管理端口 $port"
}
# 管理数据只能经管理端口访问；即使服务保留业务端口，也不能暴露 prometheus。
foreach ($port in 8080,8081,8082,8083) {
    try {
        $businessResponse = Invoke-WebRequest -Uri "http://127.0.0.1:$port/actuator/prometheus" -UseBasicParsing -TimeoutSec 10
    } catch {
        if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 404) { throw }
        continue
    }
    # 项目全局异常处理器会把资源不存在包装为 HTTP 200 + code=500，不能误判成指标泄露。
    if ($businessResponse.Content -match '(?m)^# (HELP|TYPE) |(?m)^jvm_memory_used_bytes') {
        throw "业务端口 $port 泄露了 Prometheus 指标"
    }
    $body = $businessResponse.Content | ConvertFrom-Json
    if ($null -eq $body.code -or $body.code -eq 0) { throw "业务端口 $port 返回了未预期的内容" }
}
$targets = (Invoke-RestMethod 'http://localhost:9090/api/v1/targets' -TimeoutSec 10).data.activeTargets
$serviceTargets = @($targets | Where-Object { $_.labels.job -eq 'seckill-services' })
if ($serviceTargets.Count -lt 4) { throw 'Prometheus 发现的业务目标少于 4 个' }
foreach ($target in $serviceTargets) {
    if ($target.health -ne 'up') { throw "目标 DOWN：$($target.scrapeUrl)；$($target.lastError)" }
    Write-Host "PASS 抓取 $($target.labels.application) / $($target.labels.instance)"
}
$envLine = Get-Content -LiteralPath (Join-Path $projectRoot '.env.monitoring') | Where-Object { $_ -match '^GRAFANA_ADMIN_PASSWORD=' } | Select-Object -First 1
if (-not $envLine) { throw '.env.monitoring 中没有管理员密码' }
$password = $envLine.Substring('GRAFANA_ADMIN_PASSWORD='.Length)
$auth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("admin:$password"))
$headers = @{Authorization="Basic $auth"}
$dashboard = (Invoke-RestMethod 'http://localhost:3000/api/dashboards/uid/seckill-m7' -Headers $headers -TimeoutSec 10).dashboard
$dsHealth = Invoke-RestMethod 'http://localhost:3000/api/datasources/uid/seckill-prometheus/health' -Headers $headers -TimeoutSec 10
if ($dsHealth.status -ne 'OK') { throw 'Grafana 数据源检查失败' }
Write-Host "PASS Grafana 数据源及看板（$($dashboard.panels.Count) 个面板）"
$queryCount = 0
foreach ($panel in $dashboard.panels) {
    foreach ($target in $panel.targets) {
        # 使用 All 变量与 1 分钟窗口，校验已导入看板中的实际表达式；空业务曲线并不等于查询失败。
        $query = $target.expr.Replace('$application','.*').Replace('$instance','.*').Replace('$activity','.*').Replace('$__rate_interval','1m')
        $queryResult = Invoke-RestMethod ("http://localhost:9090/api/v1/query?query=" + [Uri]::EscapeDataString($query)) -TimeoutSec 10
        if ($queryResult.status -ne 'success') { throw "PromQL 失败：$($panel.title)" }
        $queryCount++
    }
}
$rules = (Invoke-RestMethod 'http://localhost:9090/api/v1/rules' -TimeoutSec 10).data.groups
$group = @($rules | Where-Object { $_.name -eq 'seckill-m7' })
if ($group.Count -ne 1 -or $group[0].rules.Count -ne 5) { throw 'M7 告警组未完整加载' }
if (@($group[0].rules | Where-Object { $_.health -ne 'ok' }).Count -gt 0) { throw '存在告警规则计算失败' }
Write-Host "PASS $queryCount 条看板查询、5 条告警规则"
Write-Host '验收通过。业务 QPS/RT 曲线需要真实请求和至少两次采样；库存未配置时允许无数据。'
