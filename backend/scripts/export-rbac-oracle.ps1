param([Parameter(Mandatory=$true)][string]$Path)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$p7BookPath=(Resolve-Path -LiteralPath $Path).Path
$p7Archive=[System.IO.Compression.ZipFile]::OpenRead($p7BookPath)
try{
    function Read-P7Xml([string]$Name){$entry=$p7Archive.GetEntry($Name);if(-not $entry){return $null};$reader=[System.IO.StreamReader]::new($entry.Open());try{return [xml]$reader.ReadToEnd()}finally{$reader.Dispose()}}
    $p7Strings=@();$p7Shared=Read-P7Xml 'xl/sharedStrings.xml'
    if($p7Shared){$p7Strings=@($p7Shared.SelectNodes("//*[local-name()='si']")|ForEach-Object{$_.InnerText})}
    $p7Book=Read-P7Xml 'xl/workbook.xml';$p7Rels=Read-P7Xml 'xl/_rels/workbook.xml.rels'
    function Read-P7Sheet([string]$Name){
        $sheet=$p7Book.SelectNodes("//*[local-name()='sheet']")|Where-Object{$_.name -eq $Name}
        $rid=$sheet.GetAttribute('id','http://schemas.openxmlformats.org/officeDocument/2006/relationships')
        $target=[string]($p7Rels.SelectNodes("//*[local-name()='Relationship']")|Where-Object{$_.Id -eq $rid}).Target
        if($target.StartsWith('/')){$target=$target.TrimStart('/')}else{$target='xl/'+$target}
        $xml=Read-P7Xml $target
        foreach($row in $xml.SelectNodes("//*[local-name()='sheetData']/*[local-name()='row']")){
            $values=@{}
            foreach($cell in $row.SelectNodes("*[local-name()='c']")){
                $letters=([string]$cell.r)-replace '[0-9]','';$column=0;foreach($letter in $letters.ToCharArray()){$column=$column*26+[int]$letter-64};$column--
                $v=$cell.SelectSingleNode("*[local-name()='v']");$text=''
                if($cell.t -eq 's'){$text=$p7Strings[[int]$v.InnerText]}elseif($cell.t -eq 'inlineStr'){$text=$cell.SelectSingleNode("*[local-name()='is']").InnerText}elseif($v){$text=$v.InnerText}
                $values[$column]=[string]$text
            }
            Write-Output -NoEnumerate $values
        }
    }
    $p7Matrix=@(Read-P7Sheet 'Matrix');$p7Roles=@();$p7Grants=[ordered]@{}
    foreach($column in @($p7Matrix[0].Keys|Sort-Object)) {if($column -ge 2){$role=$p7Matrix[0][$column].Trim();$p7Roles+=$role;$p7Grants[$role]=[ordered]@{}}}
    $p7Permissions=@()
    foreach($row in ($p7Matrix|Select-Object -Skip 1)){
        $permission=([string]$row[0]).Trim();if($permission -notmatch '^[a-z0-9_]+:[a-z0-9_]+$'){continue};$p7Permissions+=$permission
        for($i=0;$i -lt $p7Roles.Count;$i++){$scope=([string]$row[$i+2]).Trim();if($scope -match '^[ARTO]$'){$p7Grants[$p7Roles[$i]][$permission]=$scope}elseif($scope -notin @('','-','—','–')){throw "Unsupported matrix value at $permission / $($p7Roles[$i]): $scope"}}
    }
    $p7Sensitive=[ordered]@{}
    foreach($row in (@(Read-P7Sheet 'Permissions')|Select-Object -Skip 1)){$code=([string]$row[0]).Trim();if($code){$p7Sensitive[$code]=(([string]$row[3]).Trim() -eq 'Y')}}
    $p7Oracle=[ordered]@{source=[System.IO.Path]::GetFileName($p7BookPath);sourceSha256=(Get-FileHash -LiteralPath $p7BookPath -Algorithm SHA256).Hash.ToLowerInvariant();roles=$p7Roles;permissions=$p7Permissions;grants=$p7Grants;sensitive=$p7Sensitive}
    $p7Destination=Join-Path $PSScriptRoot '../src/test/resources/acceptance/rbac-oracle.json'
    New-Item -ItemType Directory -Path (Split-Path $p7Destination -Parent) -Force|Out-Null
    [System.IO.File]::WriteAllText($p7Destination,($p7Oracle|ConvertTo-Json -Depth 6)+[Environment]::NewLine,[System.Text.UTF8Encoding]::new($false))
    Write-Output "RBAC oracle: $($p7Roles.Count) roles, $($p7Permissions.Count) permissions; source preserved."
}finally{$p7Archive.Dispose()}
