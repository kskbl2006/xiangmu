@echo off
rem 微信智能机器人启动脚本（Windows）
rem 需先执行 mvn clean package 打包
chcp 65001 >nul
set JAVA_HOME=D:\agent\tools\jdk-21.0.12+8
"%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 -jar "%~dp0target\wechat-bot.jar"
pause
