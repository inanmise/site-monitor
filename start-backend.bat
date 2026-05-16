@echo off
set "JAVA_HOME=C:\Program Files\Zulu\zulu-21"
set "PATH=%JAVA_HOME%\bin;C:\tools\apache-maven-3.9.6\bin;%PATH%"
cd /d D:\cert-monitor\backend
echo Starting Spring Boot backend...
C:\tools\apache-maven-3.9.6\bin\mvn.cmd spring-boot:run
pause
