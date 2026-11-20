<#
  Keeps the newest LED Banner test APK and Play .aab on your Desktop.

  Run it once: right-click this file > Run with PowerShell.
  It downloads the files GitHub built from main (the "latest" release),
  then checks again every 15 minutes in the background through the Task
  Scheduler task "LED Banner Desktop Sync". A build you make yourself in
  Android Studio is kept whenever it is newer than GitHub's.

  To stop it:
    Unregister-ScheduledTask -TaskName 'LED Banner Desktop Sync' -Confirm:$false
#>
param([switch]$Background)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$repo     = 'shahidothman06-tech/Led-Banner'
$taskName = 'LED Banner Desktop Sync'
$desktop  = [Environment]::GetFolderPath('Desktop')   # follows OneDrive
$headers  = @{ 'User-Agent' = 'ledbanner-desktop-sync' }

function Sync-Builds {
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/tags/latest" -Headers $headers
    $newestAab = [datetime]::MinValue

    foreach ($asset in $release.assets) {
        if ($asset.name -notmatch '^LedBanner-(test\.apk|release-.+\.aab)$') { continue }
        $target  = Join-Path $desktop $asset.name
        $updated = ([datetime]$asset.updated_at).ToUniversalTime()
        if ($asset.name -like '*.aab' -and $updated -gt $newestAab) { $newestAab = $updated }

        # Skip when the Desktop copy is already this build or newer (e.g. your own build).
        if ((Test-Path -LiteralPath $target) -and
            (Get-Item -LiteralPath $target).LastWriteTimeUtc -ge $updated) { continue }

        $temp = "$target.download"
        Invoke-WebRequest -Uri $asset.browser_download_url -Headers $headers -OutFile $temp -UseBasicParsing
        Move-Item -LiteralPath $temp -Destination $target -Force
        (Get-Item -LiteralPath $target).LastWriteTimeUtc = $updated
        Write-Host "Updated $target"
    }

    # .aab files from older versions go to the Recycle Bin.
    $current = @($release.assets | ForEach-Object { $_.name })
    Add-Type -AssemblyName Microsoft.VisualBasic
    Get-ChildItem -LiteralPath $desktop -Filter 'LedBanner-release-*.aab' |
        Where-Object { $current -notcontains $_.Name -and $_.LastWriteTimeUtc -lt $newestAab } |
        ForEach-Object {
            [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile(
                $_.FullName, 'OnlyErrorDialogs', 'SendToRecycleBin')
            Write-Host "Moved old $($_.Name) to the Recycle Bin"
        }
}

if (-not $Background) {
    # Install a copy under %LOCALAPPDATA% and run it every 15 minutes, hidden.
    $installDir = Join-Path $env:LOCALAPPDATA 'LedBannerDesktopSync'
    New-Item -ItemType Directory -Force -Path $installDir | Out-Null
    $installed = Join-Path $installDir 'desktop-sync.ps1'
    if ($PSCommandPath -ne $installed) { Copy-Item -LiteralPath $PSCommandPath -Destination $installed -Force }

    $action = New-ScheduledTaskAction -Execute 'conhost.exe' -Argument (
        "--headless powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass " +
        "-File `"$installed`" -Background")
    $trigger  = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
                    -RepetitionInterval (New-TimeSpan -Minutes 15)
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries `
                    -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Minutes 10)
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings `
        -Description 'Keeps the newest LED Banner test APK and .aab on the Desktop.' -Force | Out-Null
    Write-Host "Scheduled '$taskName' to check GitHub every 15 minutes."
}

try {
    Sync-Builds
} catch {
    if ($Background) { exit 1 }
    Write-Warning "Could not download the latest build: $_"
}

if (-not $Background) { Read-Host 'Done. Press Enter to close' }
