#!/usr/bin/env python3
"""Upload a signed bundle to the Central Portal as USER_MANAGED (validated, NOT released until
someone clicks Publish in the Portal). Requires the exact bundle sha256 as --confirm-bundle-sha256.
The token file holds 'username:password' and is never printed. Default is --dry-run."""
import argparse, base64, hashlib, pathlib, sys, urllib.request, uuid
p = argparse.ArgumentParser()
p.add_argument('--bundle', required=True, type=pathlib.Path)
p.add_argument('--token-file', required=True, type=pathlib.Path)
p.add_argument('--confirm-bundle-sha256', required=True)
p.add_argument('--name', required=True)
p.add_argument('--execute', action='store_true', help='actually POST; default is dry run')
a = p.parse_args()
data = a.bundle.read_bytes()
if hashlib.sha256(data).hexdigest() != a.confirm_bundle_sha256:
    sys.exit('Bundle sha256 does not match the confirmed digest; refusing')
token = a.token_file.read_text().strip()
assert ':' in token, 'token file must hold username:password'
if not a.execute:
    print('DRY RUN ok: would POST', len(data), 'bytes as USER_MANAGED named', a.name); sys.exit(0)
b = uuid.uuid4().hex
body = (f'--{b}\r\nContent-Disposition: form-data; name="bundle"; filename="{a.bundle.name}"\r\n'
        'Content-Type: application/octet-stream\r\n\r\n').encode() + data + f'\r\n--{b}--\r\n'.encode()
url = 'https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED&name=' + urllib.request.quote(a.name)
req = urllib.request.Request(url, data=body, method='POST', headers={
    'Authorization': 'Bearer ' + base64.b64encode(token.encode()).decode(),
    'Content-Type': 'multipart/form-data; boundary=' + b})
with urllib.request.urlopen(req) as r:
    print('HTTP', r.status, 'deployment id', r.read().decode().strip())
