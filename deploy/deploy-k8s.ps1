param(
    [string]$K8sDir = "../yolo-project/k8s"
)

$ErrorActionPreference = "Stop"

$ScriptDir = $PSScriptRoot
$ResolvedK8sDir = Resolve-Path (Join-Path $ScriptDir $K8sDir)

Write-Host "Deploying manifests from: $ResolvedK8sDir"

kubectl apply -f (Join-Path $ResolvedK8sDir "00-namespace.yaml")
kubectl apply -f (Join-Path $ResolvedK8sDir "01-config.yaml")
kubectl apply -f (Join-Path $ResolvedK8sDir "02-pvc.yaml")
kubectl apply -f (Join-Path $ResolvedK8sDir "03-mysql.yaml")
kubectl apply -f (Join-Path $ResolvedK8sDir "06-rbac.yaml")
kubectl apply -f (Join-Path $ResolvedK8sDir "04-backend.yaml")
kubectl apply -f (Join-Path $ResolvedK8sDir "05-frontend.yaml")
kubectl apply -f (Join-Path $ResolvedK8sDir "07-ingress.yaml")

Write-Host "Waiting for workloads..."
kubectl wait --for=condition=ready pod -l app=yolo-mysql -n yolo-system --timeout=180s
kubectl wait --for=condition=ready pod -l app=yolo-backend -n yolo-system --timeout=180s
kubectl wait --for=condition=ready pod -l app=yolo-frontend -n yolo-system --timeout=180s

Write-Host "Deployment status:"
kubectl get pods -n yolo-system -o wide
kubectl get svc -n yolo-system
