@REM ----------------------------------------------------------------------------
@REM Maven Wrapper script for Windows
@REM ----------------------------------------------------------------------------
@echo off
setlocal

set "DIR=%~dp0"
where mvn >nul 2>nul
if %ERRORLEVEL% equ 0 (
    mvn %*
    exit /b %ERRORLEVEL%
)

if defined M2_HOME (
    if exist "%M2_HOME%\bin\mvn.cmd" (
        "%M2_HOME%\bin\mvn.cmd" %*
        exit /b %ERRORLEVEL%
    )
)

echo Error: Neither mvn nor M2_HOME could be located. Please install Apache Maven. 1>&2
exit /b 1
