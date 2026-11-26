<#
Install the Leash bridge as a Windows scheduled task: starts at logon (hidden), is restarted after a crash,
and logs to %USERPROFILE%\.leash\bridge.log. The Windows counterpart of install-launchagent.sh.

  powershell -ExecutionPolicy Bypass -File bridge\scripts\install-windows-task.ps1
  powershell -ExecutionPolicy Bypass -File bridge\scripts\install-windows-task.ps1 -Uninstall

It runs as you, only while you are logged on (agy and Claude Code need your login). No administrator rights needed.
#>
param([switch]$Uninstall, [string]$TaskName = 'LeashBridge')
$ErrorActionPreference = 'Stop'

$bridge = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$index = Join-Path $bridge 'src\index.mjs'
$leashDir = Join-Path $env:USERPROFILE '.leash'
$log = Join-Path $leashDir 'bridge.log'

function Stop-Bridge {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    # belt and braces: a node still running this bridge keeps the port
    Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($index) } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

if ($Uninstall) {
    Stop-Bridge
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Write-Host "removed task $TaskName"
    exit 0
}

$node = (Get-Command node -ErrorAction SilentlyContinue).Source
if (-not $node) { Write-Error 'node not found on PATH: install Node.js LTS (https://nodejs.org) and open a new terminal'; exit 1 }
if (-not (Get-Command agy -ErrorAction SilentlyContinue) -and -not (Get-Command claude -ErrorAction SilentlyContinue)) {
    Write-Warning 'neither agy nor claude is on PATH; the bridge starts whatever agent it detects. Run: node src\index.mjs doctor'
}

if (-not (Test-Path (Join-Path $bridge 'node_modules'))) {
    Push-Location $bridge
    try { npm install --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { throw 'npm install failed' } } finally { Pop-Location }
}
New-Item -ItemType Directory -Force -Path $leashDir | Out-Null

# The task starts a hidden PowerShell, which starts node hidden and waits (its exit code decides about a restart).
# The script travels as -EncodedCommand, so no quoting of paths with spaces can go wrong.
function Quote([string]$s) { return "'" + $s.Replace("'", "''") + "'" }
$script = "`$env:LEASH_LOG_FILE = " + (Quote $log) + "; " +
    "`$p = Start-Process -FilePath " + (Quote $node) + " -ArgumentList " + (Quote ('"' + $index + '"')) +
    " -WorkingDirectory " + (Quote $bridge) + " -WindowStyle Hidden -PassThru -Wait; exit `$p.ExitCode"
$encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($script))

$user = "$env:USERDOMAIN\$env:USERNAME"
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -NonInteractive -WindowStyle Hidden -EncodedCommand $encoded" -WorkingDirectory $bridge
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$settings = New-ScheduledTaskSettingsSet -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited

Stop-Bridge
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName
Write-Host "installed task $TaskName (log: $log)"
Start-Sleep -Seconds 3
if (Test-Path $log) { Get-Content $log -Tail 8 }
Write-Host ''
Write-Host 'Windows Firewall may ask about Node.js: allow it on Private networks (Tailscale / home LAN), or the phone cannot connect.'
& $node $index pair
