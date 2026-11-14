param([Parameter(Mandatory=$true)][string]$Path)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Path))
try {
    function Read-Xml([string]$name) {
        $entry = $archive.GetEntry($name)
        if ($null -eq $entry) { return $null }
        $reader = [System.IO.StreamReader]::new($entry.Open())
        try { return [xml]$reader.ReadToEnd() } finally { $reader.Dispose() }
    }
    $strings = @()
    $shared = Read-Xml 'xl/sharedStrings.xml'
    if ($shared) { $strings = @($shared.SelectNodes("//*[local-name()='si']") | ForEach-Object { $_.InnerText }) }
    $book = Read-Xml 'xl/workbook.xml'
    $rels = Read-Xml 'xl/_rels/workbook.xml.rels'
    $result = @()
    foreach ($sheet in $book.SelectNodes("//*[local-name()='sheet']")) {
        $rid = $sheet.GetAttribute('id', 'http://schemas.openxmlformats.org/officeDocument/2006/relationships')
        $relation = $rels.SelectNodes("//*[local-name()='Relationship']") | Where-Object { $_.Id -eq $rid }
        $target = [string]$relation.Target
        if ($target.StartsWith('/')) { $target = $target.TrimStart('/') } else { $target = 'xl/' + $target }
        $xml = Read-Xml $target
        $rows = @($xml.SelectNodes("//*[local-name()='sheetData']/*[local-name()='row']"))
        $firstRows = @()
        foreach ($row in ($rows | Select-Object -First 3)) {
            $values = @()
            foreach ($cell in $row.SelectNodes("*[local-name()='c']")) {
                $value = $cell.SelectSingleNode("*[local-name()='v']")
                if ($cell.t -eq 's') { $text = $strings[[int]$value.InnerText] }
                elseif ($cell.t -eq 'inlineStr') { $text = $cell.SelectSingleNode("*[local-name()='is']").InnerText }
                elseif ($value) { $text = $value.InnerText } else { $text = '' }
                $values += [ordered]@{cell=$cell.r; value=$text}
            }
            $firstRows += ,$values
        }
        # Account source rows may contain credentials. Only report headers for that sheet.
        if ($sheet.name -match 'users|nguoi_dung') { $firstRows = @($firstRows | Select-Object -First 1) }
        $result += [ordered]@{name=[string]$sheet.name; rowCount=$rows.Count; firstRows=$firstRows}
    }
    $result | ConvertTo-Json -Depth 8
} finally { $archive.Dispose() }
