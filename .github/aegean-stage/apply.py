import base64, hashlib, lzma, pathlib, subprocess
root = pathlib.Path(__file__).parent
packed = base64.b64decode(''.join((root / ('part-%d.b64' % n)).read_text() for n in range(2)), validate=True)
assert hashlib.sha256(packed).hexdigest() == '1cd56ca01c659bf174c23ccfb539021f226956274df0bebbbba39957b837a881', 'Patch checksum mismatch'
patch = lzma.decompress(packed)
subprocess.run(['git', 'apply', '--index', '--unidiff-zero', '--check', '-'], input=patch, check=True)
subprocess.run(['git', 'apply', '--index', '--unidiff-zero', '-'], input=patch, check=True)
print('Applied exact validated patch:', len(patch), 'bytes')
