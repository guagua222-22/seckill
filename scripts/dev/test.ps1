<# 启动器隔离回归：真实文件锁/清单 + 假 Java/Docker，绝不启动或停止机器上的业务进程。 #>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$script:passed = 0
function Assert-DevTest([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "断言失败：$Message" }
    $script:passed++
}

# Windows PowerShell 5.1 下解析所有开发/监控脚本，能同时发现 BOM 和中文引号问题。
foreach ($file in Get-ChildItem "$projectRoot/scripts/dev","$projectRoot/scripts/monitoring" -Filter '*.ps1') {
    $parseTokens = $null
    $parseErrors = $null
    [Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$parseTokens, [ref]$parseErrors) | Out-Null
    Assert-DevTest ($parseErrors.Count -eq 0) "脚本语法 $($file.Name)：$parseErrors"
}

. (Join-Path $PSScriptRoot 'common.ps1')
# 用含空格路径验证 jar 参数与身份检查；测试产物保留在忽略目录，方便失败时排查。
$fixture = Join-Path $projectRoot ('logs/dev-tests/fixture with spaces ' + [Guid]::NewGuid().ToString('N'))
$script:DevRuntime = Join-Path $fixture 'logs/dev'
$script:DevManifest = Join-Path $script:DevRuntime 'processes.json'
$firstLock = Enter-DevLock
try {
    $blocked = $false
    try { $secondLock = Enter-DevLock; $secondLock.Dispose() } catch { $blocked = $true }
    Assert-DevTest $blocked '并发启动/停止必须互斥'
} finally { $firstLock.Dispose() }
$nextLock = Enter-DevLock
$nextLock.Dispose()
Assert-DevTest $true '释放后可以重新获取锁'

$token = 'a' * 32
$jar = Join-Path $script:DevRuntime 'user/seckill-user-test.jar'
$record = [PSCustomObject]@{Name='user';ProcessId=123;Token=$token;Jar=$jar;Port=8081}
$owned = [PSCustomObject]@{Name='java.exe';CommandLine="java -Dseckill.launch.id=$token -jar `"$jar`""}
Assert-DevTest (Test-DevProcessIdentity $record $owned) '自己启动的含空格路径可以识别'
$stranger = [PSCustomObject]@{Name='java.exe';CommandLine="java -jar `"$jar`""}
Assert-DevTest (-not (Test-DevProcessIdentity $record $stranger)) 'PID 相同但标记缺失不能停止'
$outside = [PSCustomObject]@{Token=$token;Jar=(Join-Path $fixture 'unrelated.jar')}
Assert-DevTest (-not (Test-DevProcessIdentity $outside $owned)) '工作区运行目录之外不能停止'
Assert-DevTest (-not (Test-DevProcessIdentity $record ([PSCustomObject]@{Name='other.exe'}))) '非 Java 进程不能停止'
foreach ($items in @(@(), @($record), @($record,$record))) {
    Save-DevRecords $items
    Assert-DevTest (@(Read-DevRecords).Count -eq $items.Count) "清单往返：$($items.Count) 个记录"
}

# health 必须检查 Actuator 状态，HTTP 200 的普通业务错误不能冒充健康。
& {
    function Invoke-RestMethod { [PSCustomObject]@{code=500} }
    Assert-DevTest (-not (Test-DevHealth 8081)) 'HTTP 成功但业务失败不算健康'
}
& {
    function Invoke-RestMethod { [PSCustomObject]@{status='UP'} }
    Assert-DevTest (Test-DevHealth 19081) '管理端口 UP 可以用于就绪'
}
& {
    function docker { $global:LASTEXITCODE = 7 }
    $failed = $false
    try { Invoke-DevDocker @('info') } catch { $failed = $true }
    finally { $global:LASTEXITCODE = 0 }
    Assert-DevTest $failed '原生命令失败必须中止启动'
}

New-Item -ItemType Directory -Force "$fixture/scripts/dev","$fixture/scripts/monitoring","$fixture/scripts/db" | Out-Null
foreach ($name in @('start','stop','common','status')) {
    Copy-Item -LiteralPath "$PSScriptRoot/$name.ps1" -Destination "$fixture/scripts/dev/$name.ps1"
}
Copy-Item -LiteralPath "$projectRoot/scripts/db/init-databases.sql" -Destination "$fixture/scripts/db/init-databases.sql"
foreach ($name in @('user','goods','seckill','gateway')) {
    New-Item -ItemType Directory -Force "$fixture/seckill-$name/target" | Out-Null
    [IO.File]::WriteAllText("$fixture/seckill-$name/target/seckill-$name-test.jar", 'fixture only')
}
[IO.File]::WriteAllText("$fixture/scripts/monitoring/start.ps1", 'param([switch]$NoRecreate) $global:DevLauncherTest.Monitoring = [bool]$NoRecreate')

# 仅在复制出来的 fixture 公共脚本末尾替换外部边界；实际 start/stop 控制流程原样执行。
$mocks = @'

function Get-Command { param($Name) [PSCustomObject]@{Source=$Name} }
function java { $global:LASTEXITCODE=0; 'openjdk 21.0.1' }
function docker { $global:DevLauncherTest.Docker += ,@($args); $global:LASTEXITCODE=0 }
function Get-DevListener([int]$Port) {
    if ($global:DevLauncherTest.Reuse) {
        return @($global:DevLauncherTest.Processes.Values | Where-Object { $_.Port -eq $Port } | ForEach-Object { $_.Id })
    }
    return @()
}
function Get-CimInstance { param($ClassName,$Filter)
    $id = [int]($Filter -replace '\D','')
    return $global:DevLauncherTest.Processes[$id]
}
function Start-Process { param($FilePath,$ArgumentList,$WorkingDirectory,$WindowStyle,[switch]$PassThru,$RedirectStandardOutput,$RedirectStandardError)
    if ($WindowStyle -ne 'Hidden') { throw '测试拒绝可见窗口' }
    $global:DevLauncherTest.Starts++
    $id = 1000 + $global:DevLauncherTest.Starts
    $service = $script:DevServices[$global:DevLauncherTest.Starts - 1]
    $process = [PSCustomObject]@{Id=$id;Name='java.exe';CommandLine=($ArgumentList -join ' ');Port=$service.Port;HasExited=($global:DevLauncherTest.FailSecond -and $global:DevLauncherTest.Starts -eq 2)}
    $process | Add-Member ScriptMethod Refresh {}
    $global:DevLauncherTest.Processes[$id] = $process
    return $process
}
function Invoke-RestMethod { param($Uri,$TimeoutSec)
    # 模拟 monitoring 模式：只有管理端口就绪，业务 health 返回 code=500。
    if ($Uri -match ':1908[0-3]/' -or $global:DevLauncherTest.BusinessHealth) { return [PSCustomObject]@{status='UP'} }
    return [PSCustomObject]@{code=500}
}
function Stop-Process { param($Id)
    $global:DevLauncherTest.Stopped += $Id
    $global:DevLauncherTest.Processes.Remove([int]$Id)
}
function Wait-Process { param($Id,$Timeout) }
'@
[IO.File]::AppendAllText("$fixture/scripts/dev/common.ps1", $mocks, (New-Object Text.UTF8Encoding $true))
$manifest = Join-Path $fixture 'logs/dev/processes.json'
[IO.File]::WriteAllText($manifest, '[]')
$global:DevLauncherTest = @{Starts=0;Stopped=@();Docker=@();Processes=@{};Reuse=$false;FailSecond=$false;Monitoring=$false}
try {
    & "$fixture/scripts/dev/start.ps1" -SkipBuild -ActivityIds '123'
    Assert-DevTest ($global:DevLauncherTest.Starts -eq 4) '冷启动顺序创建四个 JVM'
    $savedRecords = Get-Content $manifest -Raw | ConvertFrom-Json
    Assert-DevTest ($savedRecords.Count -eq 4) '启动后持久化四个归属记录'
    Assert-DevTest $global:DevLauncherTest.Monitoring '监控使用 NoRecreate'
    Assert-DevTest (($global:DevLauncherTest.Docker | Where-Object { ($_ -join ' ') -match 'up -d --no-recreate' }).Count -gt 0) '中间件默认不重建'
    Assert-DevTest (($global:DevLauncherTest.Processes[1001].CommandLine) -match '-jar "[^"]*with spaces[^"]*"') 'jar 路径保持引号'
    Assert-DevTest ($global:DevLauncherTest.Processes[1001].CommandLine.Contains('--seckill.seed.user-count=0')) '日常启动显式关闭测试用户造数'

    $global:DevLauncherTest.Reuse=$true
    & "$fixture/scripts/dev/start.ps1" -SkipBuild
    Assert-DevTest ($global:DevLauncherTest.Starts -eq 4) '重复启动不再创建 JVM'
    Assert-DevTest ($global:DevLauncherTest.Stopped.Count -eq 0) '复用过程不停止任何 JVM'

    # 模拟 PID 被别的 Java 进程复用：stop 必须保留它并报告失败。
    $savedCommand = $global:DevLauncherTest.Processes[1001].CommandLine
    $global:DevLauncherTest.Processes[1001].CommandLine='java -jar unrelated.jar'
    $rejected=$false
    try { & "$fixture/scripts/dev/stop.ps1" } catch { $rejected=$true }
    Assert-DevTest $rejected '停止遇到身份冲突必须失败'
    Assert-DevTest ($global:DevLauncherTest.Stopped -notcontains 1001) '身份冲突的 PID 没被停止'
    $global:DevLauncherTest.Processes[1001].CommandLine=$savedCommand
    & "$fixture/scripts/dev/stop.ps1"
    Assert-DevTest ($global:DevLauncherTest.Processes.Count -eq 0) '正常停止能回收全部自有 JVM'

    $global:DevLauncherTest = @{Starts=0;Stopped=@();Docker=@();Processes=@{};Reuse=$false;FailSecond=$true;Monitoring=$false}
    $failed=$false
    try { & "$fixture/scripts/dev/start.ps1" -SkipBuild } catch { $failed=$true }
    Assert-DevTest $failed '第二个 JVM 提前退出，启动必须报错'
    Assert-DevTest ($global:DevLauncherTest.Starts -eq 2 -and $global:DevLauncherTest.Processes.Count -eq 0) '失败只回收本轮已启动 JVM，不再启动后续服务'

    $global:DevLauncherTest = @{Starts=0;Stopped=@();Docker=@();Processes=@{};Reuse=$false;FailSecond=$false;Monitoring=$false;BusinessHealth=$true}
    $rejected=$false
    try { & "$fixture/scripts/dev/start.ps1" -SkipBuild -ActivityIds '0' } catch { $rejected=$true }
    Assert-DevTest ($rejected -and $global:DevLauncherTest.Docker.Count -eq 0) '无效活动 ID 在操作 Docker 前就报错'
    & "$fixture/scripts/dev/start.ps1" -SkipBuild -WithoutMonitoring
    Assert-DevTest ($global:DevLauncherTest.Starts -eq 4 -and -not $global:DevLauncherTest.Monitoring) '无监控模式使用业务健康检查并跳过监控启动'
    Assert-DevTest (-not $global:DevLauncherTest.Processes[1001].CommandLine.Contains('profiles.active=monitoring')) '无监控模式不加 monitoring profile'
    & "$fixture/scripts/dev/stop.ps1"
} finally { Remove-Variable DevLauncherTest -Scope Global }
Write-Host "启动器隔离测试通过：$script:passed 项断言。未接触真实 Java/Docker 进程。"
