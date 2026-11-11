$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    $knownMaven = 'C:\Tools\apache-maven\apache-maven-3.10.0\bin'
    if (Test-Path (Join-Path $knownMaven 'mvn.cmd')) {
        $env:Path = "$knownMaven;$env:Path"
    } else {
        throw 'Maven was not found. Install Maven and add its bin folder to PATH.'
    }
}

if (-not $env:DB_URL) { $env:DB_URL = 'jdbc:mariadb://localhost:3306/ihbas_db' }
if (-not $env:DB_USERNAME) { $env:DB_USERNAME = 'root' }
if ($null -eq $env:DB_PASSWORD) { $env:DB_PASSWORD = '' }
if (-not $env:JWT_EXPIRATION_MINUTES) { $env:JWT_EXPIRATION_MINUTES = '30' }
if (-not $env:OTP_DEV_EXPOSE_CODE) { $env:OTP_DEV_EXPOSE_CODE = 'false' }

Write-Host 'Starting IHBAS. Keep XAMPP MariaDB running.' -ForegroundColor Cyan
mvn clean spring-boot:run
