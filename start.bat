@echo off
chcp 936 >nul
setlocal
cd /d "%~dp0"

set PORT=8081
set MYSQL_BIN=C:\Users\dou99\tools\mysql-8.0.46-winx64\bin

echo ============================================
echo   MotoChat (Spring Boot) 启动
echo ============================================
echo.

rem ---------- 0. 检查 mvn ----------
rem 本文件一律用 goto 标签，不用括号块：括号块里出现感叹号、引号、竖线等
rem 字符容易被 cmd 误解析，症状就是提示文字被当成命令、窗口一闪而过。
where mvn >nul 2>nul
if errorlevel 1 goto no_mvn

rem ---------- 0.5 检查端口 ----------
echo [0/3] 检查端口 %PORT% 是否被占用...
set PORT_PID=
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%PORT% " ^| findstr "LISTENING"') do set PORT_PID=%%a
if not defined PORT_PID goto port_ok

set PORT_PROC=
for /f "delims=, tokens=1" %%n in ('tasklist /FI "PID eq %PORT_PID%" /NH /FO CSV') do set PORT_PROC=%%~n
if /i "%PORT_PROC%"=="java.exe" goto kill_java
goto port_other

:kill_java
echo       端口被 java.exe 占用，PID %PORT_PID%
echo       多半是上次没关掉的 MotoChat，正在结束它...
taskkill /PID %PORT_PID% /F >nul 2>nul
echo       等待 2 秒让端口释放...
ping -n 3 127.0.0.1 >nul
goto port_ok

:port_other
echo.
echo   端口 %PORT% 被 [%PORT_PROC%] 占用，PID %PORT_PID%
echo   看起来不是 MotoChat，为避免误杀别的程序，这里停住不杀。
echo   处理方式二选一：
echo     1 确认它不是你要用的程序后，手动关掉它，再重跑本脚本
echo     2 改本文件开头那行 set PORT= 换一个端口
echo.
pause
exit /b 1

:port_ok
rem ---------- 1. 检查 MySQL ----------
echo [1/3] 检查 MySQL...
"%MYSQL_BIN%\mysql.exe" -uroot -h127.0.0.1 -e "SELECT 1;" >nul 2>nul
if not errorlevel 1 goto mysql_ok

echo       MySQL 连不上，正在启动...
start "" "%MYSQL_BIN%\mysqld.exe" --no-defaults --basedir=C:\Users\dou99\tools\mysql-8.0.46-winx64 --datadir=C:\Users\dou99\tools\mysql-data --port=3306
echo       等待 8 秒让 MySQL 就绪...
ping -n 9 127.0.0.1 >nul
"%MYSQL_BIN%\mysql.exe" -uroot -h127.0.0.1 -e "SELECT 1;" >nul 2>nul
if errorlevel 1 goto no_mysql

:mysql_ok
rem ---------- 2. 检查 motochat 库 ----------
echo [2/3] 检查数据库 motochat...
"%MYSQL_BIN%\mysql.exe" -uroot -h127.0.0.1 -e "USE motochat;" >nul 2>nul
if errorlevel 1 goto no_db

rem ---------- 3. 启动应用 ----------
echo [3/3] 启动应用（http://127.0.0.1:%PORT%）...
echo.
echo   浏览器打开 http://127.0.0.1:%PORT%
echo   初始账号 admin / admin123
echo   接口文档 http://127.0.0.1:%PORT%/swagger-ui.html
echo   停止服务 Ctrl + C
echo.
call mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=%PORT%

echo.
echo ============================================
echo   应用已停止。
echo   如果是因为报错退出的，把上面的输出发出来看看。
echo ============================================
echo.
pause
goto :eof

:no_mvn
echo.
echo   找不到 mvn 命令。
echo   请确认 Maven 已安装并加入了 PATH，
echo   或把本文件里的 mvn 改成完整路径。
echo.
pause
exit /b 1

:no_mysql
echo.
echo   无法连接 MySQL。
echo   请检查 tools\mysql-data 目录，或参考 tools\start-mysql.bat。
echo.
pause
exit /b 1

:no_db
echo.
echo   无法连接 motochat 数据库。
echo   首次使用请先执行 init-db.bat 建库导表。
echo.
pause
exit /b 1
