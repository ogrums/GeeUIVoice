# À lancer sur le PC RoadRunner quand il est en ligne.
# Inventaire nimbus (lecture seule) + copie des scripts, sans toucher aux
# autres conteneurs. Usage :
#   .\sttbench.ps1 inventory
#   .\sttbench.ps1 run -Manifest C:\chemin\manifest.json
# Clé SSH habituelle : $env:USERPROFILE\.ssh\grokbot_nimbus

param(
    [Parameter(Position=0)]
    [ValidateSet("inventory","sync","run","clean")]
    [string]$Cmd = "inventory",
    [string]$Manifest = "",
    [string]$RemoteDir = "/home/grokbot/sttbench",
    [string[]]$Profile = @()
)

$sshKey = Join-Path $env:USERPROFILE ".ssh\grokbot_nimbus"
$ssh = @("-i", $sshKey, "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes", "grokbot@nimbus")

function Invoke-Nimbus([string]$RemoteCmd) {
    & ssh @ssh $RemoteCmd
    if ($LASTEXITCODE -ne 0) { throw "ssh a échoué ($LASTEXITCODE) : $RemoteCmd" }
}

$here = Split-Path -Parent $MyInvocation.MyCommand.Path

switch ($Cmd) {
    "inventory" {
        Invoke-Nimbus "python3 - <<'PY'
import urllib.request, json, socket
for url in [
    'http://127.0.0.1:13305/api/v1/models',
    'http://127.0.0.1:13305/api/v1/models?show_all=true',
    'http://127.0.0.1:8080/health',
    'http://127.0.0.1:8080/v1/models',
    'http://127.0.0.1:13306/v1/models',
    'http://127.0.0.1:13306/health',
]:
    print('==', url)
    try:
        with urllib.request.urlopen(url, timeout=8) as r:
            body = r.read()[:2000]
            print(r.status, body.decode('utf-8','replace'))
    except Exception as e:
        print('ERR', type(e).__name__, e)
    print()
PY"
    }
    "sync" {
        Invoke-Nimbus "mkdir -p $RemoteDir"
        & scp -i $sshKey -o BatchMode=yes -o IdentitiesOnly=yes -r `
            "$here\sttbench.py","$here\engines.py","$here\normalize.py","$here\wavutil.py",`
            "$here\serve.py","$here\candidates.json","$here\requirements.txt",`
            "$here\docker-compose.candidates.yml","$here\Dockerfile.candidate","$here\nimbus-run.sh","$here\README.md" `
            "grokbot@nimbus:${RemoteDir}/"
    }
    "run" {
        if (-not $Manifest) { throw "préciser -Manifest" }
        $profiles = ($Profile | ForEach-Object { "--profile $_" }) -join " "
        Invoke-Nimbus "cd $RemoteDir && OUT=$RemoteDir/out MANIFEST=$Manifest bash nimbus-run.sh $profiles"
    }
    "clean" {
        Invoke-Nimbus "cd $RemoteDir && bash nimbus-run.sh --clean"
    }
}
