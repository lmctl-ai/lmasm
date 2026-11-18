@echo off
rem lmasm host launcher for Windows: resolves a JDK, then runs the shaded host jar.
setlocal
if defined JAVA_HOME (if exist "%JAVA_HOME%\bin\java.exe" (set "JAVA=%JAVA_HOME%\bin\java.exe")) else (set "JAVA=java")
if not defined JAVA set "JAVA=java"
if not exist "%~dp0..\target\lmasm.jar" (
  echo lmasm: %~dp0..\target\lmasm.jar missing; run 'mvn package' first 1>&2
  exit /b 2
)
"%JAVA%" -jar "%~dp0..\target\lmasm.jar" %*
