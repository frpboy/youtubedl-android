#!/usr/bin/env python3
"""Disposable qualification emulator only; command evidence survives setup failure."""
import hashlib, json, pathlib, subprocess, sys, time, traceback
sdk = int(sys.argv[1]); out = pathlib.Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
pkg = 'com.frpboy.mavencompat'; serial = None; number = 0
expected = {'FileUtils.copyInputStreamToFile','FileUtils.copyFile','IOUtils.toString',
 'ZipUtils.unzipAndVerify.symlink','ZipUtils.stream','Jackson.VideoInfo',
 'Jackson.depthReject','Jackson.releaseTree','FileUtils.deleteDirectory','FileUtils.deleteQuietly'}
def adb(*args, check=True, retries=0, timeout=40):
 global number
 cmd = ['adb'] + (['-s', serial] if serial else []) + list(args)
 for attempt in range(retries+1):
  number += 1
  try:
   p = subprocess.run(cmd, capture_output=True, timeout=timeout)
   entry = {'number': number, 'command': cmd, 'attempt': attempt+1, 'exit': p.returncode,
            'stdout': p.stdout.decode('utf-8', 'replace'), 'stderr': p.stderr.decode('utf-8', 'replace')}
   if args[:2]==('exec-out','screencap'):
    entry['stdout']={'bytes':len(p.stdout),'sha256':hashlib.sha256(p.stdout).hexdigest()}
  except subprocess.TimeoutExpired as e:
   p = None
   entry = {'number':number,'command':cmd,'attempt':attempt+1,'timeoutSeconds':timeout,
            'stdout':(e.stdout or b'').decode('utf-8','replace'),'stderr':(e.stderr or b'').decode('utf-8','replace')}
  with (out/'commands.jsonl').open('a') as f: f.write(json.dumps(entry)+'\n')
  if p is not None and (p.returncode==0 or not check): return p
  if attempt<retries: time.sleep(2)
 if p is None: raise TimeoutError(f'adb command{number} timed out: {cmd}')
 raise RuntimeError(f'adb command{number} exit{p.returncode}: {cmd}: {entry["stderr"]}')
stage='discover'; result=None
try:
 devices=adb('devices',retries=2,timeout=10); (out/'devices.txt').write_bytes(devices.stdout)
 ready=[x.split()[0] for x in devices.stdout.decode().splitlines() if '\tdevice' in x]
 assert len(ready)==1, 'one emulator required'
 serial=ready[0]
 stage='wait-device'
 adb('wait-for-device',retries=2,timeout=10)
 stage='validate-emulator'
 assert adb('shell','getprop','ro.kernel.qemu',retries=2,timeout=10).stdout.strip()==b'1', 'refuse non-emulator target'
 actual=int(adb('shell','getprop','ro.build.version.sdk',retries=2,timeout=10).stdout.strip()); assert actual==sdk,(actual,sdk)
 (out/'device-properties.txt').write_bytes(adb('shell','getprop').stdout)
 stage='logcat-clear'
 try:
  cleared=adb('logcat','-c',check=False,timeout=10) # Diagnostic only, including timeout.
  (out/'logcat-clear.txt').write_bytes(cleared.stdout+cleared.stderr)
 except TimeoutError as e:
  (out/'logcat-clear.txt').write_text(str(e))
 stage='install'
 installed=adb('install','-r','compat-smoke/build/outputs/apk/debug/compat-smoke-debug.apk')
 (out/'install.txt').write_bytes(installed.stdout+installed.stderr)
 stage='prepare-launch'
 adb('shell','am','force-stop',pkg)
 adb('shell','run-as',pkg,'rm','-f','files/result.json')
 stage='launch'
 launch=adb('shell','am','start','-W','-n',pkg+'/.SmokeActivity')
 (out/'launch.txt').write_bytes(launch.stdout+launch.stderr)
 assert b'Error:' not in launch.stdout+launch.stderr, 'am start reported error'
 stage='await-result'
 for _ in range(90):
  p=adb('exec-out','run-as',pkg,'cat','files/result.json',check=False)
  if p.returncode==0:
   try:
    candidate=json.loads(p.stdout)
    if candidate.get('complete') is True: result=candidate; break
   except json.JSONDecodeError: pass
  time.sleep(1)
 assert result is not None, 'no complete runtime result within90polls'
 (out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
 stage='assert-result'
 assert result.get('sdk')==sdk and result.get('minSdkContract')==24, result
 checks=result.get('checks',{})
 assert expected==set(checks) and all(checks[k] is True for k in expected), checks
 assert result.get('pass') is True, result
 (out/'collector.json').write_text(json.dumps({'pass':True,'stage':'complete','serial':serial,'sdk':sdk})+'\n')
except BaseException:
 (out/'collector.json').write_text(json.dumps({'pass':False,'stage':stage,'serial':serial,'sdk':sdk,'error':traceback.format_exc()},indent=2)+'\n')
 raise
finally:
 # No further adb commands before identity validation. A failed diagnostic cannot mask the primary error.
 if serial and stage not in ('discover','wait-device','validate-emulator'):
  for args,name in [(('logcat','-d'),'logcat.txt'),(('exec-out','screencap','-p'),'screen.png')]:
   try:
    p=adb(*args,check=False); (out/name).write_bytes(p.stdout)
   except Exception as e: (out/(name+'.collector-error.txt')).write_text(repr(e))
