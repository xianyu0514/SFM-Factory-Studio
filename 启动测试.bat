@echo off
setlocal enabledelayedexpansion
title SFM Factory Studio 26.1.2 · 测试启动器
cd /d "%~dp0"

echo.
echo  ==========================================================
echo    SFM Factory Studio 26.1.2  测试启动器
echo  ==========================================================
echo.

REM ============================================================
REM  1. 检查代理：NeoGradle 每次构建都要连 piston-meta，
REM     代理端口会变，所以先探一次，不通就扫常见端口。
REM ============================================================
set PROXY=
for /f "tokens=2 delims==" %%P in ('findstr /b /c:"systemProp.https.proxyPort=" gradle.properties') do set PROXY=%%P
if "%PROXY%"=="" set PROXY=7890
echo  [1/3] 检查代理 127.0.0.1:%PROXY% ...

curl -s -m 3 -x http://127.0.0.1:%PROXY% -o nul https://piston-meta.mojang.com/mc/game/version_manifest.json
if not errorlevel 1 goto proxy_ok

echo  [!] 该端口不通，正在扫描常见代理端口...
for %%P in (7890 7897 1080 10808 7891 4437 10512 10583 2080 10809 8080) do (
    curl -s -m 2 -x http://127.0.0.1:%%P -o nul https://piston-meta.mojang.com/mc/game/version_manifest.json
    if not errorlevel 1 (
        echo.
        echo  [OK] 找到可用代理：%%P
        echo       请把 gradle.properties 里的 proxyPort 改成 %%P，再重新运行本脚本。
        echo.
        pause
        exit /b 1
    )
)
echo.
echo  [X] 没有找到可用代理。请先打开代理软件，或手动修改 gradle.properties。
echo.
pause
exit /b 1

:proxy_ok
echo  [OK] 代理可用
echo.

REM ============================================================
REM  2. 选择模式
REM ============================================================
echo  [2/3] 选择模式：
echo.
echo        1  启动游戏（进世界自己测：管理器界面 / 积木编辑器 / 注释脚注）
echo        2  启动游戏 + 自动打开 TPS 设置界面（验证渲染，可在主菜单直接看）
echo        3  只跑单元测试（不开游戏，约 1 分钟）
echo.
set MODE=
set /p MODE=  请输入 1 / 2 / 3，回车默认 1 ：
if "%MODE%"=="" set MODE=1
echo.

REM ============================================================
REM  3. 启动
REM ============================================================
if "%MODE%"=="3" (
    echo  [3/3] 跑单元测试...
    echo.
    call gradlew.bat test --console=plain
    echo.
    echo  测试完成，报告在：build\reports\tests\test\index.html
    goto done
)

if "%MODE%"=="2" (
    echo  [3/3] 启动游戏（自动打开 TPS 界面，约 40 秒后会自动截一张图）...
    echo.
    call gradlew.bat runClient -PautoOpenScreen=tps -PverifyMixins=true --console=plain
    echo.
    echo  截图位置：runs\client\screenshots\sfmstudio-debug
    goto done
)

echo  [3/3] 启动游戏（含 mixin 自检）...
echo.
echo       进游戏后的测试路径：
echo         1. 创造模式，/give @p sfm:manager  和  /give @p sfm:disk
echo         2. 放管理器、右键打开
echo         3. 点左侧「可视编辑」进积木编辑器
echo         4. 看注释：卡片底部（2 行以内卡片高度不应变化）
echo.
call gradlew.bat runClient -PverifyMixins=true --console=plain

:done
echo.
echo  ----------------------------------------------------------
echo   已退出。若游戏崩溃，日志在：runs\client\logs\latest.log
echo  ----------------------------------------------------------
echo.
pause
