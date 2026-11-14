param([string]$MavenPath = '', [switch]$SkipPerformance,[switch]$AcceptanceOnly)
$ErrorActionPreference = 'Stop'
$p7Workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location -LiteralPath $p7Workspace
if (-not $MavenPath) {
    $p7MavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($p7MavenCommand) { $MavenPath = $p7MavenCommand.Source }
    else { $MavenPath = 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd' }
}
if (-not (Test-Path -LiteralPath $MavenPath)) { throw 'Set -MavenPath to your Maven executable.' }
$p7RunId = [Guid]::NewGuid().ToString('N').Substring(0,12)
$p7DbName = 'synera-p7-' + $p7RunId
$p7StorageName = 'synera-p7-s3-' + $p7RunId
$p7Server = $null
$p7Succeeded = $false
New-Item -ItemType Directory -Path backend/target/p7 -Force | Out-Null
function Invoke-P7Maven([string[]]$Arguments,[string]$Log) {
    $ErrorActionPreference='Continue'
    & $MavenPath -f backend/pom.xml @Arguments *> $Log
    $p7Exit=$LASTEXITCODE
    $ErrorActionPreference='Stop'
    if ($p7Exit -ne 0) { Get-Content -LiteralPath $Log -Tail 30; throw "Maven failed; inspect $Log" }
}
try {
    $p7Password = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
    docker run --name $p7DbName --label "synera.p7.run=$p7RunId" --tmpfs /var/lib/postgresql/data -e "POSTGRES_PASSWORD=$p7Password" -e POSTGRES_DB=synera_p7 -p 127.0.0.1::5432 -d postgres:16-alpine -c shared_preload_libraries=pg_stat_statements | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot start disposable PostgreSQL.' }
    docker run --name $p7StorageName --label "synera.p7.run=$p7RunId" -p 127.0.0.1::5000 -d motoserver/moto:5.1.16 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot start disposable S3 emulator.' }
    $p7Ready = $false
    for ($p7Attempt=0;$p7Attempt -lt 30;$p7Attempt++) {
        docker exec $p7DbName pg_isready -U postgres *> $null
        if ($LASTEXITCODE -eq 0) { $p7Ready=$true;break }
        Start-Sleep -Seconds 1
    }
    if (-not $p7Ready) { throw 'PostgreSQL readiness timeout.' }
    foreach ($p7Database in @('synera_p5','synera_p6')) {
        docker exec $p7DbName createdb -U postgres $p7Database
        if ($LASTEXITCODE -ne 0) { throw 'Cannot create isolated regression database.' }
    }
    $p7Endpoint=(docker port $p7DbName 5432/tcp).Trim()
    $env:P7_TEST_RUN='1';$env:P7_DB_CONTAINER=$p7DbName;$env:P7_RUN_ID=$p7RunId
    $env:P7_TEST_DB_URL='jdbc:postgresql://'+$p7Endpoint+'/synera_p7'
    $env:P7_TEST_DB_USER='postgres';$env:P7_TEST_DB_PASSWORD=$p7Password;$env:P7_TEST_PASSWORD=$p7Password
    $env:P7_MAVEN=$MavenPath
    $env:MAVEN_OPTS='-Xmx512m -XX:ActiveProcessorCount=4'
    $env:P5_TEST_DB_URL='jdbc:postgresql://'+$p7Endpoint+'/synera_p5';$env:P5_TEST_DB_USER='postgres';$env:P5_TEST_DB_PASSWORD=$p7Password
    $env:P6_TEST_DB_URL='jdbc:postgresql://'+$p7Endpoint+'/synera_p6';$env:P6_TEST_DB_USER='postgres';$env:P6_TEST_DB_PASSWORD=$p7Password
    $env:P6_S3_ENDPOINT='http://'+(docker port $p7StorageName 5000/tcp).Trim()
    $env:FEATURE_PHOTOS='true'
    $env:P7_HARNESS_ACTION='init'
    Write-Output 'P7: validating backend and building isolated fixtures...'
    if($AcceptanceOnly){
        $p7BuiltJar=Get-Item -LiteralPath backend/target/synera-api-0.1.0.jar
        $p7Stale=@(Get-ChildItem -LiteralPath backend/src/main -File -Recurse|Where-Object{$_.LastWriteTimeUtc -gt $p7BuiltJar.LastWriteTimeUtc})
        if($p7Stale.Count -gt 0 -or (Get-Item backend/pom.xml).LastWriteTimeUtc -gt $p7BuiltJar.LastWriteTimeUtc){throw 'Backend inputs changed; run full P7 before -AcceptanceOnly.'}
        Invoke-P7Maven @('-Dtest=P7HarnessTest','-DargLine=-Xmx512m -XX:ActiveProcessorCount=4','test') 'backend/target/p7/fixture-init.log'
    }else{Invoke-P7Maven @('verify','-DargLine=-Xmx512m -XX:ActiveProcessorCount=4') 'backend/target/p7/backend-tests.log'}
    $ErrorActionPreference='Continue'
    $p7JavaProperties=& java -XshowSettings:properties -version 2>&1
    $ErrorActionPreference='Stop'
    $p7JavaHomeLine=$p7JavaProperties|Where-Object{[string]$_ -match '^\s*java.home\s*='}|Select-Object -First 1
    if(-not $p7JavaHomeLine){throw 'Cannot resolve real Java runtime.'}
    $p7JavaHome=([string]$p7JavaHomeLine -split '=',2)[1].Trim()
    $p7JavaExecutable=Join-Path $p7JavaHome 'bin\java.exe'
    $p7RuntimeJar=Join-Path $p7Workspace ('backend\target\p7\runtime-'+$p7RunId+'.jar')
    Copy-Item -LiteralPath backend/target/synera-api-0.1.0.jar -Destination $p7RuntimeJar
    $p7Listener=[System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback,0)
    $p7Listener.Start();$p7Port=$p7Listener.LocalEndpoint.Port;$p7Listener.Stop()
    $env:DB_URL=$env:P7_TEST_DB_URL;$env:DB_USER='postgres';$env:DB_PASSWORD=$p7Password
    $env:APP_ENV='test';$env:DB_ENVIRONMENT='test';$env:SEED_DEMO='false';$env:APP_JOBS_ENABLED='false';$env:OPERATIONS_ENABLED='false'
    $env:AUTH_JWT_SECRET=[Guid]::NewGuid().ToString('N')+[Guid]::NewGuid().ToString('N')
    $env:PORT=[string]$p7Port;$env:SERVER_ADDRESS='127.0.0.1';$env:DB_POOL_SIZE='8'
    $env:APP_CORS_ALLOWED_ORIGINS='http://127.0.0.1:4177'
    $env:LOGGING_LEVEL_ROOT='WARN';$env:DEBUG='false'
    $env:P7_TEST_API_URL='http://127.0.0.1:'+$p7Port
    $env:VITE_API_URL=$env:P7_TEST_API_URL;$env:VITE_USE_MOCK='false'
    $env:P7_SKIP_PERFORMANCE=if($SkipPerformance){'true'}else{'false'}
    $p7Server=Start-Process -FilePath $p7JavaExecutable -ArgumentList @('-Xmx512m','-XX:ActiveProcessorCount=4','-jar',('"'+$p7RuntimeJar+'"')) -PassThru -WindowStyle Hidden -RedirectStandardOutput backend/target/p7/server.log -RedirectStandardError backend/target/p7/server-error.log
    $p7Ready=$false
    for($p7Attempt=0;$p7Attempt -lt 60;$p7Attempt++) {
        if($p7Server.HasExited){throw 'P7 API exited; inspect server-error.log.'}
        try { $p7Health=Invoke-WebRequest -Uri ($env:P7_TEST_API_URL+'/actuator/health') -UseBasicParsing; if($p7Health.StatusCode -eq 200){$p7Ready=$true;break} } catch {}
        Start-Sleep -Seconds 1
    }
    if(-not $p7Ready){throw 'P7 API readiness timeout.'}
    Write-Output 'P7: running browser/API scenarios and database assertions...'
    node scripts/p7-e2e.mjs
    if($LASTEXITCODE -ne 0){throw 'P7 acceptance failed; inspect backend/target/p7/results.json.'}
    Remove-Item Env:VITE_API_URL -ErrorAction SilentlyContinue
    npm.cmd test
    if($LASTEXITCODE -ne 0){throw 'Frontend unit tests failed.'}
    npm.cmd run build
    if($LASTEXITCODE -ne 0){throw 'Default production build failed.'}
    npm.cmd run check:p4
    if($LASTEXITCODE -ne 0){throw 'Production mock check failed.'}
    $p7Succeeded=$true
} finally {
    if($p7Server -and -not $p7Server.HasExited){Stop-Process -Id $p7Server.Id}
    foreach($p7ContainerName in @($p7DbName,$p7StorageName)){
        $ErrorActionPreference='Continue'
        $p7InspectRaw=docker inspect $p7ContainerName 2>$null
        $ErrorActionPreference='Stop'
        if($LASTEXITCODE -eq 0){
            $p7Inspect=($p7InspectRaw|ConvertFrom-Json)[0]
            if($p7Inspect.Config.Labels.'synera.p7.run' -ne $p7RunId){throw 'Refusing to clean an unowned container.'}
            docker rm -f $p7Inspect.Id | Out-Null
        }
    }
    $p7Cleanup=@{runId=$p7RunId;containersRemoved=$true;succeeded=$p7Succeeded;finishedAt=[DateTime]::UtcNow.ToString('o')}|ConvertTo-Json
    [System.IO.File]::WriteAllText((Join-Path $p7Workspace 'backend/target/p7/cleanup.json'),$p7Cleanup)
    Write-Output 'P7: disposable containers cleaned; reports remain in backend/target/p7.'
}
