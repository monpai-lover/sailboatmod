"""Apply the exact validated follow-up; never overwrite a concurrent source edit."""
import base64,gzip,hashlib,json,pathlib,subprocess
root=pathlib.Path.cwd()
compressed=base64.b64decode((root/'tools/tellus-native/pending-native.b64').read_text().strip(),validate=True)
assert hashlib.sha256(compressed).hexdigest()=='a0efcaef78ed5fbc640d0d15f693700a2b8664284433a41f97e7e69a45fd16cf','Source transport digest mismatch'
payload=json.loads(gzip.decompress(compressed))
for name,hashes in payload['manifest'].items():
    assert name.startswith(('src/main/java/com/monpai/sailboatmod/','src/test/java/com/monpai/sailboatmod/','tools/tellus-native/java/')) and '..' not in pathlib.PurePosixPath(name).parts
    path=root/name
    actual=hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else None
    assert actual==hashes['before'],'Concurrent source modification: '+name
patch=root/'build/native-source.patch';patch.parent.mkdir(exist_ok=True);patch.write_text(payload['patch'])
subprocess.run(['git','apply','--check',str(patch)],check=True)
subprocess.run(['git','apply',str(patch)],check=True)
for name,hashes in payload['manifest'].items():
    assert hashlib.sha256((root/name).read_bytes()).hexdigest()==hashes['after'],'Applied digest: '+name
(root/'build/native-manifest.json').write_text(json.dumps(payload['manifest'],indent=2))
print('Verified bounded IO frontier scheduling, wakeup and direct generator follow-up')
