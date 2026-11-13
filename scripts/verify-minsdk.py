#!/usr/bin/env python3
"""Fail closed if any published wrapper module advertises an Android floor below API 26.
The owned library uses java.nio.file.Files (API 26+), so the declared floor must not be lower."""
import pathlib, re, sys
root = pathlib.Path(__file__).resolve().parent.parent
bad = []
for module in ('common', 'library', 'ffmpeg'):
    text = root.joinpath(module, 'build.gradle.kts').read_text()
    m = re.search(r'minSdk\s*=\s*(\d+)', text)
    assert m, f'{module}: no minSdk declaration found'
    floor = int(m.group(1))
    if floor < 26:
        bad.append(f'{module}: minSdk={floor}')
assert not bad, 'Owned modules must not advertise a floor below API 26: ' + '; '.join(bad)
print('minSdk floor = 26 across common, library, ffmpeg')
