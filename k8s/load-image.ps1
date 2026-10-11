# 로컬에서 빌드한 이미지를 Docker Desktop 쿠버네티스 노드에 넣는다.
#
# Docker Desktop의 쿠버네티스는 kind 기반이라 노드가 별도 컨테이너 안에서 돌고,
# 자체 containerd 이미지 저장소(k8s.io 네임스페이스)를 쓴다. 그래서 `docker build`로
# 만든 이미지가 클러스터에는 보이지 않는다. 아래처럼 tar로 내보낸 뒤 노드에 import한다.
#
# 사용법: .\k8s\load-image.ps1 dvp-app:v1

param(
    [Parameter(Mandatory = $true)][string]$Image,
    [string]$Node = "desktop-control-plane"
)

$ErrorActionPreference = "Stop"
$tar = Join-Path $env:TEMP ("{0}.tar" -f ($Image -replace "[:/]", "-"))

Write-Host "[1/3] 이미지를 tar로 내보내는 중: $Image"
docker save $Image -o $tar

# 노드의 /tmp 는 tmpfs 로 마운트돼 있어 docker cp 한 파일이 가려진다. /var/tmp 를 쓴다.
Write-Host "[2/3] 노드로 복사하는 중: $Node"
docker cp $tar "${Node}:/var/tmp/image.tar"

Write-Host "[3/3] 노드의 containerd(k8s.io)에 import 중"
docker exec $Node ctr -n k8s.io images import /var/tmp/image.tar
docker exec $Node rm -f /var/tmp/image.tar

Remove-Item $tar -Force
Write-Host "완료. 확인: docker exec $Node crictl images | Select-String dvp"
