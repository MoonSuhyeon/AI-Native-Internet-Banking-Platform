# 컨테이너 이미지가 소스보다 낡았는지 본다.
#
#  왜 필요한가.
#  `docker compose up -d <서비스>` 는 이미지가 이미 있으면 **다시 빌드하지 않는다.**
#  그래서 소스를 고치고 올려도 어제 만든 이미지가 그대로 뜬다. 컨테이너는 healthy 고
#  로그도 깨끗한데 동작만 옛날 것이다.
#
#  실제로 이 세션에서 겪었다. 조사 사이드카에 실거래 입력을 붙여 뒀는데 컨테이너는
#  그 지원이 없는 판이라 422 가 났고, 계약이 어긋난 줄 알고 한참 들여다봤다.
#  드러난 증상(422)과 원인(낡은 이미지)이 멀어서 찾기 어려운 종류다.
#
#  무엇을 보는가.
#  compose 가 알려 주는 각 서비스의 Dockerfile 위치에서 그 서비스의 소스 디렉터리를
#  끌어내, **이미지가 만들어진 시각**과 **그 디렉터리에서 가장 최근에 바뀐 파일**을
#  견준다. 소스가 더 새것이면 낡은 이미지다.
#
#  보지 않는 것.
#  테스트 소스는 세지 않는다. 이미지에 들어가지 않으니 아무리 고쳐도 컨테이너
#  동작은 그대로다 — 그걸 낡음으로 세면 거짓 경보가 나고, 거짓 경보가 몇 번
#  나면 아무도 이 검사를 안 읽는다.
#
#  자바 서비스가 기대는 common 모듈도 보지 않는다. 서비스 제 소스만 본다 —
#  넓히면 아무 파일이나 고쳐도 전부 낡았다고 나와서 같은 이유로 무용해진다.
#  놓치는 경우가 있어도 흔한 쪽을 잡는 편이 낫다.
#
#  정확하지 않다 — 시각만 본다.
#  파일을 되돌려 쓰면(브랜치 전환, 백업 복원) 내용이 같아도 mtime 이 새것이 되어
#  낡은 것으로 잡힌다. 실제로 이 스크립트를 만들면서 그런 거짓 양성을 봤다.
#  그래서 "낡았다" 가 아니라 **"낡았을 수 있다"** 로 말한다. 단정하지 않는 대신
#  놓치지 않는 쪽을 고른 것이고, 확인은 다시 빌드해 보면 끝난다 — 내용이 같으면
#  캐시가 걸려 금방 끝나고 이미지도 그대로다.
#
#  사용법:
#    pwsh scripts/check-stale-images.ps1
#    pwsh scripts/check-stale-images.ps1 -Fix     # 낡은 것만 다시 빌드

param(
    [switch]$Fix
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

Write-Host "compose 설정을 읽는 중..." -ForegroundColor DarkGray
$configJson = docker compose -f "$repoRoot\docker-compose.yml" config --format json 2>$null
if (-not $configJson) {
    Write-Host "[ERROR] docker compose config 실패 — 도커가 떠 있는지 확인하세요." -ForegroundColor Red
    exit 1
}
$config = $configJson | ConvertFrom-Json

$stale = @()
$unbuilt = @()
$checked = 0

foreach ($prop in $config.services.PSObject.Properties) {
    $name = $prop.Name
    $svc = $prop.Value
    if (-not $svc.build) { continue }

    # Dockerfile 이 있는 자리가 곧 그 서비스의 소스 디렉터리다.
    $dockerfile = Join-Path $svc.build.context $svc.build.dockerfile
    $sourceDir = Split-Path -Parent $dockerfile
    if (-not (Test-Path $sourceDir)) { continue }

    # 실행 중인 컨테이너가 쓰는 이미지를 본다.
    #
    # 실패를 삼키고 계속 간다. 컨테이너가 **이미 지워진 이미지**를 가리키는 경우가
    # 있는데(다시 빌드했지만 컨테이너를 새로 만들지 않았을 때), 그때 docker 가
    # 오류를 내면서 여기서 스크립트가 통째로 죽었다. 그 상태야말로 알려야 할
    # 것이라, 죽는 대신 "다시 올려야 함" 으로 보고한다.
    $imageId = $null
    try {
        $ErrorActionPreference = "Continue"
        $imageId = docker compose -f "$repoRoot\docker-compose.yml" images -q $name 2>$null
    } catch {
        $imageId = $null
    } finally {
        $ErrorActionPreference = "Stop"
    }
    if (-not $imageId) {
        $unbuilt += $name
        continue
    }

    $created = $null
    try {
        $ErrorActionPreference = "Continue"
        $created = docker inspect --format "{{.Created}}" $imageId 2>$null
    } catch {
        $created = $null
    } finally {
        $ErrorActionPreference = "Stop"
    }
    if (-not $created) {
        $unbuilt += $name
        continue
    }
    $imageTime = [datetime]::Parse($created).ToUniversalTime()

    # build 산출물과 테스트 소스는 제외한다. 앞은 빌드마다 새로 써져 항상 최신이고,
    # 뒤는 이미지에 들어가지 않아 고쳐도 컨테이너 동작이 그대로다. 둘 다 세면
    # 거짓 경보가 되고, 거짓 경보가 나는 검사는 곧 아무도 안 읽는다.
    $newest = Get-ChildItem -Path $sourceDir -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '\\(build|bin|__pycache__|\.pytest_cache|node_modules|\.next|tests|eval)\\' } |
        Where-Object { $_.FullName -notmatch '\\src\\test\\' } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1

    $checked++
    if (-not $newest) { continue }

    if ($newest.LastWriteTimeUtc -gt $imageTime) {
        $stale += [pscustomobject]@{
            Service = $name
            Image   = $imageTime.ToString("yyyy-MM-dd HH:mm")
            Source  = $newest.LastWriteTimeUtc.ToString("yyyy-MM-dd HH:mm")
            File    = $newest.FullName.Replace("$repoRoot\", "")
        }
    }
}

Write-Host ""
Write-Host "검사한 서비스: $checked" -ForegroundColor DarkGray

if ($unbuilt.Count -gt 0) {
    Write-Host "이미지를 확인할 수 없음(안 올렸거나, 다시 빌드 후 컨테이너를 새로 안 만듦): $($unbuilt -join ', ')" -ForegroundColor DarkGray
}

if ($stale.Count -eq 0) {
    Write-Host "의심되는 이미지 없음 — 컨테이너가 소스보다 새것입니다." -ForegroundColor Green
    exit 0
}

Write-Host ""
Write-Host "낡았을 수 있는 이미지 $($stale.Count)건 — 소스가 이미지보다 새것입니다." -ForegroundColor Yellow
$stale | Format-Table -AutoSize Service, Image, Source, File

Write-Host "정말 낡았다면 고친 코드가 아니라 옛 코드가 돕니다." -ForegroundColor Yellow
Write-Host "파일을 되돌려 쓴 것뿐이면 내용은 같다 — 다시 빌드하면 캐시가 걸려 금방 끝납니다." -ForegroundColor DarkGray

if (-not $Fix) {
    $names = ($stale | ForEach-Object { $_.Service }) -join ' '
    Write-Host ""
    Write-Host "다시 빌드하려면:" -ForegroundColor Cyan
    Write-Host "  docker compose up -d --build $names" -ForegroundColor Cyan
    Write-Host "  (또는 이 스크립트에 -Fix)" -ForegroundColor DarkGray
    exit 1
}

$names = $stale | ForEach-Object { $_.Service }
Write-Host ""
Write-Host "다시 빌드합니다: $($names -join ', ')" -ForegroundColor Cyan
docker compose -f "$repoRoot\docker-compose.yml" up -d --build @names
exit $LASTEXITCODE
