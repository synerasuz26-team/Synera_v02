param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("migrate", "seed:system", "seed:demo", "reset:dev", "purge:demo:plan", "purge:demo")]
    [string]$Task,
    [string]$MavenPath = $env:MAVEN_PATH
)

if (-not $MavenPath) {
    $maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (-not $maven) {
        $maven = Get-Command mvn -ErrorAction SilentlyContinue
    }
    if ($maven) {
        $MavenPath = $maven.Source
    }
}
if (-not $MavenPath -or -not (Test-Path -LiteralPath $MavenPath)) {
    throw "Không tìm thấy Maven. Cài Maven, thêm vào PATH hoặc đặt MAVEN_PATH."
}

$projectPath = Join-Path $PSScriptRoot "..\pom.xml"
& $MavenPath `
    -f $projectPath `
    "-Dspring-boot.run.main-class=com.synera.api.seed.DatabaseTaskApplication" `
    "-Dspring-boot.run.arguments=--app.task=$Task" `
    spring-boot:run

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
