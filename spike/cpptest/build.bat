@echo off
call "C:\Program Files\Microsoft Visual Studio\2022\Preview\VC\Auxiliary\Build\vcvars64.bat" >nul
set SRC=..\..\app\app\src\main\cpp
cl /nologo /O2 /std:c++17 /EHsc /D_USE_MATH_DEFINES /I%SRC% /I%SRC%\third_party test_sep.cpp %SRC%\separation.cpp %SRC%\dsp.cpp /Fe:test_sep.exe
