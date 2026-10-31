@echo off
REM ============================================================
REM AI-JOB backend build/test entry (Windows).
REM
REM Resolution order (standard environments win; personal paths are
REM fallbacks only and never override existing configuration):
REM   1. JAVA_HOME already set in the environment is used as-is;
REM      AIJOB_JAVA_HOME may override it explicitly.
REM   2. Maven: AIJOB_MVN if set, else mvn.cmd found on PATH,
REM      else the author's IDEA-bundled fallback below.
REM   3. -s (settings) and -Dmaven.repo.local are passed ONLY when
REM      AIJOB_SETTINGS / AIJOB_M2_REPO are explicitly set; otherwise
REM      Maven defaults (user settings.xml / default local repo) apply.
REM
REM The script reads AI-JOB/.env and injects MYSQL_USER/MYSQL_PASSWORD
REM as environment variables for the test JVM: a local security agent
REM may block java.exe from opening .env directly (bash/cmd allowed).
REM
REM Usage:
REM   mvn-run.bat [mvn goals...]
REM Official acceptance (mandatory mode, DB unreachable = FAIL):
REM   mvn-run.bat clean test -Dintegration-tests=true
REM Full output is written to build.log (git-ignored) and echoed.
REM ============================================================
setlocal

if defined AIJOB_JAVA_HOME set "JAVA_HOME=%AIJOB_JAVA_HOME%"
if not defined JAVA_HOME set "JAVA_HOME=D:\Juanapps\ai-job-tools\jdk-21.0.13.0.2+1"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JAVA_HOME points to a missing JDK: %JAVA_HOME%
    echo Set JAVA_HOME ^(JDK 21^) or AIJOB_JAVA_HOME before running.
    exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not defined AIJOB_MVN (
    where mvn.cmd >nul 2>&1
    if not errorlevel 1 set "AIJOB_MVN=mvn.cmd"
)
if not defined AIJOB_MVN set "AIJOB_MVN=D:\Juan apps\IDEA\IntelliJ IDEA Community Edition 2024.3\plugins\maven\lib\maven3\bin\mvn.cmd"
if /i not "%AIJOB_MVN%"=="mvn.cmd" if not exist "%AIJOB_MVN%" (
    echo [ERROR] mvn.cmd not found: %AIJOB_MVN%
    echo Install Maven on PATH or set AIJOB_MVN before running.
    exit /b 2
)

REM %~dp0 = this script's directory (project/backend), portable across checkouts
cd /d "%~dp0"

REM Resolve AI-JOB/.env via %%~fi (for /f usebackq cannot open raw ..\.. paths)
set "AIJOB_ENV_FILE="
for %%i in ("%~dp0..\..\.env") do set "AIJOB_ENV_FILE=%%~fi"
if not defined MYSQL_USER (
    if defined AIJOB_ENV_FILE (
        for /f "usebackq tokens=1,* delims==" %%a in ("%AIJOB_ENV_FILE%") do (
            if "%%a"=="MYSQL_USER" set "MYSQL_USER=%%b"
            if "%%a"=="MYSQL_PASSWORD" set "MYSQL_PASSWORD=%%b"
        )
    )
)

REM Optional -s / -Dmaven.repo.local: passed only when explicitly configured
set "EXTRA_ARGS="
if defined AIJOB_SETTINGS set EXTRA_ARGS=-s "%AIJOB_SETTINGS%"
if defined AIJOB_M2_REPO set EXTRA_ARGS=%EXTRA_ARGS% -Dmaven.repo.local="%AIJOB_M2_REPO%"

REM Batch-to-batch invocation MUST use call, otherwise control never
REM returns and the log echo / exit-code handling below is skipped.
call "%AIJOB_MVN%" %EXTRA_ARGS% %* > build.log 2>&1
set "EXITCODE=%ERRORLEVEL%"
type build.log
REM Expand %EXITCODE% before endlocal wipes it (whole-line expansion
REM happens first), so the real mvn exit code propagates to callers.
endlocal & exit /b %EXITCODE%