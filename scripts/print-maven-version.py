#!/usr/bin/env python3
"""Print the single source of truth for the Maven version (root build.gradle.kts default)."""
import pathlib, re
t = pathlib.Path(__file__).resolve().parent.parent.joinpath('build.gradle.kts').read_text()
m = re.search(r'gradleProperty\("mavenVersion"\)\.getOrElse\("([^"]+)"\)', t)
assert m, 'mavenVersion default not found'
print(m.group(1))
