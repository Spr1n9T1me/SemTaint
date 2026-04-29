@echo off
REM Benchmark Configuration Generator - Windows Batch Script
REM Usage: run_benchmark.bat [options]

setlocal enabledelayedexpansion

echo ========================================
echo SemTaint Benchmark Tool
echo ========================================
echo.

REM Check if Python is available
python --version >nul 2>&1
if errorlevel 1 (
    echo Error: Python not found. Please install Python 3.6+
    exit /b 1
)

REM Check for required dependencies
python -c "import yaml" >nul 2>&1
if errorlevel 1 (
    echo Warning: PyYAML not installed. Installing...
    pip install pyyaml
)

REM Parse arguments
set ACTION=%1
set PROJECT=
set CONFIG=

if /i "%ACTION%"=="generate" (
    if "%2"=="" (
        REM no project/config specified
    ) else if /i "%2"=="--config" (
        set CONFIG=%3
    ) else (
        set PROJECT=%2
        if /i "%3"=="--config" set CONFIG=%4
    )
)

if /i "%ACTION%"=="run" (
    if "%2"=="" (
        REM no project/config specified
    ) else if /i "%2"=="--config" (
        set CONFIG=%3
    ) else (
        set PROJECT=%2
        if /i "%3"=="--config" set CONFIG=%4
    )
)

if /i "%ACTION%"=="all" (
    if /i "%2"=="--config" set CONFIG=%3
)

if "%ACTION%"=="" set ACTION=help

if /i "%ACTION%"=="help" goto :show_help
if /i "%ACTION%"=="generate" goto :generate_configs
if /i "%ACTION%"=="run" goto :run_benchmark
if /i "%ACTION%"=="all" goto :run_all

:show_help
echo Usage: run_benchmark.bat [action] [options]
echo.
echo Actions:
echo   generate [project] [--config path]  - Generate configuration files
echo   run [project] [--config path]       - Run benchmark analysis
echo   all [--config path]                 - Generate configs and run benchmark
echo   help                - Show this help message
echo.
echo Examples:
echo   run_benchmark.bat generate          - Generate all configs
echo   run_benchmark.bat generate webgoat  - Generate config for webgoat only
echo   run_benchmark.bat generate --config benchmark-config.yml
echo   run_benchmark.bat run               - Run all benchmarks
echo   run_benchmark.bat run webgoat       - Run webgoat benchmark only
echo   run_benchmark.bat run webgoat --config benchmark-config.yml
echo   run_benchmark.bat all --config benchmark-config.yml
goto :end

:generate_configs
echo Generating configuration files...
if "%PROJECT%"=="" (
    if "%CONFIG%"=="" (
        python generate_benchmark_configs.py
    ) else (
        python generate_benchmark_configs.py --config "%CONFIG%"
    )
) else (
    if "%CONFIG%"=="" (
        python generate_benchmark_configs.py --project "%PROJECT%"
    ) else (
        python generate_benchmark_configs.py --project "%PROJECT%" --config "%CONFIG%"
    )
)
if errorlevel 1 (
    echo Error: Configuration generation failed
    exit /b 1
)
echo Configuration generation completed!
goto :end

:run_benchmark
echo Running benchmark analysis...
if "%PROJECT%"=="" (
    if "%CONFIG%"=="" (
        python run_benchmark.py
    ) else (
        python run_benchmark.py --config "%CONFIG%"
    )
) else (
    if "%CONFIG%"=="" (
        python run_benchmark.py --project "%PROJECT%"
    ) else (
        python run_benchmark.py --project "%PROJECT%" --config "%CONFIG%"
    )
)
if errorlevel 1 (
    echo Error: Benchmark execution failed
    exit /b 1
)
echo Benchmark completed!
goto :end

:run_all
echo Running complete benchmark workflow...
echo.
echo Step 1: Generating configuration files...
if "%CONFIG%"=="" (
    python generate_benchmark_configs.py
) else (
    python generate_benchmark_configs.py --config "%CONFIG%"
)
if errorlevel 1 (
    echo Error: Configuration generation failed
    exit /b 1
)
echo.
echo Step 2: Running benchmark analysis...
if "%CONFIG%"=="" (
    python run_benchmark.py
) else (
    python run_benchmark.py --config "%CONFIG%"
)
if errorlevel 1 (
    echo Error: Benchmark execution failed
    exit /b 1
)
echo.
echo Complete benchmark workflow finished!
goto :end

:end
endlocal
