# Sobe o sistema inteiro (broker, Saga API, Workorder API e Finance API) na ordem certa e espera ficar saudavel.
# Uso (PowerShell):  .\scripts\subir-tudo.ps1
# Se o Windows bloquear a execucao:  powershell -ExecutionPolicy Bypass -File .\scripts\subir-tudo.ps1
#
# Requisito: as tres pastas lado a lado (mecanicadm-saga-api-v4, mecanicadm-workorder-api-v4 e mecanicadm-finance-api-v4).
# Cada servico continua podendo ser subido sozinho com `docker compose up -d --build` na sua propria pasta.
$ErrorActionPreference = 'Stop'

$SagaDir = Split-Path -Parent $PSScriptRoot
$Root = Split-Path -Parent $SagaDir
$WorkorderDir = Join-Path $Root 'mecanicadm-workorder-api-v4'
$FinanceDir = Join-Path $Root 'mecanicadm-finance-api-v4'
$SagaPort = if ($env:SAGA_PORT) { $env:SAGA_PORT } else { '8081' }
$WorkorderPort = if ($env:WORKORDER_PORT) { $env:WORKORDER_PORT } else { '8080' }
$FinancePort = if ($env:FINANCE_PORT) { $env:FINANCE_PORT } else { '8082' }

foreach ($dir in @($WorkorderDir, $FinanceDir)) {
    if (-not (Test-Path $dir)) {
        Write-Error "Pasta nao encontrada: $dir. Clone os tres repositorios lado a lado, na mesma pasta."
    }
}

function Sobe($dir, [string[]]$extra) {
    Write-Host ">>> $(Split-Path -Leaf $dir): docker compose $($extra -join ' ') up -d --build"
    Push-Location $dir
    try {
        docker compose @extra up -d --build
        if ($LASTEXITCODE -ne 0) { throw "docker compose falhou em $dir" }
    } finally { Pop-Location }
}

function Espera($nome, $url) {
    Write-Host -NoNewline "Aguardando $nome"
    for ($i = 0; $i -lt 90; $i++) {
        try {
            $resposta = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5
            if ($resposta.Content -match '"status":"UP"') { Write-Host ' ok'; return }
        } catch { }
        Write-Host -NoNewline '.'
        Start-Sleep -Seconds 3
    }
    Write-Error "$nome nao ficou saudavel (veja: docker compose logs api, na pasta do servico)"
}

# 1) Saga API + broker (o broker e compartilhado pelos tres servicos e precisa subir primeiro)
Sobe $SagaDir @('--profile', 'broker')
Espera 'Saga API' "http://localhost:$SagaPort/api/actuator/health/liveness"
# 2) Workorder API
Sobe $WorkorderDir @()
Espera 'Workorder API' "http://localhost:$WorkorderPort/api/actuator/health/liveness"
# 3) Finance API
Sobe $FinanceDir @()
Espera 'Finance API' "http://localhost:$FinancePort/api/actuator/health/liveness"

Write-Host @"

Tudo no ar:
  Workorder API  http://localhost:$WorkorderPort/api/swagger-ui.html   (login do seed: admin@mecanicadm.com / Senha123)
  Saga API       http://localhost:$SagaPort/api/swagger-ui.html
  Finance API    http://localhost:$FinancePort/api/swagger-ui.html
  RabbitMQ       http://localhost:15672   (guest / guest)
  Mailpit        http://localhost:8025    (e-mails de orcamento)
"@
