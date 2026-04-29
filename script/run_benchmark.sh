#!/bin/bash
# Benchmark Configuration Generator - Unix Shell Script
# Usage: ./run_benchmark.sh [options]

set -e

echo "========================================"
echo "SemTaint Benchmark Tool"
echo "========================================"
echo ""

# Check if Python is available
if ! command -v python3 &> /dev/null; then
    echo "Error: Python 3 not found. Please install Python 3.6+"
    exit 1
fi

# Check for required dependencies
if ! python3 -c "import yaml" &> /dev/null; then
    echo "Warning: PyYAML not installed. Installing..."
    pip3 install pyyaml
fi

# Parse arguments
ACTION="${1:-help}"
PROJECT="$2"

show_help() {
    echo "Usage: ./run_benchmark.sh [action] [options]"
    echo ""
    echo "Actions:"
    echo "  generate [project]  - Generate configuration files"
    echo "  run [project]       - Run benchmark analysis"
    echo "  all                 - Generate configs and run benchmark"
    echo "  help                - Show this help message"
    echo ""
    echo "Examples:"
    echo "  ./run_benchmark.sh generate          - Generate all configs"
    echo "  ./run_benchmark.sh generate webgoat  - Generate config for webgoat only"
    echo "  ./run_benchmark.sh run               - Run all benchmarks"
    echo "  ./run_benchmark.sh run webgoat       - Run webgoat benchmark only"
    echo "  ./run_benchmark.sh all               - Complete benchmark workflow"
}

generate_configs() {
    echo "Generating configuration files..."
    if [ -z "$PROJECT" ]; then
        python3 generate_benchmark_configs.py
    else
        python3 generate_benchmark_configs.py --project "$PROJECT"
    fi
    echo "Configuration generation completed!"
}

run_benchmark() {
    echo "Running benchmark analysis..."
    if [ -z "$PROJECT" ]; then
        python3 run_benchmark.py
    else
        python3 run_benchmark.py --project "$PROJECT"
    fi
    echo "Benchmark completed!"
}

run_all() {
    echo "Running complete benchmark workflow..."
    echo ""
    echo "Step 1: Generating configuration files..."
    python3 generate_benchmark_configs.py
    echo ""
    echo "Step 2: Running benchmark analysis..."
    python3 run_benchmark.py
    echo ""
    echo "Complete benchmark workflow finished!"
}

case "$ACTION" in
    help)
        show_help
        ;;
    generate)
        generate_configs
        ;;
    run)
        run_benchmark
        ;;
    all)
        run_all
        ;;
    *)
        echo "Unknown action: $ACTION"
        echo ""
        show_help
        exit 1
        ;;
esac
