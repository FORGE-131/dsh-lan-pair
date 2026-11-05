# build.ps1 - build the DSH LAN Pair APK without Gradle.
#
# Pipeline: javac -> aapt2 link -> d8 -> insert classes.dex -> zipalign -> apksigner
# Only build-tools (aapt2, d8, zipalign, apksigner) and a platform android.jar are needed.
$ErrorActionPreference = 'Stop'

# build-tools r34 ships R8 8.2.x, which cannot run on a very new JDK (it breaks
# inside its own reflection on JDK 25). Use a portable JDK 21 when present.
$jdkRoot = 'C:\Users\a2010\jdk21'
if (Test-Path $jdkRoot) {
    $jdkDir = Get-ChildItem $jdkRoot -Directory -Filter 'jdk-21*' | Select-Object -First 1
    if ($jdkDir) {
        $env:JAVA_HOME = $jdkDir.FullName
        $env:PATH = (Join-Path $jdkDir.FullName 'bin') + ';' + $env:PATH
        Write-Host ("using JDK: " + $jdkDir.Name)
    }
}

$tools = 'C:\Users\a2010\android-tools'
$bt = Join-Path $tools 'build-tools\android-14'
$ajar = Join-Path $tools 'platform\android-35\android.jar'
$root = 'C:\Users\a2010\dsh-lan-pair\android'
$src = Join-Path $root 'src'
$build = Join-Path $root 'build'
$classes = Join-Path $build 'classes'
$dexdir = Join-Path $build 'dex'
$dist = Join-Path $root 'dist'
$ks = Join-Path $root 'dshpair.keystore'
$versionName = '0.4.2'
$versionCode = '9'
$minSdk = '26'
$targetSdk = '34'

foreach ($needed in @($bt, $ajar, $src)) {
    if (-not (Test-Path $needed)) { throw "missing: $needed" }
}

Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $dexdir, $dist | Out-Null

# 1. compile
$sources = Get-ChildItem $src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "[1/6] javac ($($sources.Count) files)"
& javac --release 8 -Xlint:-options -cp $ajar -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

# 2. resources + manifest -> base apk
Write-Host '[2/6] aapt2 compile + link'
$base = Join-Path $build 'base.apk'
$resDir = Join-Path $root 'res'
$resFlat = Join-Path $build 'resources.zip'
$linkArgs = @(
    'link', '-o', $base, '-I', $ajar,
    '--manifest', (Join-Path $root 'AndroidManifest.xml'),
    '--min-sdk-version', $minSdk, '--target-sdk-version', $targetSdk,
    '--version-code', $versionCode, '--version-name', $versionName
)
if (Test-Path $resDir) {
    & (Join-Path $bt 'aapt2.exe') compile --dir $resDir -o $resFlat
    if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
    $linkArgs += $resFlat
}
& (Join-Path $bt 'aapt2.exe') @linkArgs
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

# 3. dex
# build-tools r34 bundles R8 8.2, which crashes ("String.length() parameter1 is
# null") on any class carrying an EnclosingMethod attribute - i.e. every
# anonymous class javac 21 emits. A standalone R8 jar from Google Maven handles
# the same classes fine, so prefer it when present.
Write-Host '[3/6] d8'
$classFiles = Get-ChildItem $classes -Recurse -Filter *.class | ForEach-Object { $_.FullName }
$r8Jar = Get-ChildItem $tools -Filter 'r8-*.jar' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
if ($r8Jar) {
    Write-Host ("      using standalone " + $r8Jar.Name)
    & $javaExe -cp $r8Jar.FullName com.android.tools.r8.D8 --min-api $minSdk --lib $ajar --output $dexdir @classFiles
} else {
    & (Join-Path $bt 'd8.bat') --min-api $minSdk --lib $ajar --output $dexdir @classFiles
}
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
$dex = Join-Path $dexdir 'classes.dex'
if (-not (Test-Path $dex)) { throw "classes.dex not produced in $dexdir" }

# 4. put classes.dex into the apk
Write-Host '[4/6] add classes.dex'
$withDex = Join-Path $build 'with-dex.apk'
Copy-Item $base $withDex -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($withDex, 'Update')
try {
    $existing = $zip.GetEntry('classes.dex')
    if ($existing) { $existing.Delete() }
    $entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
    $out = $entry.Open()
    try {
        $bytes = [System.IO.File]::ReadAllBytes($dex)
        $out.Write($bytes, 0, $bytes.Length)
    } finally { $out.Dispose() }
} finally { $zip.Dispose() }

# 5. align
Write-Host '[5/6] zipalign'
$aligned = Join-Path $build 'aligned.apk'
& (Join-Path $bt 'zipalign.exe') -f -p 4 $withDex $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

# 6. sign
Write-Host '[6/6] apksigner'
if (-not (Test-Path $ks)) {
    Write-Host '      creating debug keystore'
    & keytool -genkeypair -keystore $ks -alias dshpair -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass dshpair -keypass dshpair `
        -dname 'CN=DSH LAN Pair, O=DSH, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}
$signed = Join-Path $dist "dsh-lan-pair-$versionName.apk"
& (Join-Path $bt 'apksigner.bat') sign --ks $ks --ks-pass pass:dshpair --key-pass pass:dshpair `
    --min-sdk-version $minSdk --out $signed $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner sign failed' }

& (Join-Path $bt 'apksigner.bat') verify --min-sdk-version $minSdk $signed
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify failed' }

$size = [math]::Round((Get-Item $signed).Length / 1KB, 1)
Write-Host ""
Write-Host "APK: $signed  ($size KB)" -ForegroundColor Green
