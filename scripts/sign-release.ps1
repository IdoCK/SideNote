param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$BuildTools = '37.0.0'
)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
$signingDir = Join-Path $projectDir '.signing'
$releaseDir = Join-Path $projectDir 'releases'
$keyFile = Join-Path $signingDir 'sidenote-release.jks'
$passwordFile = Join-Path $signingDir 'password.xml'
$unsigned = Join-Path $projectDir 'app/build/outputs/apk/release/app-release-unsigned.apk'
$output = Join-Path $releaseDir 'SideNote-1.0.apk'
if (!(Test-Path $unsigned)) { throw 'Build assembleRelease first.' }
if ((Test-Path $keyFile) -and !(Test-Path $passwordFile)) { throw 'Existing key has no saved password. Restore it; do not replace the key.' }
New-Item -ItemType Directory -Force $signingDir, $releaseDir | Out-Null
if (!(Test-Path $passwordFile)) {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $securePassword = ConvertTo-SecureString ([Convert]::ToBase64String($bytes)) -AsPlainText -Force
    $securePassword | Export-Clixml -LiteralPath $passwordFile
}
$securePassword = Import-Clixml -LiteralPath $passwordFile
$credential = New-Object System.Net.NetworkCredential('', $securePassword)
$env:SIDENOTE_SIGNING_PASSWORD = $credential.Password
$env:JAVA_HOME = $JavaHome
try {
    if (!(Test-Path $keyFile)) {
        & "$JavaHome/bin/keytool.exe" -genkeypair -keystore $keyFile -storetype JKS -alias sidenote -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=SideNote' -storepass:env SIDENOTE_SIGNING_PASSWORD -keypass:env SIDENOTE_SIGNING_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Signing key creation failed.' }
    }
    $toolsDir = Join-Path $AndroidHome "build-tools/$BuildTools"
    $aligned = Join-Path $releaseDir 'sidenote-aligned.apk'
    & "$toolsDir/zipalign.exe" -f -P 16 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
    & "$toolsDir/apksigner.bat" sign --ks $keyFile --ks-key-alias sidenote --ks-pass env:SIDENOTE_SIGNING_PASSWORD --key-pass env:SIDENOTE_SIGNING_PASSWORD --out $output $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & "$toolsDir/apksigner.bat" verify --verbose --print-certs $output
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    & "$toolsDir/zipalign.exe" -c -P 16 4 $output
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK alignment verification failed.' }
    Get-FileHash -LiteralPath $output -Algorithm SHA256
} finally {
    Remove-Item Env:SIDENOTE_SIGNING_PASSWORD -ErrorAction SilentlyContinue
}
