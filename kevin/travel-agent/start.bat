@echo off
chcp 65001 >nul
cd /d %~dp0
echo ==============================================================
echo  智能旅行规划助手 Agent - 一键演示
echo ==============================================================
python main.py "北京出发去三亚5天，预算5000元，2大1小"
echo.
echo 更多玩法：
echo   断点续跑: python main.py --step 3 "帮我规划上海三日游，2人，预算6000元"
echo   完成检查: python main.py --status
echo   Web控制台: python web.py
echo   验收演示: python demo.py
pause
