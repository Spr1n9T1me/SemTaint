# SemTaint Benchmark Scripts

A small toolkit to batch-run SemTaint on multiple Java web projects. It generates per-project `semtaint.yml` configs and runs analyses in bulk.

## What’s Included

**Core scripts**

- `generate_benchmark_configs.py` – generate per-project configs (jar or classpath mode)
- `run_benchmark.py` – run analyses for all or selected projects
- `run_benchmark.bat` / `run_benchmark.sh` – convenience wrappers

**Config**

- `benchmark-config.yml` – main config (projects + defaults)
- `benchmark-config.template.yml` – starter template
- `benchmark-config.example.yml` – examples

## Install

```bash
pip install -r requirements.txt
# or
pip install pyyaml
```

## Quick Start

1) Create config

```bash
copy benchmark-config.template.yml benchmark-config.yml
```

2) Edit `benchmark-config.yml` and add projects

```yaml
projects:
  - name: webgoat
    jar-path: D:/CVE-reproduction/SemTaint-Bench/TaintBench/webgoat
    package-name: org.owasp.webgoat
    taint-config: src/main/resources/taint-config-webgoat.yml
```

3) Generate configs and run

**Windows**

```cmd
run_benchmark.bat generate
run_benchmark.bat run
```

**Linux/Mac**

```bash
chmod +x run_benchmark.sh
./run_benchmark.sh generate
./run_benchmark.sh run
```

## Manual Usage

Generate configs:

```bash
python generate_benchmark_configs.py
python generate_benchmark_configs.py --project webgoat
python generate_benchmark_configs.py --dry-run
python generate_benchmark_configs.py -o ./configs
```

Run benchmarks:

```bash
python run_benchmark.py
python run_benchmark.py --project webgoat
python run_benchmark.py --skip-config-gen
python run_benchmark.py --timeout 7200
python run_benchmark.py -o my_results.json
```

## Config Modes

**JAR mode** (packaged JAR/WAR or a directory of jars):

```yaml
projects:
  - name: app
    jar-path: /path/to/jar-or-dir
    package-name: com.example
    taint-config: src/main/resources/taint-config-default.yml
```

**Classpath mode** (exploded classes + libs):

```yaml
projects:
  - name: app
    app-class-path:
      - /path/to/module1/classes
      - /path/to/module2/classes
    app-lib-path: /path/to/lib
    package-name: com.example
    taint-config: src/main/resources/taint-config-default.yml
```

The mode is detected automatically:

- `jar-path` => `jar-mode: true`
- `app-class-path` => `jar-mode: false`

## Results

Results are written to `benchmark_results.json` with status, runtime, errors, and logs per project.

## Notes

- Run scripts from the `script` directory, or pass `-c` to point at your config.
- `taint-config` is relative to the SemTaint root.
- Generated `semtaint.yml` overwrites any existing file in the target output directory.
