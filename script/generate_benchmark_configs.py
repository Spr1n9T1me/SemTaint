#!/usr/bin/env python3
"""
Benchmark Configuration Generator for SemTaint

Automatically generates semtaint.yml configuration files for multiple 
test projects based on a centralized benchmark configuration.
"""

import yaml
import os
import sys
from pathlib import Path
from typing import Dict, Any
import argparse


def load_benchmark_config(config_path: str) -> Dict[str, Any]:
    """Load the benchmark configuration file."""
    with open(config_path, 'r', encoding='utf-8') as f:
        return yaml.safe_load(f)


def merge_config(defaults: Dict[str, Any], project_config: Dict[str, Any]) -> Dict[str, Any]:
    """
    Merge project-specific configuration with default configuration.
    Automatically detects JAR mode vs Class path mode.
    
    Args:
        defaults: Default configuration template
        project_config: Project-specific parameters
        
    Returns:
        Merged configuration dictionary
    """
    # Deep copy defaults
    import copy
    config = copy.deepcopy(defaults)
    path_cfg = config.setdefault('path', {})
    
    # Update with project-specific values
    config['app']['package-name'] = project_config['package-name']
    config['pta']['taint-config'] = project_config['taint-config']
    
    # Auto-detect mode based on presence of jar-path or app-class-path
    if 'jar-path' in project_config and project_config['jar-path']:
        # JAR mode
        config['app']['jar-mode'] = True
        path_cfg['jar-path'] = project_config['jar-path']
        path_cfg['app-class-path'] = ""
        path_cfg['app-lib-path'] = ""
        # Remove deprecated keys to avoid writing old schema.
        path_cfg.pop('appClass-path', None)
        path_cfg.pop('lib-path', None)
    elif 'app-class-path' in project_config and project_config['app-class-path']:
        # Class path mode
        config['app']['jar-mode'] = False
        path_cfg['app-class-path'] = project_config['app-class-path']
        path_cfg['app-lib-path'] = project_config.get('app-lib-path', '')
        # Clear jar-path and deprecated fields
        path_cfg.pop('jar-path', None)
        path_cfg.pop('appClass-path', None)
        path_cfg.pop('lib-path', None)
    else:
        raise ValueError(f"Project must specify either 'jar-path' or 'app-class-path'")
    
    return config


def generate_semtaint_yml(config: Dict[str, Any], output_path: str, dry_run: bool = False):
    """
    Generate and write semtaint.yml file.
    
    Args:
        config: Configuration dictionary
        output_path: Path to write the configuration file
        dry_run: If True, only print the configuration without writing
    """
    if dry_run:
        print(f"\n[DRY RUN] Would write to: {output_path}")
        print(yaml.dump(config, default_flow_style=False, sort_keys=False, allow_unicode=True))
        return
    
    # Create directory if it doesn't exist
    os.makedirs(os.path.dirname(output_path), exist_ok=True)

    # Always remove the previous generated file before creating a new one.
    if os.path.exists(output_path):
        if not os.path.isfile(output_path):
            raise ValueError(f"Target path exists but is not a file: {output_path}")
        os.remove(output_path)
        # print(f"  - Removed old config: {output_path}")
    
    # Create a brand-new config file after deletion.
    with open(output_path, 'x', encoding='utf-8') as f:
        yaml.dump(config, f, default_flow_style=False, sort_keys=False, allow_unicode=True)
    
    print(f"✓ Generated: {output_path}")


def main():
    parser = argparse.ArgumentParser(
        description='Generate semtaint.yml files for benchmark projects'
    )
    parser.add_argument(
        '-c', '--config',
        default='benchmark-config.yml',
        help='Path to benchmark configuration file (default: benchmark-config.yml)'
    )
    parser.add_argument(
        '-o', '--output-dir',
        default=None,
        help='Base output directory (default: use jar-path from config)'
    )
    parser.add_argument(
        '--dry-run',
        action='store_true',
        help='Preview configurations without writing files'
    )
    parser.add_argument(
        '--project',
        help='Generate config for specific project only'
    )
    
    args = parser.parse_args()
    
    # Get script directory and resolve config path
    script_dir = Path(__file__).parent
    config_path = script_dir / args.config
    
    if not config_path.exists():
        print(f"Error: Configuration file not found: {config_path}", file=sys.stderr)
        sys.exit(1)
    
    # Load benchmark configuration
    print(f"Loading benchmark configuration from: {config_path}")
    benchmark_config = load_benchmark_config(config_path)
    
    defaults = benchmark_config.get('defaults', {})
    projects = benchmark_config.get('projects', [])
    
    if not projects:
        print("Error: No projects defined in configuration", file=sys.stderr)
        sys.exit(1)
    
    # Filter projects if specific project requested
    if args.project:
        projects = [p for p in projects if p.get('name') == args.project]
        if not projects:
            print(f"Error: Project '{args.project}' not found in configuration", file=sys.stderr)
            sys.exit(1)
    
    print(f"\nGenerating configurations for {len(projects)} project(s)...\n")
    
    # Generate configuration for each project
    for project in projects:
        project_name = project.get('name', 'unnamed')
        print(f"Processing project: {project_name}")
        
        try:
            # Merge configurations
            config = merge_config(defaults, project)
            
            # Determine output path
            config_filename = f"semtaint-{project_name}.yml"
            if args.output_dir:
                output_path = os.path.join(args.output_dir, project_name, config_filename)
            else:
                # Use jar-path for JAR mode, or first app-class-path for class path mode
                if 'jar-path' in project and project['jar-path']:
                    base_path = project['jar-path']
                elif 'app-class-path' in project and project['app-class-path']:
                    app_lib_path = project.get('app-lib-path', '')
                    if app_lib_path:
                        base_path = os.path.dirname(os.path.dirname(app_lib_path))
                    else:
                        first_class_path = project['app-class-path'][0]
                        base_path = os.path.dirname(os.path.dirname(first_class_path))
                else:
                    print(f"  ✗ Warning: No valid path found for project {project_name}")
                    continue
                output_path = os.path.join(base_path, config_filename)
            
            # Display mode information
            mode = "JAR" if config['app']['jar-mode'] else "Class Path"
            print(f"  Mode: {mode}")
            
            # Generate configuration file
            generate_semtaint_yml(config, output_path, args.dry_run)
        except ValueError as e:
            print(f"  ✗ Error: {e}")
            continue
    
    if not args.dry_run:
        print(f"\n✓ Successfully generated {len(projects)} configuration file(s)")
    else:
        print("\n✓ Dry run completed (no files written)")


if __name__ == '__main__':
    main()
