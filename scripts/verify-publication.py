#!/usr/bin/env python3
"""Fail closed on wrapper publication contents and the bound yt-dlp engine."""
import argparse, hashlib, json, pathlib, xml.etree.ElementTree as ET, zipfile
p=argparse.ArgumentParser()
p.add_argument('--repository',required=True,type=pathlib.Path)
p.add_argument('--group',required=True)
p.add_argument('--version',required=True)
p.add_argument('--engine-sha256',required=True)
a=p.parse_args(); ns={'m':'http://maven.apache.org/POM/4.0.0'}; result=[]
for module in ('common','library','ffmpeg'):
 d=a.repository.joinpath(*a.group.split('.'),module,a.version); stem=f'{module}-{a.version}'
 for suffix in ('.aar','.pom','.module','-sources.jar','-javadoc.jar'):
  f=d/(stem+suffix); assert f.is_file(), f'Missing {f}'
  assert f.stat().st_size>0, f'Empty {f}'
 root=ET.parse(d/(stem+'.pom')).getroot()
 assert root.findtext('m:groupId',namespaces=ns)==a.group
 assert root.findtext('m:artifactId',namespaces=ns)==module
 assert root.findtext('m:version',namespaces=ns)==a.version
 for dep in root.findall('m:dependencies/m:dependency',ns):
  group=dep.findtext('m:groupId',namespaces=ns); name=dep.findtext('m:artifactId',namespaces=ns)
  assert 'junkfood' not in group, 'Stale Junkfood dependency'
  if name in ('common','library','ffmpeg'):
   assert group==a.group and dep.findtext('m:version',namespaces=ns)==a.version
 aar=d/(stem+'.aar')
 with zipfile.ZipFile(aar) as z:
  assert not any(x.startswith('jni/') for x in z.namelist()), 'Native payload unexpectedly included'
  assert 'classes.jar' in z.namelist()
  if module=='library':
   assert hashlib.sha256(z.read('res/raw/ytdlp')).hexdigest()==a.engine_sha256, 'Engine resource mismatch'
 with zipfile.ZipFile(d/(stem+'-sources.jar')) as z:
  assert any(x.endswith('.kt') for x in z.namelist()), 'Actual Kotlin sources absent'
 result.append({'module':module,'aarSha256':hashlib.sha256(aar.read_bytes()).hexdigest()})
print(json.dumps({'group':a.group,'version':a.version,'artifacts':result},indent=2))
