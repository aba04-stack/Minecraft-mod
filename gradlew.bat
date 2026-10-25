@echo off
setlocal EnableExtensions
set "GRADLE_VERSION=9.2.1"
set "GRADLE_SHA256=72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f"

where gradle >nul 2>nul
if errorlevel 1 goto bootstrap

gradle --version | findstr /x /c:"Gradle %GRADLE_VERSION%" >nul
if errorlevel 1 goto pinned_bootstrap_notice
call gradle --no-daemon %*
exit /b %ERRORLEVEL%

:pinned_bootstrap_notice
echo System Gradle differs from pinned %GRADLE_VERSION%; using checksum-verified distribution instead.
goto bootstrap

:bootstrap
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "CACHE_ROOT=%GRADLE_USER_HOME%\wrapper\dists\gradle-%GRADLE_VERSION%-bin\living-villages"
set "GRADLE_HOME=%CACHE_ROOT%\gradle-%GRADLE_VERSION%"
set "GRADLE_EXE=%GRADLE_HOME%\bin\gradle.bat"
if exist "%GRADLE_EXE%" goto run_gradle

echo Gradle %GRADLE_VERSION% is not cached. Downloading official distribution with SHA-256 verification...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $version='%GRADLE_VERSION%'; $expected='%GRADLE_SHA256%'; $cache='%CACHE_ROOT%'; $parent=Split-Path -Parent $cache; New-Item -ItemType Directory -Force -Path $parent | Out-Null; $zip=Join-Path $env:TEMP ('gradle-' + $version + '-bin.zip'); try { Invoke-WebRequest -UseBasicParsing ('https://services.gradle.org/distributions/gradle-' + $version + '-bin.zip') -OutFile $zip; $actual=(Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant(); if ($actual -ne $expected) { throw 'Gradle distribution SHA-256 mismatch; aborting.' }; Expand-Archive -LiteralPath $zip -DestinationPath $cache -Force; } finally { if (Test-Path $zip) { Remove-Item $zip -Force } }"
if errorlevel 1 exit /b 1
if not exist "%GRADLE_EXE%" (
  echo Could not find the downloaded Gradle executable: "%GRADLE_EXE%"
  exit /b 1
)

:run_gradle
call "%GRADLE_EXE%" --no-daemon %*
exit /b %ERRORLEVEL%
