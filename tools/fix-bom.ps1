param([string[]]$Files)
$enc = New-Object System.Text.UTF8Encoding($true)
foreach ($f in $Files) {
    if (-not (Test-Path $f)) { Write-Host "missing $f"; continue }
    $t = [IO.File]::ReadAllText($f)
    $t = $t.TrimStart([char]0xFEFF)
    [IO.File]::WriteAllText($f, $t, $enc)
    Write-Host "fixed $f"
}
