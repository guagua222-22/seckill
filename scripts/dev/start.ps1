<#
一键启动本机学习环境：现有中间件 → 幂等建三库 → 构建 → 四个 JVM → 可选监控。
默认不造业务数据、不跑下单压测；已运行的项目服务只检查和复用，绝不强行抢占端口。
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$WithoutMonitoring,
    [string]$ActivityIds = '',
    [ValidateRange(10,600)][int]$TimeoutSeconds = 120
)
. (Join-Path $PSScriptRoot 'common.ps1')
$lock = Enter-DevLock
$startedHere = @()
Push-Location $script:DevRoot
try {
    if ($ActivityIds -and $ActivityIds -notmatch '^\d+(,\d+)*$') { throw 'ActivityIds 只允许逗号分隔的正整数。' }
    $ids = @($ActivityIds.Split(',') | Where-Object { $_ } | Select-Object -Unique)
    if ($ids.Count -gt 32 -or @($ids | Where-Object { [long]$_ -le 0 }).Count -gt 0) {
        throw '库存监控最多允许 32 个正整数活动 ID。'
    }
    $javaCommand = Get-Command java -ErrorAction Stop
    # 使用当前 PATH 的 Java，避免把开发者机器上的绝对 JDK 路径写死在仓库。
    $javaVersion = (& $javaCommand.Source --version | Out-String)
    if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch '\b21[.\s]') { throw '请将 JDK 21 配置到 PATH。' }
    Get-Command docker -ErrorAction Stop | Out-Null
    Invoke-DevDocker @('info','--format','{{.ServerVersion}}')

    # 先检查冲突，避免为了最后才发现端口占用而先启动一批新进程。
    $reuse = @{}
    $records = @(Read-DevRecords)
    foreach ($service in $script:DevServices) {
        $owners = @(Get-DevListener $service.Port)
        if ($owners.Count -gt 0) {
            if ($owners.Count -ne 1) { throw "端口 $($service.Port) 有多个监听进程。" }
            $existing = Get-CimInstance Win32_Process -Filter "ProcessId=$($owners[0])"
            $isProject = $existing.Name -eq 'java.exe' -and $existing.CommandLine.Contains($script:DevRoot) -and
                ($existing.CommandLine.Contains($service.Main) -or $existing.CommandLine -match "seckill-$($service.Name)-[^\s]+\.jar")
            if (-not $isProject) { throw "端口 $($service.Port) 被其他程序占用，请自行处理；脚本不会停止它。" }
            $reuse[$service.Name] = $true
        } elseif (@(Get-DevListener $service.ManagementPort).Count -gt 0) {
            throw "管理端口 $($service.ManagementPort) 已被占用，请检查现有进程。"
        }
    }

    # 两个 Compose 项目沿用 M7 的名字，不创建第三个容器组，不 down、不删卷。
    # 固定项目名，已有容器即使配置变化也不自动重建；升级配置应单独安排维护。
    Invoke-DevDocker @('compose','-p','seckill','-f','docker-compose.yml','up','-d','--no-recreate','--wait','--wait-timeout',"$TimeoutSeconds")
    # SQL 只含 CREATE DATABASE IF NOT EXISTS；Flyway 各自管理表，不执行旧库迁移或 DROP。
    $databaseSql = Get-Content scripts/db/init-databases.sql -Raw -Encoding UTF8
    $databaseSql | & docker compose -p seckill -f docker-compose.yml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot'
    if ($LASTEXITCODE -ne 0) { throw '建库失败，请核对本地 MySQL 配置。' }

    if (-not $SkipBuild) {
        Write-Host '构建全部模块（启动命令不跑测试；完整验证请单独运行 mvnw verify）...'
        & ./mvnw.cmd -B -ntp '-DskipTests' package
        if ($LASTEXITCODE -ne 0) { throw 'Maven 构建失败，未启动新的 JVM。' }
    }
    foreach ($service in $script:DevServices) {
        if ($reuse.ContainsKey($service.Name)) {
            # 独立 management.port 启用后 health 在管理端口，业务端口可能返回 404。
            $healthPort = if (Test-DevHealth $service.ManagementPort) { $service.ManagementPort } else { $service.Port }
            Wait-DevHealth $healthPort $TimeoutSeconds
            Write-Host "复用 $($service.Name) :$($service.Port)；不会热加载本次构建或改变现有启动参数。"
            if (-not $WithoutMonitoring -and -not (Test-DevHealth $service.ManagementPort)) {
                Write-Warning "现有 $($service.Name) 未启用管理端口，请在原启动配置添加 monitoring profile 后重启。"
            }
            if ($service.Name -eq 'seckill' -and $ActivityIds) {
                Write-Warning 'ActivityIds 只用于新启动的 JVM；已有秒杀进程请在原启动配置中设置。'
            }
            continue
        }
        $jar = Get-ChildItem -LiteralPath "seckill-$($service.Name)/target" -Filter "seckill-$($service.Name)-*.jar" |
            Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' }
        if (@($jar).Count -ne 1) { throw "找不到唯一的 $($service.Name) 可执行 jar，请先构建。" }
        # 每轮新目录：避免 Windows 锁住运行中的 jar，使后续 Maven 构建无法覆盖 target。
        $token = [Guid]::NewGuid().ToString('N')
        $runDir = Join-Path $script:DevRuntime "$($service.Name)-$token"
        New-Item -ItemType Directory -Path $runDir | Out-Null
        $runJar = Join-Path $runDir $jar.Name
        Copy-Item -LiteralPath $jar.FullName -Destination $runJar
        $arguments = @('-Xms128m','-Xmx512m',"-Dseckill.launch.id=$token",'-jar',('"{0}"' -f $runJar))
        # 防止终端遗留 SEED_USER_COUNT 导致日常启动删除/重建测试用户；造数必须单独执行。
        if ($service.Name -eq 'user') { $arguments += '--seckill.seed.user-count=0' }
        if (-not $WithoutMonitoring) { $arguments += '--spring.profiles.active=monitoring' }
        if ($service.Name -eq 'seckill' -and $ActivityIds) { $arguments += "--seckill.monitoring.activity-ids=$ActivityIds" }
        Write-Host "启动 $($service.Name) :$($service.Port)..."
        $child = Start-Process -FilePath $javaCommand.Source -ArgumentList $arguments -WorkingDirectory $script:DevRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDir 'stdout.log') -RedirectStandardError (Join-Path $runDir 'stderr.log')
        $record = [PSCustomObject]@{Name=$service.Name;ProcessId=$child.Id;Token=$token;Jar=$runJar;Port=$service.Port}
        $startedHere += $record
        $records = @($records | Where-Object { $_.Name -ne $service.Name }) + @($record)
        Save-DevRecords $records
        $healthPort = if ($WithoutMonitoring) { $service.Port } else { $service.ManagementPort }
        Wait-DevHealth $healthPort $TimeoutSeconds $child
    }
    if (-not $WithoutMonitoring) {
        & (Join-Path $script:DevRoot 'scripts/monitoring/start.ps1') -NoRecreate
    }
    Write-Host '启动完成：验证台 http://localhost:8080/；状态检查 scripts/dev/status.ps1'
    Write-Host '库存监控未传 ActivityIds 时不采集活动；已有进程的配置保持原样。'
} catch {
    # 只清理本次新建的 JVM；IDEA 和以前已运行的服务、中间件及卷全部保留。
    foreach ($record in $startedHere) {
        try { Stop-DevRecord $record } catch { Write-Warning $_ }
    }
    throw
} finally {
    Pop-Location
    $lock.Dispose()
}
