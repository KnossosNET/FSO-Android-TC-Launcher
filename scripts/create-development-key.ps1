$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$key = Join-Path $root 'signing/development.jks'
$config = Join-Path $root 'signing.properties'
if ((Test-Path -LiteralPath $key) -or (Test-Path -LiteralPath $config)) {
    throw 'Signing is already configured. Existing signing files will not be replaced.'
}
New-Item -ItemType Directory -Path (Split-Path -Parent $key) -Force | Out-Null
& keytool -genkeypair -keystore $key -storepass android -keypass android -alias development -keyalg RSA -keysize 3072 -validity 36500 -dname 'CN=FSO Launcher Development'
if ($LASTEXITCODE -ne 0) { throw 'Key generation failed' }
@'
storeFile=signing/development.jks
storePassword=android
keyAlias=development
keyPassword=android
'@ | Set-Content -LiteralPath $config -Encoding ascii
Write-Output 'Development signing is configured. Use a private production key before store distribution.'
