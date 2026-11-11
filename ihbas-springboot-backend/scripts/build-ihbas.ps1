$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    $knownMaven = 'C:\Tools\apache-maven\apache-maven-3.10.0\bin'
    if (Test-Path (Join-Path $knownMaven 'mvn.cmd')) { $env:Path = "$knownMaven;$env:Path" }
    else { throw 'Maven was not found. Install Maven and add its bin folder to PATH.' }
}
mvn clean package
