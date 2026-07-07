<#
.SYNOPSIS
    Copy the minimal set of files needed to build and run image-sorter with
    Docker / docker-compose to a destination path (e.g. a deploy folder or a
    mounted TrueNAS dataset).

.DESCRIPTION
    Copies only what the Docker build context actually needs, mirroring the
    exclusions in .dockerignore (no node_modules, built dist/, caches, tests,
    logs, runtime data or the unused yolo26n.pt weights). Safe to re-run: each
    build-owned subtree is mirrored, so removed source files disappear at the
    destination too.

.PARAMETER Destination
    Target directory. Created if it does not exist.

.PARAMETER Source
    Repo root to copy from. Defaults to the folder this script lives in.

.EXAMPLE
    ./copy-docker-essentials.ps1 -Destination \\JUSZKOWO_NAS\quick_access_for_pc\daniel\image-sorter
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Destination,

    [string]$Source = $PSScriptRoot
)

$ErrorActionPreference = 'Stop'

# Single files that live at the repo root and are part of the build context.
# docker-compose.yml and the configs/ tree are intentionally NOT copied: they
# are environment-specific and managed separately at the destination.
$files = @(
    'Dockerfile',
    '.dockerignore',
    'pyproject.toml',
    'yolo11s.pt'
)

# Directory trees to mirror. These map 1:1 to the Dockerfile COPY steps.
$dirs = @(
    'frontend',
    'launcher',
    'imagesorter'
)

# Directory / file names excluded everywhere, matching .dockerignore so the
# copied bundle equals the real build context.
$excludeDirs  = @('node_modules', 'dist', '__pycache__', 'tests', '.egg-info', '.idea', 'configs')
$excludeFiles = @('*.log', 'docker-compose.yml')

if (-not (Test-Path -LiteralPath $Source)) {
    throw "Source path not found: $Source"
}
if (-not (Test-Path -LiteralPath $Destination)) {
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
}

# Use ProviderPath (raw filesystem path) rather than Path: for UNC targets the
# latter carries a "Microsoft.PowerShell.Core\FileSystem::" provider prefix that
# native robocopy cannot parse (it fails with exit code 16).
$srcFull = (Resolve-Path -LiteralPath $Source).ProviderPath
$dstFull = (Resolve-Path -LiteralPath $Destination).ProviderPath
Write-Host "Copying Docker essentials" -ForegroundColor Cyan
Write-Host "  from: $srcFull"
Write-Host "  to:   $dstFull`n"

foreach ($file in $files) {
    $srcFile = Join-Path $srcFull $file
    if (Test-Path -LiteralPath $srcFile) {
        Copy-Item -LiteralPath $srcFile -Destination (Join-Path $dstFull $file) -Force
        Write-Host "  [file] $file"
    } else {
        Write-Warning "  [skip] $file (not found)"
    }
}

foreach ($dir in $dirs) {
    $srcDir = Join-Path $srcFull $dir
    if (-not (Test-Path -LiteralPath $srcDir)) {
        Write-Warning "  [skip] $dir/ (not found)"
        continue
    }
    $dstDir = Join-Path $dstFull $dir
    Write-Host "  [dir]  $dir/"

    # /MIR mirrors the tree (build-owned, so pruning stale files is desired).
    # /XD and /XF apply the .dockerignore exclusions. /NFL /NDL /NP /NJH /NJS
    # keep output quiet; robocopy exit codes 0-7 indicate success.
    # /R and /W cap retries so a locked/inaccessible file fails fast instead of
    # hanging on robocopy's default 1,000,000 retries.
    $roboArgs = @($srcDir, $dstDir, '/MIR', '/R:2', '/W:2', '/NFL', '/NDL', '/NP', '/NJH', '/NJS')
    $roboArgs += '/XD'; $roboArgs += $excludeDirs
    $roboArgs += '/XF'; $roboArgs += $excludeFiles

    # Capture output rather than discarding it, so a failure can be explained.
    $roboOut = robocopy @roboArgs 2>&1
    if ($LASTEXITCODE -ge 8) {
        $detail = ($roboOut | Where-Object { $_ -match '\S' }) -join "`n"
        throw "robocopy failed for '$dir' with exit code $LASTEXITCODE`n$detail"
    }
}

# robocopy leaves $LASTEXITCODE non-zero on success; reset so callers don't
# misread this script as failed.
$global:LASTEXITCODE = 0
Write-Host "`nDone." -ForegroundColor Green
