@echo off
cd /d "%~dp0"
java -jar SubscriptionReminder.jar
if errorlevel 1 pause
