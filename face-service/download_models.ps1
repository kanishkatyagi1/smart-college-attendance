# Downloads the exact local models required by the standalone face service.
# No placeholder is created: a failed download leaves the service fail-closed.
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$modelsDirectory = Join-Path $PSScriptRoot 'models'
New-Item -ItemType Directory -Force -Path $modelsDirectory | Out-Null

function Test-ValidModel {
    param([string]$Path, [long]$MinimumBytes)
    return (Test-Path -LiteralPath $Path -PathType Leaf) -and ((Get-Item -LiteralPath $Path).Length -ge $MinimumBytes)
}

function Download-Model {
    param(
        [string]$Name,
        [string]$FileName,
        [string[]]$Urls,
        [long]$MinimumBytes
    )

    $outputPath = Join-Path $modelsDirectory $FileName
    if (Test-ValidModel -Path $outputPath -MinimumBytes $MinimumBytes) {
        Write-Host "[OK] $Name already exists ($((Get-Item -LiteralPath $outputPath).Length) bytes)"
        return
    }

    # A zero-byte/partial earlier transfer must not be mistaken for a model.
    if (Test-Path -LiteralPath $outputPath) {
        Remove-Item -LiteralPath $outputPath -Force
    }

    foreach ($url in $Urls) {
        Write-Host "[DOWNLOAD] $Name"
        Write-Host "  $url"
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            $temporaryPath = "$outputPath.download"
            try {
                Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
                Invoke-WebRequest -Uri $url -OutFile $temporaryPath -UseBasicParsing -TimeoutSec 120
                if (Test-ValidModel -Path $temporaryPath -MinimumBytes $MinimumBytes) {
                    Move-Item -LiteralPath $temporaryPath -Destination $outputPath -Force
                    Write-Host "[OK] $Name downloaded successfully ($((Get-Item -LiteralPath $outputPath).Length) bytes)"
                    return
                }
                throw "Downloaded file was empty or unexpectedly small."
            }
            catch {
                Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
                Write-Warning "Attempt $attempt of 3 failed for ${Name}: $($_.Exception.Message)"
                if ($attempt -lt 3) { Start-Sleep -Seconds (2 * $attempt) }
            }
        }
        Write-Warning "All attempts failed at this endpoint; trying fallback if available."
    }
    throw "Failed to download $Name from all configured binary endpoints."
}

# URLs are direct raw/binary endpoints only. The MiniFASNet ONNX conversion is
# Apache-2.0-derived from Minivision Silent-Face-Anti-Spoofing; see THIRD_PARTY_NOTICES.md.
$modelDefinitions = @(
    @{ Name = 'YuNet'; FileName = 'face_detection_yunet_2023mar.onnx'; MinimumBytes = 100000; Urls = @(
        'https://raw.githubusercontent.com/opencv/opencv_zoo/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx',
        'https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx'
    ) },
    @{ Name = 'SFace'; FileName = 'face_recognition_sface_2021dec.onnx'; MinimumBytes = 1000000; Urls = @(
        'https://raw.githubusercontent.com/opencv/opencv_zoo/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx',
        'https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx'
    ) },
    @{ Name = 'MediaPipe Face Landmarker'; FileName = 'face_landmarker.task'; MinimumBytes = 1000000; Urls = @(
        'https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task'
    ) },
    @{ Name = 'MiniFASNet V1SE'; FileName = '4_0_0_80x80_MiniFASNetV1SE.onnx'; MinimumBytes = 100000; Urls = @(
        'https://raw.githubusercontent.com/QingHeYang/Silent-Face-Anti-Spoofing-onnx/main/onnx/4_0_0_80x80_MiniFASNetV1SE.onnx',
        'https://github.com/QingHeYang/Silent-Face-Anti-Spoofing-onnx/raw/main/onnx/4_0_0_80x80_MiniFASNetV1SE.onnx'
    ) },
    @{ Name = 'MiniFASNet V2'; FileName = '2.7_80x80_MiniFASNetV2.onnx'; MinimumBytes = 100000; Urls = @(
        'https://raw.githubusercontent.com/QingHeYang/Silent-Face-Anti-Spoofing-onnx/main/onnx/2.7_80x80_MiniFASNetV2.onnx',
        'https://github.com/QingHeYang/Silent-Face-Anti-Spoofing-onnx/raw/main/onnx/2.7_80x80_MiniFASNetV2.onnx'
    ) }
)

Write-Host '========================================'
Write-Host ' Face Service Model Setup'
Write-Host '========================================'
foreach ($model in $modelDefinitions) {
    Download-Model @model
}
Write-Host ''
Write-Host '========================================'
Write-Host ' All required models are available.'
Write-Host '========================================'
