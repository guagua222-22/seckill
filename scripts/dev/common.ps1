# Windows 本机开发启动器的公共逻辑。只管理自己启动并记录身份的 JVM。
$ErrorActionPreference = 'Stop'
$script:DevRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$script:DevRuntime = Join-Path $script:DevRoot 'logs/dev'
$script:DevManifest = Join-Path $script:DevRuntime 'processes.json'
$script:DevServices = @(
    [PSCustomObject]@{Name='user'; Port=8081; ManagementPort=19081; Main='com.seckill.user.UserServiceApplication'},
    [PSCustomObject]@{Name='goods'; Port=8082; ManagementPort=19082; Main='com.seckill.goods.GoodsServiceApplication'},
    [PSCustomObject]@{Name='seckill'; Port=8083; ManagementPort=19083; Main='com.seckill.seckill.SeckillServiceApplication'},
    [PSCustomObject]@{Name='gateway'; Port=8080; ManagementPort=19080; Main='com.seckill.gateway.GatewayApplication'}
)

function Enter-DevLock {
    New-Item -ItemType Directory -Path $script:DevRuntime -Force | Out-Null
    try {
        # 文件锁同时约束启动和停止；另一个终端不能在启动一半时又执行停止。
        return [IO.File]::Open((Join-Path $script:DevRuntime 'launcher.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    } catch { throw '另一个启动/停止命令正在执行，请等待它结束。' }
}

function Read-DevRecords {
    if (Test-Path -LiteralPath $script:DevManifest) {
        # PS 5.1 的 ConvertFrom-Json 会将数组作为单个管道对象输出，先赋值再返回才能展开。
        $parsed = Get-Content -LiteralPath $script:DevManifest -Raw -Encoding UTF8 | ConvertFrom-Json
        return $parsed
    }
    return @()
}

function Save-DevRecords([object[]]$Records) {
    # 同目录临时文件替换，避免意外中断把进程清单写成半个 JSON；不保存密码。
    $json = ConvertTo-Json -InputObject @($Records) -Depth 5
    [IO.File]::WriteAllText("$script:DevManifest.tmp", $json, (New-Object Text.UTF8Encoding $true))
    Move-Item -LiteralPath "$script:DevManifest.tmp" -Destination $script:DevManifest -Force
}

function Test-DevProcessIdentity($Record, $Process) {
    if (-not $Process -or $Process.Name -ne 'java.exe') { return $false }
    if ($Record.Token -notmatch '^[a-f0-9]{32}$') { return $false }
    # 防止 PID 被系统复用，也防止错误清单指向工作区以外的 Java 程序。
    $jarPath = [IO.Path]::GetFullPath([string]$Record.Jar)
    $prefix = [IO.Path]::GetFullPath($script:DevRuntime).TrimEnd('\') + '\'
    if (-not $jarPath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { return $false }
    return $Process.CommandLine.Contains("-Dseckill.launch.id=$($Record.Token)") -and
        $Process.CommandLine.Contains($jarPath)
}

function Stop-DevRecord($Record) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$Record.ProcessId)"
    if (-not $process) { return }
    if (-not (Test-DevProcessIdentity $Record $process)) {
        throw "进程身份不匹配，拒绝停止 PID=$($Record.ProcessId)；不会按端口或 java.exe 批量结束进程。"
    }
    # Windows 的 Stop-Process 是进程终止，不等同于应用优雅停机；仅供本地无在途交易时使用。
    Stop-Process -Id ([int]$Record.ProcessId) -ErrorAction Stop
    Wait-Process -Id ([int]$Record.ProcessId) -Timeout 15 -ErrorAction SilentlyContinue
}

function Get-DevListener([int]$Port) {
    try {
        return @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop |
            Select-Object -ExpandProperty OwningProcess -Unique)
    } catch {
        # 没有监听是正常情况，权限不足/系统查询失败则必须报错，不能误当作端口空闲。
        if ($_.CategoryInfo.Category -eq 'ObjectNotFound') { return @() }
        throw
    }
}

function Test-DevHealth([int]$Port) {
    try {
        $response = Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 2
        return $response.status -eq 'UP'
    } catch { return $false }
}

function Wait-DevHealth([int]$Port, [int]$TimeoutSeconds, $Process = $null) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if ($Process) {
            $Process.Refresh()
            if ($Process.HasExited) { throw "JVM 提前退出（端口 $Port），请查看 logs/dev 中对应日志。" }
        }
        if (Test-DevHealth $Port) { return }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "端口 $Port 健康检查超时（${TimeoutSeconds}s），请查看服务日志。"
}

function Invoke-DevDocker([string[]]$DockerArguments) {
    & docker @DockerArguments
    if ($LASTEXITCODE -ne 0) { throw "Docker 命令失败，退出码 $LASTEXITCODE" }
}
