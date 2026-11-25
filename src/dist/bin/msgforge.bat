@echo off
setlocal
rem Starts msgforge from the folder it was unpacked into, whatever the current directory is,
rem so packs\, plugins\ and msgforge.json are the ones next to it. Arguments go to msgforge;
rem a relative --report path is taken from the folder it was started in.
rem It needs Java 17 or newer, on the PATH or through JAVA_HOME.
set "HERE=%CD%"
cd /d "%~dp0.."
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java"
"%JAVA%" "-Dmsgforge.workdir=%HERE%\." -jar msgforge.jar %*
set "CODE=%ERRORLEVEL%"
if "%CODE%"=="0" exit /b 0
rem It did not start, or stopped with an error. No Java, or an older one, fails with "not recognized" or a
rem class file version number: say what is needed, and keep a double-clicked window open long enough to read it.
"%JAVA%" -version 2>&1 | findstr /r /c:"version .1[7-9]" /c:"version .[2-9][0-9]" >nul
if not errorlevel 1 exit /b %CODE%
echo.
echo msgforge needs Java 17 or newer. Install one, or point JAVA_HOME at one.
pause
exit /b 1
