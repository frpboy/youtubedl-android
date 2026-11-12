# Owner correction route - review only, no runtime start

The first owner attempt f78f2d3de8c3250542ae7122f26aee6b4f7c8a3c added a1byte newline marker with default Create title. Run43146564426 failed admission in both jobs. Runtime/build/emulator steps were skipped and0artifacts exist. This was not an Android compatibility failure. Do not rerun that run or repeat ADD-only instructions.

Proposed preparation is exactly ONE commit on existing qualification/api24-stock-modern-20261112, parent exact failed owner commit above. Modify .github/workflows/build.yml and add this note as docs/API24-OWNER-CORRECTION-ROUTE.md. DO NOT modify empty marker. Exact branch+marker-path push trigger means preparation push does not run. No default/PR/staging/signing/publication/source changes.

Next owner makes a single UI edit of EXISTING .qualification/api24-owner-trigger.json, commits directly to same qualification branch (no new branch/PR), exact JSON and new title below. Admission requires sole MODIFICATION of marker, exact frpboy actor/sender/ref, single nonforced push, HEAD parent push-before, HEAD~2 exact failedownercommit, parent marker exact newline blob8b137891791fe96927ad78e64b0aad7bded08bdc, preparation diff exactly workflowMOD+thisdocADD, exact JSON and title. A second edit is rejected by failedownerancestor check. Fixed runtime source b6708c41/tree592ca93a and all fixture/receipt checks stay byte-for-byte unchanged. No action before independent review and live readback.

FILE CONTENTS (replace blank file, do not paste into commit title):
{"sourceCommit":"b6708c41b1925802cba466697b4b94ffe2a11a81","purpose":"stock-api24-api26-runtime-only","ownerStart":true}

COMMIT TITLE (after clicking Commit changes; do not paste into file):
Correct marker and start reviewed API24 runtime experiment

Owner instruction draft: open the existing file edit page, paste JSON in file editor, verify text visible, click Commit changes, replace default title with exact title above, leave direct commit to qualification/api24-stock-modern-20261112, submit. No deletion/newfile. Main provides verified link only after route readback; do not send these steps prematurely.

Operator UI start is not cryptographic human-presence proof: sameaccount token indistinguishable. Agents never edit marker/send start commit. Runtime experiment results may fail onAPI24; final app/security/license qualification remains open. Inspect JSON/logcat/screenshot/APK hashes after actual runtime, not admission failures.
