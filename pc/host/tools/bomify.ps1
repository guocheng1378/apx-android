param([string]$P)
$c = [IO.File]::ReadAllText($P, [Text.Encoding]::UTF8)
[IO.File]::WriteAllText($P, $c, [Text.Encoding]::UTF8)
'BOM_OK'
