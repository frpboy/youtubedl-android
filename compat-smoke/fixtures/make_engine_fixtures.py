#!/usr/bin/env python3
"""Deterministic synthetic yt-dlp engine fixtures for compat-smoke runtime qualification.

Each fixture is a real Python zipapp (a zip with __main__.py + identity.txt at the
root), the same shape as the real yt-dlp engine file, runnable by the bundled CPython:
  python <engine> --dump-json <url>     -> one canned VideoInfo JSON line
  python <engine> --version             -> canned version string
  --fixture-handshake                   -> ready/release handshake mode (probes)

Discrimination design: identity is read from identity.txt INSIDE the archive named by
sys.argv[0]. A handshake child re-reads it only AFTER the harness-owned release signal,
so which bytes that late read sees (a private launch snapshot vs a swapped installed
file) is exactly what the snapshot-isolation probe and its direct-path negative
control discriminate on.

Handshake design (deterministic, no sleep-duration reasoning):
  1. Ready is published ATOMICALLY: the child fully writes and closes a temp
     sibling and then renames it to fixture-child-started-<pid> NEXT TO its
     private $TMPDIR (never inside it). The harness only ever observes a
     completely written ready file; a write/close/rename failure raises BEFORE
     the ready name exists, and a start with no ready receipt fails the harness's
     20s start gate.
  2. The child then waits for fixture-release-<pid> at the same location - the
     release signal, written by the harness only when the probe's setup (such as a
     guarded engine swap) is durably complete. The late identity read is therefore
     CAUSALLY after the release, which is causally after the probe setup.
  3. There is NO autonomous successful exit and NO internal timeout: the child
     leaves the wait only via the explicit release signal or external
     termination. The workflow ceiling is the crash fallback.
  4. A handshake error that CAN be recorded leaves a durable sibling receipt
     (fixture-handshake-error-<pid>, also atomically published) and the child
     exits with code 3 plus a stderr marker; the cancellation proof explicitly
     rejects any such receipt. An error whose receipt CANNOT be recorded parks
     the child in the wait for external cancellation rather than exiting with an
     unobservable error; a parked child fails the harness's bounded joins.
  5. The cancellation probe never writes the release before its assertions, so
     the only exits that can satisfy its finish bound are destruction (accepted)
     or a recorded error (rejected via the receipt).

Sentinel/release hygiene: all handshake files are harness-owned siblings of the
invocation directory. Production cleanup (OwnedInvocation.cleanAfterExit) owns the
invocation directory's contents and rightly counts any foreign file there as a
cleanup failure; the sibling files are deleted by the harness.

Deterministic: fixed entry names, order, date and content.
"""
import hashlib
import sys
import zipfile

TEMPLATE = '''import json
import os
import sys
import time
import zipfile

VERSION = {version!r}


def late_identity(engine_path: str) -> str:
    with zipfile.ZipFile(engine_path) as archive:
        return archive.read("identity.txt").decode("utf-8").strip()


def handshake(parent: str) -> None:
    pid = os.getpid()
    # Publish ready ATOMICALLY: fully write and close a temp sibling, then rename.
    # An observer only ever sees a completely written ready file; a write, close
    # or rename failure raises before the ready name exists.
    starting = os.path.join(parent, "fixture-child-starting-%d" % pid)
    with open(starting, "w") as fh:
        fh.write("started")
    os.replace(starting, os.path.join(parent, "fixture-child-started-%d" % pid))
    release = os.path.join(parent, "fixture-release-%d" % pid)
    # No autonomous successful exit and no internal timeout: the child leaves
    # this wait only via the explicit harness-owned release signal or external
    # termination. The workflow ceiling is the crash fallback.
    while not os.path.exists(release):
        time.sleep(0.1)


def record_error(parent: str, exc: BaseException) -> None:
    # Durable sibling error receipt, also published atomically.
    tmp = os.path.join(parent, "fixture-child-error-%d.tmp" % os.getpid())
    with open(tmp, "w") as fh:
        fh.write("FIXTURE-HANDSHAKE-ERROR %r" % (exc,))
    os.replace(tmp, os.path.join(parent, "fixture-handshake-error-%d" % os.getpid()))


def main() -> int:
    args = sys.argv[1:]
    if "--version" in args:
        print(VERSION)
        return 0
    ident = late_identity(sys.argv[0])
    if "--fixture-handshake" in args:
        try:
            handshake(os.path.dirname(os.environ["TMPDIR"].rstrip("/")))
        except (KeyError, OSError) as exc:
            try:
                record_error(os.path.dirname(os.environ["TMPDIR"].rstrip("/")), exc)
            except Exception:
                # The error receipt itself cannot be recorded: park for external
                # cancellation rather than exit with an unobservable error.
                while True:
                    time.sleep(1)
            print("FIXTURE-HANDSHAKE-ERROR %r" % (exc,), file=sys.stderr)
            return 3
        ident = late_identity(sys.argv[0])
    if "--dump-json" in args:
        print(json.dumps({{"id": ident, "title": "Fixture Engine", "ext": "mp4"}}))
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
'''

FIXTURES = {
    "ytdlp": ("fixture-engine-id", "2026.11.12-fixture"),
    "ytdlp_swapped": ("swapped-engine-id", "2099.01.01-swapped"),
}


def build(path: str, ident: str, version: str) -> str:
    source = TEMPLATE.format(version=version).encode()
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, payload in (("__main__.py", source), ("identity.txt", (ident + "\n").encode())):
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, payload)
    digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
    print(f"{digest}  {path}")
    return digest


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "compat-smoke/src/main/res/raw"
    for name, (ident, version) in FIXTURES.items():
        build(f"{out}/{name}", ident, version)
