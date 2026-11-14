#!/usr/bin/env python3
"""Build a signed Maven Central deployment bundle from the reviewed staging repository.
Fails closed. Local use only: the passphrase is read from a file descriptor, never printed,
never written into the bundle. No network access and no upload happens here."""
import argparse, hashlib, os, pathlib, re, shutil, subprocess, sys, tempfile, zipfile
p = argparse.ArgumentParser()
p.add_argument('--repository', required=True, type=pathlib.Path)
p.add_argument('--group', required=True)
p.add_argument('--version', required=True)
p.add_argument('--gpg-key-id', required=True)
p.add_argument('--passphrase-file', required=True, type=pathlib.Path)
p.add_argument('--out', required=True, type=pathlib.Path)
p.add_argument('--gnupghome', type=pathlib.Path)
a = p.parse_args()
MODULES = ('common', 'library', 'ffmpeg')
SUFFIXES = ('.aar', '.pom', '.module', '-sources.jar', '-javadoc.jar')
if not re.fullmatch(r'io\.github\.frpboy(\.[a-z0-9-]+)*', a.group):
    sys.exit(f'Refusing group {a.group!r}: must stay inside the verified io.github.frpboy namespace')
if not re.fullmatch(r'\d+\.\d+\.\d+(-(alpha|beta|rc)\.\d+)?', a.version):
    sys.exit(f'Refusing version {a.version!r}: needs a clean release/prerelease string (no staging/snapshot labels)')
if a.out.exists():
    sys.exit(f'Refusing to overwrite {a.out}')
env = dict(os.environ)
if a.gnupghome:
    env['GNUPGHOME'] = str(a.gnupghome)
passphrase = a.passphrase_file.read_bytes().rstrip(b'\r\n')
assert passphrase, 'empty passphrase file'
def digest(path, algo):
    h = hashlib.new(algo); h.update(path.read_bytes()); return h.hexdigest()
with tempfile.TemporaryDirectory() as td:
    root = pathlib.Path(td) / 'bundle'
    entries = []
    for m in MODULES:
        src = a.repository.joinpath(*a.group.split('.'), m, a.version)
        stem = f'{m}-{a.version}'
        dst = root.joinpath(*a.group.split('.'), m, a.version); dst.mkdir(parents=True)
        for s in SUFFIXES:
            f = src / (stem + s)
            assert f.is_file() and not f.is_symlink() and f.stat().st_size > 0, f'Missing or invalid {f}'
            shutil.copyfile(f, dst / f.name)
            entries.append(dst / f.name)
    for f in list(entries):
        if f.name.endswith('.aar'):
            with zipfile.ZipFile(f) as aar:
                for info in aar.infolist():
                    n = info.filename
                    assert not (n.startswith('jni/') or n.lower().endswith(('.so', '.a', '.dll', '.dylib', '.exe'))), f'Native payload {n} in {f.name}'
                    head = aar.read(n)[:4] if not info.is_dir() else b''
                    assert head not in (b'\x7fELF', b'MZ\x90\x00', b'\xcf\xfa\xed\xfe', b'\xca\xfe\xba\xbe'), f'Executable magic in {n} ({f.name})'
        for algo, ext in (('md5', '.md5'), ('sha1', '.sha1'), ('sha256', '.sha256'), ('sha512', '.sha512')):
            f.with_name(f.name + ext).write_text(digest(f, algo) + '\n')
        sig = f.with_name(f.name + '.asc')
        r = subprocess.run(['gpg', '--batch', '--yes', '--pinentry-mode', 'loopback', '--passphrase-fd', '0',
                            '--armor', '--detach-sign', '-u', a.gpg_key_id, '-o', str(sig), str(f)],
                           input=passphrase + b'\n', env=env, capture_output=True)
        assert r.returncode == 0 and sig.is_file(), 'gpg signing failed'
        v = subprocess.run(['gpg', '--batch', '--verify', str(sig), str(f)], env=env, capture_output=True)
        assert v.returncode == 0, f'signature verification failed for {f.name}'
    files = sorted(x for x in root.rglob('*') if x.is_file())
    assert len(files) == 3 * 5 * 6, f'unexpected file count {len(files)}'
    assert not any('.asc.' in x.name for x in files), 'unexpected checksum of a signature'
    with zipfile.ZipFile(a.out, 'w', zipfile.ZIP_DEFLATED) as zf:
        for x in files:
            zf.write(x, x.relative_to(root).as_posix())
print('bundle', a.out, 'sha256', digest(a.out, 'sha256'), 'files', len(files))
