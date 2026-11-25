<#
.SYNOPSIS
Moves Checklist++ from the old package com.example.checklist to com.fletcheese.checklist on a phone, keeping every list.

.DESCRIPTION
1. Backs up the private data of both packages (run-as tar) to -BackupRoot\<package>\data-<timestamp>.tar.
2. Installs the renamed APK with `adb install -r`. If the phone holds an older com.fletcheese.checklist signed with a different key, the install fails; with -ReplaceMismatchedTarget the old target is uninstalled (after its backup) and the install retried.
3. Copies files/checklist_data.json and shared_prefs/ChecklistWidgetPrefs.xml from the source backup into the new package through run-as.
4. Verifies: the on-device file hash matches the backup, and the new app's provider reports the same lists and item counts as the backup JSON.
5. Only with -UninstallSource, and only if step 4 passed, uninstalls com.example.checklist.

Both packages must be debuggable (run-as). The home-screen widget belongs to the old package, so it disappears on uninstall and has to be re-added.

.EXAMPLE
.\tools\migrate-to-fletcheese.ps1 -Apk app\build\outputs\apk\debug\app-debug.apk -ReplaceMismatchedTarget
#>
param(
    [Parameter(Mandatory)] [string] $Apk,
    [string] $Adb = 'C:\Users\Fletcher\AppData\Local\Android\Sdk\platform-tools\adb.exe',
    [string] $BackupRoot = (Join-Path $PSScriptRoot '..\..\checklist-data-backup'),
    [string] $Source = 'com.example.checklist',
    [string] $Target = 'com.fletcheese.checklist',
    [switch] $ReplaceMismatchedTarget,
    [switch] $UninstallSource
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8  # adb prints list names as UTF-8
$Apk = (Resolve-Path $Apk).Path
if (-not (Test-Path $Adb)) { throw "adb not found at $Adb" }
New-Item -ItemType Directory -Force $BackupRoot | Out-Null
$BackupRoot = (Resolve-Path $BackupRoot).Path
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

function Invoke-Adb([string[]] $AdbArgs) {
    $ErrorActionPreference = 'Continue'  # adb writes failures to stderr; PS 5.1 would otherwise throw on the first line
    $out = & $Adb @AdbArgs 2>&1 | ForEach-Object { "$_" } | Out-String
    Write-Output $out.Trim()
}
function Test-Installed([string] $Pkg) { (Invoke-Adb @('shell', 'pm', 'path', $Pkg)) -match '^package:' }

# cmd.exe redirection keeps binary streams intact; PowerShell 5.1 pipes would re-encode them.
function Backup-Package([string] $Pkg) {
    $dir = Join-Path $BackupRoot $Pkg
    New-Item -ItemType Directory -Force $dir | Out-Null
    $tarFile = Join-Path $dir "data-$stamp.tar"
    Invoke-Adb @('shell', 'am', 'force-stop', $Pkg) | Out-Null
    cmd /c "`"$Adb`" exec-out run-as $Pkg tar c -C /data/data/$Pkg . > `"$tarFile`""
    $listing = & tar.exe -tf $tarFile 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "Backup of $Pkg is not a valid tar: $(Get-Content $tarFile -Raw)" }
    $x = Join-Path $dir "extracted-$stamp"
    New-Item -ItemType Directory -Force $x | Out-Null
    & tar.exe -xf $tarFile -C $x
    $script:LastExtract = $x
    Write-Output "Backed up $Pkg -> $tarFile"
    Write-Output $listing.Trim()
}

function Push-File([string] $LocalFile, [string] $Pkg, [string] $RemoteRel) {
    $remoteDir = Split-Path $RemoteRel -Parent
    Invoke-Adb @('shell', "run-as $Pkg mkdir -p $remoteDir") | Out-Null
    cmd /c "`"$Adb`" exec-in run-as $Pkg sh -c 'cat > $RemoteRel' < `"$LocalFile`""
    if ($LASTEXITCODE -ne 0) { throw "Push of $RemoteRel failed" }
}

Write-Output "== Device"
Write-Output (Invoke-Adb @('devices', '-l'))

Write-Output "== 1. Backup"
if (-not (Test-Installed $Source)) { throw "$Source is not installed; nothing to migrate" }
Backup-Package $Source
$srcDir = $script:LastExtract
$srcJson = Join-Path $srcDir 'files\checklist_data.json'
if (-not (Test-Path $srcJson)) { throw "Source backup has no files/checklist_data.json" }
if (Test-Installed $Target) { Backup-Package $Target }

Write-Output "== 2. Install $Apk"
$res = Invoke-Adb @('install', '-r', $Apk)
Write-Output $res
if ($res -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
    if (-not $ReplaceMismatchedTarget) { throw "Existing $Target is signed with a different key. Re-run with -ReplaceMismatchedTarget to uninstall it (its data is backed up above)." }
    Write-Output "Uninstalling the differently-signed $Target (backed up above)"
    Write-Output (Invoke-Adb @('uninstall', $Target))
    $res = Invoke-Adb @('install', '-r', $Apk)
    Write-Output $res
}
if ($res -notmatch 'Success') { throw "Install failed" }

Write-Output "== 3. Copy data $Source -> $Target"
Invoke-Adb @('shell', 'am', 'force-stop', $Target) | Out-Null
Push-File $srcJson $Target 'files/checklist_data.json'
$srcPrefs = Join-Path $srcDir 'shared_prefs\ChecklistWidgetPrefs.xml'
if (Test-Path $srcPrefs) { Push-File $srcPrefs $Target 'shared_prefs/ChecklistWidgetPrefs.xml' }

Write-Output "== 4. Verify"
$ok = $true
$localHash = (Get-FileHash $srcJson -Algorithm SHA256).Hash.ToLower()
$remoteHash = ((Invoke-Adb @('shell', "run-as $Target sha256sum files/checklist_data.json")) -split '\s+')[0]
if ($localHash -eq $remoteHash) { Write-Output "OK   checklist_data.json hash $localHash" } else { Write-Output "FAIL checklist_data.json hash local $localHash device $remoteHash"; $ok = $false }

$state = Get-Content $srcJson -Raw -Encoding UTF8 | ConvertFrom-Json
# kotlinx.serialization omits empty lists, and in PS 5.1 @($null).Count is 1, so count explicitly.
function Get-Count($x) { if ($null -eq $x) { 0 } else { @($x).Count } }
$expected = @{}
foreach ($i in @($state.instances)) { $expected[$i.id] = "$($i.name)|$(Get-Count $i.items)" }
$rows = Invoke-Adb @('shell', 'content', 'query', '--uri', 'content://com.fletcheese.checklist.provider/lists', '--projection', '_id:name:total')
$actual = @{}
foreach ($line in ($rows -split "`n")) {
    if ($line -match '_id=([^,]+), name=(.*), total=(\d+)\s*$') { $actual[$Matches[1]] = "$($Matches[2])|$($Matches[3])" }
}
Write-Output "Lists in backup: $($expected.Count); reported by new app: $($actual.Count)"
foreach ($k in $expected.Keys) {
    if ($actual[$k] -ne $expected[$k]) { Write-Output "FAIL list $k expected '$($expected[$k])' got '$($actual[$k])'"; $ok = $false }
}
if ($actual.Count -ne $expected.Count) { $ok = $false }
Write-Output ("Templates {0}, item definitions {1}, sort schemas {2}, sync URL set: {3} (carried inside the same JSON)" -f (Get-Count $state.templates), (Get-Count $state.itemDefinitions), (Get-Count $state.sortSchemas), [bool]$state.syncUrl)

if (-not $ok) { Write-Output "VERIFICATION FAILED - $Source left installed"; exit 1 }
Write-Output "VERIFIED"

if ($UninstallSource) {
    Write-Output "== 5. Uninstall $Source"
    Write-Output (Invoke-Adb @('uninstall', $Source))
    Write-Output "Re-add the home-screen widget: the old one belonged to $Source."
} else {
    Write-Output "$Source left installed. Re-run with -UninstallSource once happy."
}
