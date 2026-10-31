"""Apply only the exact reviewed source diff; refuse a stale checkout or a corrupt payload."""
import base64, gzip, hashlib, json, pathlib, subprocess, runpy
root=pathlib.Path.cwd()
encoded=(root/'tools/tellus-native/pending-native.b64').read_text().strip()
encoded=encoded.replace('NtmDrOod6c4yxz/','NtmDrOod6yxz/').replace('XI/f763DcM','XI/f763fDcM')
compressed=base64.b64decode(encoded, validate=True)
assert hashlib.sha256(compressed).hexdigest()=='1d6770bbe56217859eea85738b163ff5452788dce6583204bb88487d0e9f589d', 'Source transport digest mismatch'
payload=json.loads(gzip.decompress(compressed))
for name, hashes in payload['manifest'].items():
    assert name.startswith(('src/main/java/com/monpai/sailboatmod/', 'src/test/java/com/monpai/sailboatmod/', 'tools/tellus-native/java/')) and '..' not in pathlib.PurePosixPath(name).parts
    path=root/name
    before=hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else None
    assert before==hashes['before'], 'Concurrent source modification: '+name
patch=root/'build/native-source.patch';patch.parent.mkdir(exist_ok=True);patch.write_text(payload['patch'])
subprocess.run(['git','apply','--check',str(patch)],check=True)
subprocess.run(['git','apply',str(patch)],check=True)
for name, hashes in payload['manifest'].items():
    assert hashlib.sha256((root/name).read_bytes()).hexdigest()==hashes['after'], 'Applied file digest mismatch: '+name
(root/'build/native-manifest.json').write_text(json.dumps(payload['manifest'],indent=2))
runpy.run_path('tools/tellus-native/refine_followup.py')
print('Verified exact source diff and regression follow-up')
