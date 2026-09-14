# 只启动 M7 监控栈，首次生成本地随机管理员密码；不会改动业务数据或重启业务服务。
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $projectRoot
try {
    if (-not (Test-Path -LiteralPath '.env.monitoring')) {
        $passwordBytes = New-Object byte[] 24
        $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $generator.GetBytes($passwordBytes) } finally { $generator.Dispose() }
        $generatedPassword = [Convert]::ToBase64String($passwordBytes)
        [System.IO.File]::WriteAllText((Join-Path $projectRoot '.env.monitoring'), "GRAFANA_ADMIN_PASSWORD=$generatedPassword`n")
    }
    docker compose --env-file .env.monitoring -f docker-compose.monitoring.yml up -d
    if ($LASTEXITCODE -ne 0) { throw '监控容器启动失败，请检查上方 Docker 错误。' }
    Write-Host 'Prometheus: http://localhost:9090'
    Write-Host 'Grafana: http://localhost:3000/d/seckill-m7 （账号 admin，密码见 .env.monitoring）'
    Write-Host '接下来按照 docs/M7-monitoring.md 使用 monitoring profile 启动业务服务。'
} finally {
    Pop-Location
}
