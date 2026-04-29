#!/usr/bin/env python3
"""
Batch Benchmark Runner for SemTaint

Automatically runs SemTaint analysis on multiple projects and collects results.
"""

import yaml
import os
import sys
import subprocess
import time
from pathlib import Path
from datetime import datetime
import argparse
import json


def load_benchmark_config(config_path: str):
    """Load the benchmark configuration file."""
    with open(config_path, 'r', encoding='utf-8') as f:
        return yaml.safe_load(f)


def run_analysis(project_name: str, config_path: str, timeout: int = 3600) -> dict:
    """
    Run SemTaint analysis for a single project.
    
    Args:
        project_name: Name of the project
        config_path: Path to semtaint.yml configuration file
        timeout: Timeout in seconds (default: 1 hour)
        
    Returns:
        Dictionary containing execution results
    """
    print(f"\n{'='*60}")
    print(f"Running analysis for: {project_name}")
    print(f"Config: {config_path}")
    print(f"{'='*60}")
    
    result = {
        'project': project_name,
        'config': config_path,
        'start_time': datetime.now().isoformat(),
        'status': 'unknown',
        'duration': 0,
        'error': None
    }
    
    start_time = time.time()
    
    try:
        # Run SemTaint using -jar (Main-Class is specified in MANIFEST.MF)
        cmd = [
            'java',
            '-Xmx24g',  # Adjust memory as needed
            '-jar', 'D:\\Program-Analysis\\semtaint-newfront\\build\\tai-e-all-0.5.2-SNAPSHOT.jar',
            config_path
        ]
        
        print(f"Command: {' '.join(cmd)}")
        
        process = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout,
            cwd=Path(__file__).parent.parent  # Run from project root
        )
        
        end_time = time.time()
        result['duration'] = end_time - start_time
        result['end_time'] = datetime.now().isoformat()
        
        if process.returncode == 0:
            result['status'] = 'success'
            print(f"✓ Analysis completed successfully in {result['duration']:.2f}s")
        else:
            result['status'] = 'failed'
            result['error'] = process.stderr
            print(f"✗ Analysis failed with return code {process.returncode}")
            # print(f"Error: {process.stderr[:500]}")  # Print first 500 chars
            print(f"Error: {process.stderr}")
        # Save full output
        result['stdout'] = process.stdout
        result['stderr'] = process.stderr
        
    except subprocess.TimeoutExpired:
        end_time = time.time()
        result['duration'] = end_time - start_time
        result['status'] = 'timeout'
        result['error'] = f'Execution exceeded timeout of {timeout}s'
        print(f"✗ Analysis timed out after {timeout}s")
        
    except Exception as e:
        end_time = time.time()
        result['duration'] = end_time - start_time
        result['status'] = 'error'
        result['error'] = str(e)
        print(f"✗ Unexpected error: {e}")
    
    return result


def save_results(results: list, output_file: str):
    """Save benchmark results to file."""
    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(results, f, indent=2, ensure_ascii=False)
    print(f"\n✓ Results saved to: {output_file}")


def print_summary(results: list):
    """Print summary of benchmark results."""
    print("\n" + "="*60)
    print("BENCHMARK SUMMARY")
    print("="*60)
    
    total = len(results)
    success = sum(1 for r in results if r['status'] == 'success')
    failed = sum(1 for r in results if r['status'] == 'failed')
    timeout = sum(1 for r in results if r['status'] == 'timeout')
    error = sum(1 for r in results if r['status'] == 'error')
    
    print(f"Total projects: {total}")
    print(f"  ✓ Success: {success}")
    print(f"  ✗ Failed: {failed}")
    print(f"  ⏱ Timeout: {timeout}")
    print(f"  ⚠ Error: {error}")
    
    total_time = sum(r['duration'] for r in results)
    print(f"\nTotal execution time: {total_time:.2f}s ({total_time/60:.2f}min)")
    
    if success > 0:
        avg_time = sum(r['duration'] for r in results if r['status'] == 'success') / success
        print(f"Average time (success): {avg_time:.2f}s")
    
    print("\nDetailed Results:")
    for r in results:
        status_symbol = {
            'success': '✓',
            'failed': '✗',
            'timeout': '⏱',
            'error': '⚠'
        }.get(r['status'], '?')
        
        print(f"  {status_symbol} {r['project']:30s} {r['status']:10s} {r['duration']:8.2f}s")


def main():
    parser = argparse.ArgumentParser(
        description='Run SemTaint benchmark on multiple projects'
    )
    parser.add_argument(
        '-c', '--config',
        default='benchmark-config.yml',
        help='Path to benchmark configuration file'
    )
    parser.add_argument(
        '-o', '--output',
        default='benchmark_results.json',
        help='Output file for results (default: benchmark_results.json)'
    )
    parser.add_argument(
        '--timeout',
        type=int,
        default=3600,
        help='Timeout per project in seconds (default: 3600)'
    )
    parser.add_argument(
        '--project',
        help='Run benchmark for specific project only'
    )
    parser.add_argument(
        '--skip-config-gen',
        action='store_true',
        help='Skip automatic config file generation'
    )
    
    args = parser.parse_args()
    
    script_dir = Path(__file__).parent
    config_path = script_dir / args.config
    
    if not config_path.exists():
        print(f"Error: Configuration file not found: {config_path}", file=sys.stderr)
        sys.exit(1)
    
    # Load benchmark configuration
    print(f"Loading benchmark configuration from: {config_path}")
    benchmark_config = load_benchmark_config(config_path)
    projects = benchmark_config.get('projects', [])
    
    if not projects:
        print("Error: No projects defined in configuration", file=sys.stderr)
        sys.exit(1)
    
    # Filter projects if specific project requested
    if args.project:
        projects = [p for p in projects if p.get('name') == args.project]
        if not projects:
            print(f"Error: Project '{args.project}' not found", file=sys.stderr)
            sys.exit(1)
    
    # Generate config files if needed
    if not args.skip_config_gen:
        print("\nGenerating configuration files...")
        gen_script = script_dir / 'generate_benchmark_configs.py'
        if gen_script.exists():
            subprocess.run([sys.executable, str(gen_script), '-c', str(config_path)])
        else:
            print("Warning: Config generator not found, skipping generation")
    
    # Run benchmark
    print(f"\n{'='*60}")
    print(f"Starting benchmark for {len(projects)} project(s)")
    print(f"{'='*60}")
    
    results = []
    
    for i, project in enumerate(projects, 1):
        project_name = project.get('name', 'unnamed')
        
        # Determine config file path based on mode
        if 'jar-path' in project and project['jar-path']:
            # JAR mode
            base_path = project['jar-path']
        elif 'app-class-path' in project and project['app-class-path']:
            app_lib_path = project.get('app-lib-path', '')
            base_path = os.path.dirname(os.path.dirname(app_lib_path)) if app_lib_path else ''
        else:
            print(f"\n[{i}/{len(projects)}] ✗ Skipping {project_name}: No valid path configuration")
            results.append({
                'project': project_name,
                'config': 'N/A',
                'status': 'error',
                'error': 'No jar-path or app-class-path specified',
                'duration': 0
            })
            continue
        
        config_file = os.path.join(base_path, f'semtaint-{project_name}.yml')
        
        print(f"\n[{i}/{len(projects)}] Processing: {project_name}")
        
        if not os.path.exists(config_file):
            print(f"✗ Config file not found: {config_file}")
            results.append({
                'project': project_name,
                'config': config_file,
                'status': 'error',
                'error': 'Config file not found',
                'duration': 0
            })
            continue
        
        result = run_analysis(project_name, config_file, args.timeout)
        results.append(result)
    
    # Save and display results
    output_path = script_dir / args.output
    save_results(results, str(output_path))
    print_summary(results)


if __name__ == '__main__':
    main()
