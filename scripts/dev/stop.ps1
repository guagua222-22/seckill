<# 只停止 scripts/dev/start.ps1 创建且身份匹配的 Java 进程，保留容器、数据卷和 IDE 进程。 #>
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'common.ps1')
$lock = Enter-DevLock
try {
    $records = @(Read-DevRecords)
    $remaining = @()
    # 网关先停，再停业务服务。Windows 进程终止不保证处理完在途请求，压测结束后再使用。
    [array]::Reverse($records)
    foreach ($record in $records) {
        try {
            Stop-DevRecord $record
            Write-Host "已停止或已经退出：$($record.Name) PID=$($record.ProcessId)"
        } catch { $remaining += $record; Write-Warning $_ }
    }
    Save-DevRecords $remaining
    if ($remaining.Count -gt 0) { throw '部分进程因身份不匹配未停止，请检查清单和提示。' }
    Write-Host '脚本管理的 JVM 已停止；Docker 容器及 IDE 进程保持原状。'
} finally { $lock.Dispose() }
