@echo off
cd /d "%~dp0"
"C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot\bin\java.exe" -cp out chess.Main uci
