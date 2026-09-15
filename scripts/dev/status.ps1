# 只读检查。端口在监听不代表业务就绪，所以额外读取 health.status。
. (Join-Path $PSScriptRoot 'common.ps1')
$records = @(Read-DevRecords)
foreach ($service in $script:DevServices) {
    $owners = @(Get-DevListener $service.Port)
    $owned = $false
    foreach ($record in @($records | Where-Object { $_.Name -eq $service.Name })) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$record.ProcessId)"
        if (Test-DevProcessIdentity $record $process) { $owned = $true }
    }
    [PSCustomObject]@{
        Service=$service.Name; Port=$service.Port; ProcessId=($owners -join ',');
        Healthy=((Test-DevHealth $service.Port) -or (Test-DevHealth $service.ManagementPort)); Monitoring=(Test-DevHealth $service.ManagementPort);
        ManagedByScript=$owned
    }
}
