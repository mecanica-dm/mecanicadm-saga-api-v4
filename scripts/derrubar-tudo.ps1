# Derruba o sistema inteiro. Com -Limpar, apaga tambem os volumes (bancos e broker) para comecar do zero.
# Uso (PowerShell):  .\scripts\derrubar-tudo.ps1 [-Limpar]
param([switch]$Limpar)
$ErrorActionPreference = 'Stop'

$SagaDir = Split-Path -Parent $PSScriptRoot
$Root = Split-Path -Parent $SagaDir
$extra = @()
if ($Limpar) { $extra = @('-v') }

function Derruba($dir, [string[]]$antes) {
    if (-not (Test-Path $dir)) { return }
    Write-Host ">>> $(Split-Path -Leaf $dir): docker compose $($antes -join ' ') down $($extra -join ' ')"
    Push-Location $dir
    try { docker compose @antes down @extra } finally { Pop-Location }
}

# Ordem inversa da subida: o broker (na pasta da saga) e o ultimo a cair.
Derruba (Join-Path $Root 'mecanicadm-finance-api-v4') @()
Derruba (Join-Path $Root 'mecanicadm-workorder-api-v4') @()
Derruba $SagaDir @('--profile', 'broker')
