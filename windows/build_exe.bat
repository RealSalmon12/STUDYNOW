@echo off
REM FocusLock.exe 만들기 (Python 설치 후 한 번만 실행)
python -m pip install --upgrade pyinstaller
python -m PyInstaller --onefile --noconsole --name FocusLock focus_lock.py
echo.
echo 완료! dist\FocusLock.exe 를 실행하세요.
pause
