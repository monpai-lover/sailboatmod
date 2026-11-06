param(
    [string]$RuntimeClasspathFile,
    [string]$BaselineAddonJar,
    [string]$FixtureCache,
    [int]$RegionX = 0,
    [int]$RegionZ = 0,
    [int]$Warmup = 1,
    [int]$Iterations = 3,
    [int]$MaxMilliseconds = 30000,
    [string]$OutputRoot = 'build/audit/render-speed-20261104/results',
    [switch]$Run
)
$ErrorActionPreference = 'Stop'
$renderRepositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
Push-Location -LiteralPath $renderRepositoryRoot
try {
    if ($RuntimeClasspathFile) {
        if (-not (Test-Path -LiteralPath $RuntimeClasspathFile)) { throw "Missing runtime classpath: $RuntimeClasspathFile" }
        $renderClasspath = (Get-Content -Raw -LiteralPath $RuntimeClasspathFile).Trim()
    } else {
        $renderLegacyClasspath = 'build/moddev/junit/legacyClasspath.txt'
        if (-not (Test-Path -LiteralPath $renderLegacyClasspath)) {
            throw 'Run ./gradlew test first so mapped classes and the test runtime classpath are available.'
        }
        $renderClasspathEntries = @((Join-Path $renderRepositoryRoot 'build/classes/java/main'))
        $renderClasspathEntries += @(Get-ChildItem -LiteralPath 'build/moddev/artifacts' -Filter 'neoforge-*.jar' | ForEach-Object FullName)
        $renderClasspathEntries += @(Get-Content -LiteralPath $renderLegacyClasspath)
        $renderClasspath = $renderClasspathEntries -join [System.IO.Path]::PathSeparator
    }
    if ($BaselineAddonJar) {
        $renderBaselineJar = (Resolve-Path -LiteralPath $BaselineAddonJar).Path
        $renderClasspath = $renderBaselineJar + [System.IO.Path]::PathSeparator + $renderClasspath
    }
    $renderClasses = Join-Path $renderRepositoryRoot 'build/audit/render-speed-20261104/classes'
    $renderEmptySource = Join-Path $renderRepositoryRoot 'build/audit/render-speed-20261104/empty-source'
    New-Item -ItemType Directory -Force -Path $renderClasses, $renderEmptySource | Out-Null
    & javac -proc:none -sourcepath $renderEmptySource -cp $renderClasspath -d $renderClasses (Join-Path $PSScriptRoot 'MapRenderThroughputBenchmark.java')
    if ($LASTEXITCODE -ne 0) { throw "Benchmark compilation failed: $LASTEXITCODE" }
    if ($Run) {
        $renderArguments = @('--output', $OutputRoot, '--region-x', "$RegionX", '--region-z', "$RegionZ",
                '--warmup', "$Warmup", '--iterations', "$Iterations", '--max-ms', "$MaxMilliseconds")
        if ($FixtureCache) { $renderArguments += @('--fixture-cache', $FixtureCache) }
        & java '-Djava.awt.headless=true' -cp "$renderClasses;$renderClasspath" com.monpai.sailboatmod.market.web.map.MapRenderThroughputBenchmark @renderArguments
        if ($LASTEXITCODE -ne 0) { throw "Benchmark failed: $LASTEXITCODE" }
    } else {
        Write-Output "Benchmark compiled. Add -Run to measure; production sources and saved-world cache are untouched."
    }
} finally { Pop-Location }
