param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("migrate", "seed:system", "seed:demo", "reset:dev")]
    [string]$Task
)

$maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $maven) {
    $maven = Get-Command mvn -ErrorAction SilentlyContinue
}
if (-not $maven) {
    throw "Không tìm thấy Maven. Hãy cài Maven và thử lại."
}

$projectPath = Join-Path $PSScriptRoot "..\pom.xml"
& $maven.Source `
    -f $projectPath `
    "-Dspring-boot.run.main-class=com.synera.api.seed.DatabaseTaskApplication" `
    "-Dspring-boot.run.arguments=--app.task=$Task" `
    spring-boot:run

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
