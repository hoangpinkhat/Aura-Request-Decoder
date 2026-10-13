# Build script for the Aura Request Decoder Burp extension.
# No Maven/Gradle required - just a JDK (javac + jar on the system).
#
# Usage:  powershell -ExecutionPolicy Bypass -File build.ps1

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot

$MontoyaVersion = "2026.7"
$MontoyaUrl = "https://repo1.maven.org/maven2/net/portswigger/burp/extensions/montoya-api/$MontoyaVersion/montoya-api-$MontoyaVersion.jar"
$LibJar = "lib/montoya-api.jar"

# Resolve a real JDK home that actually contains jar.exe (not just a PATH shim).
function Resolve-JdkHome {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME/bin/jar.exe")) {
        return $env:JAVA_HOME
    }
    # Search standard JDK install locations for a bin/jar.exe (the PATH java may be
    # just the Oracle "javapath" shim, which has no jar.exe).
    $roots = @(
        "$env:ProgramFiles\Java",
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Microsoft\jdk*",
        "${env:ProgramFiles(x86)}\Java",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
    )
    $jar = Get-ChildItem -Path $roots -Filter jar.exe -Recurse -ErrorAction SilentlyContinue |
           Where-Object { $_.FullName -match '\\bin\\jar\.exe$' } |
           Sort-Object FullName -Descending | Select-Object -First 1
    if ($jar) { return (Split-Path (Split-Path $jar.FullName)) }

    throw "No JDK (with jar.exe) found. Install a JDK or set JAVA_HOME to a JDK."
}

$JdkHome  = Resolve-JdkHome
$JavacExe = Join-Path $JdkHome "bin/javac.exe"
$JarExe   = Join-Path $JdkHome "bin/jar.exe"
Write-Host "Using JDK: $JdkHome"

New-Item -ItemType Directory -Force -Path lib, build, dist | Out-Null

# Download the Montoya API (compile-only; Burp provides it at runtime).
if (-not (Test-Path $LibJar)) {
    Write-Host "Downloading Montoya API $MontoyaVersion ..."
    Invoke-WebRequest -Uri $MontoyaUrl -OutFile $LibJar
}

# Clean previous output.
Remove-Item -Recurse -Force build/* -ErrorAction SilentlyContinue

# Compile.
$sources = Get-ChildItem -Recurse -Filter *.java -Path src/main/java | ForEach-Object { $_.FullName }
Write-Host "Compiling $($sources.Count) source files ..."
# --release 21: Burp ships a Java 25 runtime; target 21 so bytecode loads there.
& $JavacExe --release 21 -d build -cp $LibJar $sources
if ($LASTEXITCODE -ne 0) { throw "Compilation failed." }

# Package.
& $JarExe cf dist/aura-decoder.jar -C build .
if ($LASTEXITCODE -ne 0) { throw "Jar packaging failed." }

Write-Host ""
Write-Host "Build OK -> dist/aura-decoder.jar"
Write-Host "Load it in Burp: Extensions > Installed > Add > Extension type: Java > select the jar."
