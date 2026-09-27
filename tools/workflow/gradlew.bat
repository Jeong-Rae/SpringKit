@echo off

rem Delegate to the repository's shared Gradle wrapper and select this project.
set "SCRIPT_DIR=%~dp0"
call "%SCRIPT_DIR%..\template\spring-boot-kotlin\gradlew.bat" -p "%SCRIPT_DIR%" %*
exit /b %ERRORLEVEL%
