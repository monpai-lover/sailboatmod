"""Compile/run the real cache-decoder harness against an explicit production JAR."""
from pathlib import Path
import argparse
import json
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--mod-jar', required=True, type=Path)
parser.add_argument('--large-png', required=True, type=Path)
parser.add_argument('--client-root', required=True, type=Path)
parser.add_argument('--version-json', required=True, type=Path)
parser.add_argument('--jdk', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
version = json.loads(args.version_json.read_text(encoding='utf-8-sig'))
libraries = []
for lib in version['libraries']:
    if 'natives-' in lib['name'] and not lib['name'].endswith(':natives-windows'):
        continue
    artifact = lib.get('downloads', {}).get('artifact', {}).get('path')
    if not artifact:
        parts = lib['name'].split(':')
        artifact = parts[0].replace('.', '/')+'/'+parts[1]+'/'+parts[2]+'/'+parts[1]+'-'+parts[2]+('-'+parts[3] if len(parts)>3 else '')+'.jar'
    candidate = args.client_root/'libraries'/artifact
    if candidate.exists():
        libraries.append(candidate)
mc = args.client_root/'libraries/net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar'
assert mc.is_file()
classpath = ';'.join(map(str, [mc, *libraries, args.mod_jar]))
source = Path(__file__).with_name('TileCacheDecodeRegression.java')
classes = args.output/'classes'
classes.mkdir(exist_ok=True)
compile_result = subprocess.run([str(args.jdk/'bin/javac.exe'), '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes), str(source)], capture_output=True, text=True)
(args.output/'compile.log').write_text(compile_result.stdout+compile_result.stderr, encoding='utf8')
print('compile_exit='+str(compile_result.returncode))
if compile_result.returncode:
    print(compile_result.stdout+compile_result.stderr)
    raise SystemExit(compile_result.returncode)
result = subprocess.run([str(args.jdk/'bin/java.exe'), '-cp', str(classes)+';'+classpath, 'TileCacheDecodeRegression', str(args.mod_jar), str(args.large_png), str(args.output/'fixtures')], capture_output=True, text=True)
output=result.stdout+result.stderr
(args.output/'run.log').write_text(output,encoding='utf8')
print('run_exit='+str(result.returncode))
print(output)
raise SystemExit(result.returncode)
