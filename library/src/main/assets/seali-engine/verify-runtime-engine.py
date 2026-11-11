"""Offline candidate capability check, run by the packaged Python before and after replace."""
import sys, hashlib, json, subprocess
candidate, runtime, expected_version = sys.argv[1:4]
sys.path.insert(0, candidate)
from yt_dlp.version import __version__
if expected_version != "*":
    assert __version__ == expected_version, (__version__, expected_version)
from yt_dlp import YoutubeDL
from yt_dlp.utils import match_str
from yt_dlp.extractor.youtube.jsc._builtin import ejs, vendor
from yt_dlp_ejs import version as ejs_version
from yt_dlp_ejs.yt import solver
assert ejs_version == vendor.VERSION, (ejs_version, vendor.VERSION)
for name, source in [('lib', solver.lib()), ('core', solver.core())]:
    expected = vendor.HASHES['yt.solver.%s.min.js' % name]
    digest = hashlib.sha3_512(source.encode()).hexdigest()
    assert digest == expected, 'EJS %s hash mismatch' % name
    assert len(source) > 1000, 'Missing EJS solver source'
assert not match_str('!is_live & !is_upcoming & live_status!=?post_live', {'is_live': True})
assert match_str('!is_live & !is_upcoming & live_status!=?post_live', {'is_live': False, 'live_status': 'not_live'})
# Offline QuickJS execution, not a YouTube challenge or a remote component download.
result = subprocess.run([runtime, '-e', 'if (JSON.stringify({ok:6n.toString()})!==\'{"ok":"6"}\') throw Error("JS"); console.log("SEALI_JS_OK")'], capture_output=True, text=True, timeout=8, check=True)
assert result.stdout.strip() == 'SEALI_JS_OK', result.stdout
print(json.dumps({'engine': __version__, 'ejs': ejs_version, 'solver_hashes': 'PASS', 'native_filters': 'PASS', 'quickjs_offline': 'PASS'}))
