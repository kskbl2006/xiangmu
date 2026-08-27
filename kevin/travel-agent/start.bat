@echo off
chcp 65001 >nul
cd /d %~dp0
echo ==============================================================
echo  智能旅行规划助手 Agent (Java) - 一键演示
echo ==============================================================
if not exist target\travel-agent.jar (
  echo 首次运行需要构建：正在执行 mvn -DskipTests package ...
  call mvn -DskipTests package
  if errorlevel 1 (
    echo.
    echo 构建失败，请检查 Maven/JDK 环境（需 JDK 17+）。可手动执行：mvn -DskipTests package
    pause
    exit /b 1
  )
)
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar target\travel-agent.jar "北京出发去三亚5天，预算5000元，2大1小"
echo.
echo 更多玩法：
echo   断点续跑: java -jar target\travel-agent.jar --step 3 "帮我规划上海三日游，2人，预算6000元"
echo   完成检查: java -jar target\travel-agent.jar --status
echo   Web控制台: java -jar target\travel-agent.jar web
echo   验收演示: java -jar target\travel-agent.jar demo --auto
pause
