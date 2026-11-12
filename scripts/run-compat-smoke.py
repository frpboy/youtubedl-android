#!/usr/bin/env python3
"""Owner-reviewed qualification APK only, never Seali or a person's device."""
import json,pathlib,subprocess,sys,time
sdk=int(sys.argv[1]);out=pathlib.Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
pkg='com.frpboy.mavencompat'
def adb(*args,check=True):
 return subprocess.run(['adb',*args],capture_output=True,check=check)
result=None
try:
 serials=adb('devices').stdout.decode();(out/'devices.txt').write_text(serials)
 assert len([x for x in serials.splitlines() if '\tdevice' in x])==1,'one emulator required'
 assert adb('shell','getprop','ro.kernel.qemu').stdout.strip()==b'1','refuse non-emulator target'
 actual=int(adb('shell','getprop','ro.build.version.sdk').stdout.strip());assert actual==sdk,(actual,sdk)
 (out/'device-properties.txt').write_bytes(adb('shell','getprop').stdout)
 adb('logcat','-c')
 adb('install','-r','compat-smoke/build/outputs/apk/debug/compat-smoke-debug.apk')
 adb('shell','am','force-stop',pkg)
 # Run-as removal is limited to the separate disposable qualification package.
 adb('shell','run-as',pkg,'rm','-f','files/result.json')
 launch=adb('shell','am','start','-W','-n',pkg+'/.SmokeActivity')
 (out/'launch.txt').write_bytes(launch.stdout+launch.stderr)
 for _ in range(90):
  p=adb('exec-out','run-as',pkg,'cat','files/result.json',check=False)
  if p.returncode==0:
   try:result=json.loads(p.stdout);break
   except json.JSONDecodeError:pass
  time.sleep(1)
 assert result is not None,'no complete runtime result within90seconds'
 (out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
 assert result.get('sdk')==sdk and result.get('pass') is True,result
 checks=result.get('checks',{})
 expected={'FileUtils.copyInputStreamToFile','FileUtils.copyFile','IOUtils.toString','ZipUtils.unzipAndVerify.symlink','ZipUtils.stream','Jackson.VideoInfo','Jackson.depthReject','Jackson.releaseTree','FileUtils.deleteDirectory','FileUtils.deleteQuietly'}
 assert expected<=checks.keys() and all(checks[k] is True for k in expected),checks
finally:
 (out/'logcat.txt').write_bytes(adb('logcat','-d',check=False).stdout)
 (out/'screen.png').write_bytes(adb('exec-out','screencap','-p',check=False).stdout)
