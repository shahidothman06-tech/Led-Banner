# Creates the LED Banner upload key ONCE and saves it in two places.
#
#   1. This PC, inside the project folder (git-ignored, never uploaded):
#        keystore\ledbanner-upload.jks   the key itself
#        keystore\info.txt               alias, password, fingerprints, where things are
#        keystore.properties             lets Gradle sign release builds on this PC
#
#   2. GitHub repository secrets, so GitHub Actions signs its .aab:
#        LEDBANNER_KEYSTORE_BASE64       the .jks file as base64
#        LEDBANNER_KEYSTORE_PASSWORD     the password
#
# Run it from the project folder:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\create-upload-key.ps1
# Add -GeneratePassword to have a strong random password made for you instead of typing one.
# It is saved in keystore\info.txt and never shown on screen.

param([switch]$GeneratePassword)

$ErrorActionPreference = 'Stop'
$repoSlug = 'shahidothman06-tech/Led-Banner'
$alias    = 'ledbanner-upload'
$root     = Split-Path -Parent $PSScriptRoot
$keyDir   = Join-Path $root 'keystore'
$jks      = Join-Path $keyDir 'ledbanner-upload.jks'
$info     = Join-Path $keyDir 'info.txt'
$props    = Join-Path $root 'keystore.properties'

function Fail($msg) { Write-Host ""; Write-Host $msg -ForegroundColor Red; exit 1 }
function Plain($secure) {
    [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}
# Runs a program (keytool, git, gh). Windows PowerShell turns their normal progress text on
# stderr into errors when output is captured; here it is just printed. Check $LASTEXITCODE after.
function Invoke-Native([scriptblock]$Command) {
    $ErrorActionPreference = 'Continue'
    & $Command 2>&1 | ForEach-Object {
        if ($_ -is [Management.Automation.ErrorRecord]) { Write-Host $_.Exception.Message } else { $_ }
    }
}

# --- Checks -----------------------------------------------------------------
$keytool = @(
    "$env:ProgramFiles\Android\Android Studio\jbr\bin\keytool.exe",
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr\bin\keytool.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $keytool) { $keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source }
if (-not $keytool) { Fail 'keytool was not found. Install Android Studio first.' }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Fail 'The GitHub CLI (gh) was not found.' }

# Never replace an existing key: Play Console needs the same key for every update.
if (Test-Path $jks) { Fail "A key already exists at $jks. Nothing was changed." }

# The repository is public, so make sure git will never upload the key files.
Push-Location $root
try {
    Invoke-Native { git check-ignore -q keystore/ledbanner-upload.jks }; $ignoredKey = $LASTEXITCODE
    Invoke-Native { git check-ignore -q keystore.properties };          $ignoredProps = $LASTEXITCODE
} finally { Pop-Location }
if ($ignoredKey -ne 0 -or $ignoredProps -ne 0) {
    Fail '.gitignore does not cover keystore/ and keystore.properties. Stopped so the key cannot be uploaded.'
}

# --- Password ---------------------------------------------------------------
Write-Host ''
Write-Host 'LED Banner upload key' -ForegroundColor Cyan
if ($GeneratePassword) {
    # 24 random letters and digits, skipping look-alikes (I, O, l, 0, 1) so it is easy to type.
    $chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $limit = 256 - (256 % $chars.Length)   # avoids modulo bias
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $buf = New-Object byte[] 1
    $pw = ''
    while ($pw.Length -lt 24) {
        $rng.GetBytes($buf)
        if ($buf[0] -lt $limit) { $pw += $chars[$buf[0] % $chars.Length] }
    }
    $rng.Dispose()
    Write-Host 'Made a random 24-character password. It goes into keystore\info.txt and is not shown here.'
}
else {
Write-Host 'Choose a password. You need it for every Play Store release, so do not lose it.'
Write-Host 'At least 6 characters: English letters, numbers and symbols. No spaces or backslashes.'
Write-Host '(Nothing shows on screen while you type. That is normal.)'
while ($true) {
    $pw  = Plain (Read-Host 'Password' -AsSecureString)
    $pw2 = Plain (Read-Host 'Type it again' -AsSecureString)
    if ($pw -cne $pw2)               { Write-Host "The two passwords don't match. Try again." -ForegroundColor Yellow; continue }
    if ($pw.Length -lt 6)            { Write-Host 'Too short. Use at least 6 characters.' -ForegroundColor Yellow; continue }
    if ($pw -notmatch '^[!-~]+$' -or $pw.Contains('\')) {
        Write-Host 'Use only English letters, numbers and symbols, with no spaces or backslashes.' -ForegroundColor Yellow; continue
    }
    break
}
$pw2 = $null
}

# --- 1. Create the key on this PC --------------------------------------------
New-Item -ItemType Directory -Force $keyDir | Out-Null

# Files are written without a BOM so Gradle reads them correctly.
# keystore.properties is written first, so the password is on disk before the key exists.
$utf8 = New-Object System.Text.UTF8Encoding $false
[IO.File]::WriteAllText($props, (@(
    'storeFile=keystore/ledbanner-upload.jks'
    "storePassword=$pw"
    "keyAlias=$alias"
    "keyPassword=$pw"
) -join "`n") + "`n", $utf8)

$env:LEDBANNER_KEY_PW = $pw
try {
    Write-Host ''
    Write-Host 'Creating the key...' -ForegroundColor Cyan
    Invoke-Native {
        & $keytool -genkeypair -keystore $jks -storetype PKCS12 -alias $alias `
            -keyalg RSA -keysize 2048 -validity 10000 `
            -dname 'CN=LED Banner, O=Tubers' -storepass:env LEDBANNER_KEY_PW
    }
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $jks)) { Fail 'keytool could not create the key. Run the script again.' }
    $listing = Invoke-Native { & $keytool -list -v -keystore $jks -alias $alias -storepass:env LEDBANNER_KEY_PW }
} finally {
    Remove-Item Env:\LEDBANNER_KEY_PW -ErrorAction SilentlyContinue
}
function Fingerprint($name) {
    $m = $listing | Select-String "$($name):\s*(\S+)" | Select-Object -First 1
    if ($m) { $m.Matches[0].Groups[1].Value } else { '(not read; run keytool -list -v to see it)' }
}
$sha1   = Fingerprint 'SHA1'
$sha256 = Fingerprint 'SHA256'

$today = Get-Date -Format 'yyyy-MM-dd'
[IO.File]::WriteAllText($info, (@(
    'LED Banner - Google Play upload key'
    '==================================='
    'PRIVATE. Never upload or share this folder.'
    ''
    "Created         : $today"
    'App package     : tubers.ledbanner.ledlight'
    'Keystore file   : keystore\ledbanner-upload.jks'
    'Keystore type   : PKCS12'
    "Key alias       : $alias"
    "Keystore password: $pw"
    "Key password     : $pw   (same as the keystore password)"
    "SHA-1           : $sha1"
    "SHA-256         : $sha256"
    ''
    'Android Studio > Build > Generate Signed App Bundle: use the file, alias and password above.'
    'keystore.properties in the project folder holds the same details, so Gradle signs release builds on this PC.'
    ''
    'GitHub secrets (Settings > Secrets and variables > Actions):'
    "  https://github.com/$repoSlug/settings/secrets/actions"
    '  LEDBANNER_KEYSTORE_BASE64    = this .jks file as base64'
    '  LEDBANNER_KEYSTORE_PASSWORD  = the password above'
    'GitHub cannot show secrets again. This folder is your copy, so back it up (USB drive or cloud).'
) -join "`r`n") + "`r`n", $utf8)

Write-Host 'Saved on this PC:' -ForegroundColor Green
Write-Host "  $jks"
Write-Host "  $info"
Write-Host "  $props"

# --- 2. Save to GitHub secrets ------------------------------------------------
Write-Host ''
Write-Host 'Saving to GitHub secrets...' -ForegroundColor Cyan
$OutputEncoding = [Text.Encoding]::ASCII
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($jks))
Invoke-Native { $b64 | gh secret set LEDBANNER_KEYSTORE_BASE64 --repo $repoSlug }
if ($LASTEXITCODE -ne 0) { Fail 'Could not save LEDBANNER_KEYSTORE_BASE64. The key is still saved on this PC.' }
Invoke-Native { $pw | gh secret set LEDBANNER_KEYSTORE_PASSWORD --repo $repoSlug }
if ($LASTEXITCODE -ne 0) { Fail 'Could not save LEDBANNER_KEYSTORE_PASSWORD. The key is still saved on this PC.' }
$pw = $null; $b64 = $null

# --- 3. Rebuild on GitHub so the latest .aab is signed --------------------------
Invoke-Native { gh workflow run build.yml --repo $repoSlug --ref main }
if ($LASTEXITCODE -eq 0) { Write-Host 'Started a new GitHub build. It will produce a signed .aab.' -ForegroundColor Green }

Write-Host ''
Write-Host 'All done.' -ForegroundColor Green
Write-Host "Key alias: $alias"
Write-Host "Password, alias and fingerprints are in: $info"
Write-Host 'Back up the keystore folder somewhere safe. If this PC is lost, GitHub cannot give the key back.' -ForegroundColor Yellow
