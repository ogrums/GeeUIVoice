#Requires -Version 5
<#
.SYNOPSIS
  Campagne de tests vocaux RUX depuis le PC RoadRunner (PowerShell 5 + Python).

.EXAMPLE
  .\voicetest.ps1 -Mode wav
  .\voicetest.ps1 -Mode chaine -Suite emotion
#>
[CmdletBinding()]
param(
    [ValidateSet('wav', 'chaine')]
    [string]$Mode = 'wav',

    [ValidateSet('all', 'dialogue', 'emotion', 'voice')]
    [string]$Suite = 'all',

    [string]$Serial = '2006014140527',

    [string]$Phonon = 'http://nimbus:8880',

    [string]$Whisper = 'http://nimbus:13305/api/v1',

    [string]$PhononVoice = 'Hélène',

    [int]$Timeout = 90,

    [switch]$Regen,

    [string]$Ids = '',

    [string]$OutDir = '',

    [string]$Python = ''
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

function Find-Python {
    if ($Python -and (Test-Path $Python)) { return $Python }
    foreach ($name in @('python', 'python3', 'py')) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd) {
            if ($name -eq 'py') { return 'py -3' }
            return $cmd.Source
        }
    }
    throw "Python 3 introuvable. Installez-le et réessayez (python.org), ou passez -Python chemin\python.exe"
}

$py = Find-Python
$script = Join-Path $here 'voicetest.py'
if (-not (Test-Path $script)) { throw "introuvable : $script" }

$argList = @(
    $script,
    '--mode', $Mode,
    '--suite', $Suite,
    '--adb-serial', $Serial,
    '--phonon', $Phonon,
    '--whisper', $Whisper,
    '--phonon-voice', $PhononVoice,
    '--timeout', "$Timeout"
)
if ($Regen) { $argList += '--regen' }
if ($Ids) { $argList += @('--ids', $Ids) }
if ($OutDir) { $argList += @('--out-dir', $OutDir) }

Write-Host "python $($argList -join ' ')"
if ($py -eq 'py -3') {
    & py -3 @argList
} else {
    & $py @argList
}
exit $LASTEXITCODE
