#!/usr/bin/env python3
"""No device required. Synthetic command fixtures validate collector control flow only."""
import json, pathlib, runpy, subprocess, tempfile, unittest
from unittest.mock import patch
SCRIPT=pathlib.Path(__file__).with_name('run-compat-smoke.py')
NAMES={'FileUtils.copyInputStreamToFile','FileUtils.copyFile','IOUtils.toString','ZipUtils.unzipAndVerify.symlink','ZipUtils.stream','Jackson.VideoInfo','Jackson.depthReject','Jackson.releaseTree','FileUtils.deleteDirectory','FileUtils.deleteQuietly'}
class Collector(unittest.TestCase):
 def run_case(self, mode):
  calls=[]
  def fake(cmd,**kw):
   calls.append(cmd); a=cmd[3:] if cmd[1:2]==['-s'] else cmd[1:]; code=0; stdout=b''; stderr=b''
   if mode=='transient' and len(calls)==1:return subprocess.CompletedProcess(cmd,1,b'',b'transient early adb')
   if a==['devices']:stdout=b'List of devices attached\nemulator-5554\tdevice\n'
   elif a==['shell','getprop','ro.kernel.qemu']:stdout=b'0' if mode=='physical' else b'1'
   elif a==['shell','getprop','ro.build.version.sdk']:stdout=b'26' if mode=='wrong-sdk' else b'24'
   elif a==['logcat','-c'] and mode=='clear-timeout':raise subprocess.TimeoutExpired(cmd,10,stderr=b'synthetic timeout')
   elif a==['logcat','-c'] and mode=='clear-failure':code=1;stderr=b'clear denied synthetic'
   elif a[0]=='install':
    stdout=b'Success'
    if mode=='install-failure':code=1;stderr=b'INSTALL_FAILED synthetic'
   elif a[:2]==['exec-out','run-as']:
    checks={k:True for k in NAMES}
    if mode=='probe-failure':checks['ZipUtils.stream']=False
    result={'sdk':24,'minSdkContract':24,'complete':mode!='incomplete','checks':checks,'pass':all(checks.values())}
    stdout=json.dumps(result).encode()
   elif a[:2]==['exec-out','screencap']:stdout=b'synthetic-not-image'
   return subprocess.CompletedProcess(cmd,code,stdout,stderr)
  with tempfile.TemporaryDirectory() as t:
   error=None
   with patch('sys.argv',[str(SCRIPT),'24',t]),patch('subprocess.run',fake),patch('time.sleep'):
    try:runpy.run_path(str(SCRIPT),run_name='__main__')
    except BaseException as e:error=e
   p=pathlib.Path(t); receipt=json.loads((p/'collector.json').read_text()); commands=[json.loads(x) for x in (p/'commands.jsonl').read_text().splitlines()]
   return error,receipt,calls,commands
 def test_early_retry(self):
  e,r,c,logs=self.run_case('transient');self.assertIsNone(e);self.assertTrue(r['pass']);self.assertEqual(logs[0]['exit'],1);self.assertEqual(logs[1]['attempt'],2)
 def test_success(self):self.assertTrue(self.run_case('ok')[1]['pass'])
 def test_nonfatal_clear_timeout(self):
  e,r,c,logs=self.run_case('clear-timeout');self.assertIsNone(e);self.assertTrue(r['pass']);self.assertTrue(any(x.get('timeoutSeconds')==10 for x in logs))
 def test_nonfatal_clear(self):
  e,r,c,logs=self.run_case('clear-failure');self.assertIsNone(e);self.assertTrue(r['pass']);self.assertTrue(any(x['stderr']=='clear denied synthetic' for x in logs))
 def test_install_failure_has_stderr(self):
  e,r,c,logs=self.run_case('install-failure');self.assertIsNotNone(e);self.assertEqual(r['stage'],'install');self.assertIn('INSTALL_FAILED',r['error']);self.assertFalse(any('start' in x for x in c))
 def test_wrong_sdk_no_install(self):
  e,r,c,logs=self.run_case('wrong-sdk');self.assertIsNotNone(e);self.assertFalse(any('install' in x for x in c))
 def test_physical_refused(self):
  e,r,c,logs=self.run_case('physical');self.assertIsNotNone(e);self.assertFalse(any('install' in x or 'logcat' in x for x in c))
 def test_independent_probe_failure_fails_collector(self):
  e,r,c,logs=self.run_case('probe-failure');self.assertIsNotNone(e);self.assertEqual(r['stage'],'assert-result')
 def test_incomplete_never_passes(self):
  e,r,c,logs=self.run_case('incomplete');self.assertIsNotNone(e);self.assertEqual(r['stage'],'await-result')
if __name__=='__main__':unittest.main()
