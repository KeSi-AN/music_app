@echo off
call "C:\Program Files\Microsoft Visual Studio\2022\Preview\VC\Auxiliary\Build\vcvars64.bat"
set SRC=..\..\app\app\src\main\cpp
cl /nologo /O2 /std:c++17 /EHsc /D_USE_MATH_DEFINES /I%SRC% test_pitch.cpp %SRC%\pitch.cpp /Fe:test_pitch.exe
test_pitch.exe
