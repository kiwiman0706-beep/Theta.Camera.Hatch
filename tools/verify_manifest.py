from pathlib import Path
import hashlib,json
root=Path(__file__).resolve().parents[1]
m=json.loads((root/'MANIFEST.json').read_text(encoding='utf-8'))
for item in m['files']:
    p=root/item['path']
    assert p.is_file(), 'Missing: '+item['path']
    assert p.stat().st_size==item['bytes'], 'Size mismatch: '+item['path']
    assert hashlib.sha256(p.read_bytes()).hexdigest()==item['sha256'], 'Hash mismatch: '+item['path']
expected={x['path'] for x in m['files']}|{'MANIFEST.json','SHA256SUMS.txt'}
actual={str(p.relative_to(root)) for p in root.rglob('*') if p.is_file() and '__pycache__' not in p.parts}
assert expected==actual, 'Unexpected or missing files: '+repr(expected^actual)
print('Verified',len(m['files']),'files.')
