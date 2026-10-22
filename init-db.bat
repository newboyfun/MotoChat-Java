@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo ============================================
echo   初始化 motochat 数据库
echo ============================================
echo.

rem 确保 MySQL 在跑
tasklist /FI "IMAGENAME eq mysqld.exe" 2>nul | find /i "mysqld.exe" >nul
if not %errorlevel%==0 (
    echo MySQL 未运行，正在启动...
    start "" "C:\Users\dou99\tools\mysql-8.0.46-winx64\bin\mysqld.exe" ^
        --no-defaults ^
        --basedir=C:\Users\dou99\tools\mysql-8.0.46-winx64 ^
        --datadir=C:\Users\dou99\tools\mysql-data ^
        --port=3306
    timeout /t 4 /nobreak >nul
)

set MYSQL="C:\Users\dou99\tools\mysql-8.0.46-winx64\bin\mysql.exe"

echo [1/2] 创建数据库 motochat...
%MYSQL% -uroot -h127.0.0.1 -e "CREATE DATABASE IF NOT EXISTS motochat DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
if not %errorlevel%==0 (
    echo   [!] 创建数据库失败
    pause
    exit /b 1
)

echo [2/2] 导入表结构...
%MYSQL% --default-character-set=utf8mb4 -uroot -h127.0.0.1 motochat < src\main\resources\schema.sql
if not %errorlevel%==0 (
    echo   [!] 导入失败
    pause
    exit /b 1
)

echo.
echo 完成。表结构：
%MYSQL% -uroot -h127.0.0.1 motochat -e "SHOW TABLES;"
echo.
echo 初始账号 admin / admin123
echo.
pause
endlocal
