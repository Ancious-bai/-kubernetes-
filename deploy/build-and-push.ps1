param(
    [string]$Repository = "docker.io/ancious",
    [string]$Version = "v1.0.0",
    [switch]$Push
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$BackendDir = Join-Path $Root "yolo-project"

$BackendImage = "${Repository}/yolo-backend:${Version}"
$FrontendImage = "${Repository}/yolo-frontend:${Version}"
$TrainerImage = "${Repository}/yolov8-trainer:${Version}"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Building YOLO Training System Images" -ForegroundColor Cyan
Write-Host "Repository: $Repository" -ForegroundColor Gray
Write-Host "Version: $Version" -ForegroundColor Gray
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

Write-Host "[1/3] Building trainer image: $TrainerImage" -ForegroundColor Yellow
docker build -t $TrainerImage -f (Join-Path $Root "Dockerfile.trainer") $Root
if ($LASTEXITCODE -ne 0) { throw "Failed to build trainer image" }

Write-Host ""
Write-Host "[2/3] Building backend image: $BackendImage" -ForegroundColor Yellow
docker build -t $BackendImage -f (Join-Path $BackendDir "Dockerfile") $BackendDir
if ($LASTEXITCODE -ne 0) { throw "Failed to build backend image" }

Write-Host ""
Write-Host "[3/3] Building frontend image: $FrontendImage" -ForegroundColor Yellow
docker build -t $FrontendImage -f (Join-Path $BackendDir "Dockerfile.frontend") $BackendDir
if ($LASTEXITCODE -ne 0) { throw "Failed to build frontend image" }

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "Build completed successfully!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green

if ($Push) {
    Write-Host ""
    Write-Host "Pushing images..." -ForegroundColor Cyan
    
    Write-Host "[1/3] Pushing trainer image" -ForegroundColor Yellow
    docker push $TrainerImage
    if ($LASTEXITCODE -ne 0) { throw "Failed to push trainer image" }
    
    Write-Host "[2/3] Pushing backend image" -ForegroundColor Yellow
    docker push $BackendImage
    if ($LASTEXITCODE -ne 0) { throw "Failed to push backend image" }
    
    Write-Host "[3/3] Pushing frontend image" -ForegroundColor Yellow
    docker push $FrontendImage
    if ($LASTEXITCODE -ne 0) { throw "Failed to push frontend image" }
    
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Green
    Write-Host "All images pushed successfully!" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
}

Write-Host ""
Write-Host "Images:" -ForegroundColor Cyan
Write-Host "  Trainer:  $TrainerImage" -ForegroundColor White
Write-Host "  Backend:  $BackendImage" -ForegroundColor White
Write-Host "  Frontend: $FrontendImage" -ForegroundColor White
