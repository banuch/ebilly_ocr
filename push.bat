@echo off
@echo off
IF "%~1"=="" (
    echo Error: Please provide a commit message.
    echo Usage: gitcommit.bat "Your commit message"
    exit /b 1
)

git add .
git commit -m "%~1"
git push -u origin master

echo Committed and pushed successfully with message: "%~1"
