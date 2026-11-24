# Local Android CameraX face evaluation

This procedure evaluates the current YuNet, quality, MiniFASNet ensemble, SFace, and cosine-matching implementation using consented Android CameraX captures. It is local-only: neither the script nor the report uploads images, and it cannot call Spring Boot or create attendance records.

## Collection

1. Create `face-service/evaluation-data/` locally. It is ignored by Git. Do not commit, attach, or upload its contents.
2. Copy `face-service/evaluation-manifest.example.json` to `face-service/evaluation-data/manifest.json`, then replace the illustrative paths with relative paths below that directory.
3. Capture separate CameraX images for each explicitly recorded ground-truth class. The existing debug app writes the current CameraX JPEG only to its cache as `face.jpg`. Immediately after pressing **Capture Photo** (and before taking another capture), export it locally from a USB-debugging-enabled test device. The next capture overwrites this cache file.

   Do **not** use ordinary PowerShell `>` redirection for `adb exec-out`: it can text-encode/corrupt binary JPEG bytes. Use this binary-safe Python command from `face-service` instead (change the destination for each capture):

   ```powershell
   python -c "from pathlib import Path; import subprocess; destination = Path('evaluation-data/genuine/subject01_registration_01.jpg'); result = subprocess.run(['adb', 'exec-out', 'run-as', 'com.college.attendance.mobile', 'cat', 'cache/face.jpg'], stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True); data = result.stdout; assert data.startswith(b'\xff\xd8'), 'Export is not a JPEG (expected FF D8)'; destination.parent.mkdir(parents=True, exist_ok=True); destination.write_bytes(data); print(f'Wrote {len(data)} bytes after JPEG signature validation')"
   ```

   The assertion validates the JPEG SOI signature `FF D8` before writing. Run this only on a consenting test device and keep the output beneath `evaluation-data/`; do not paste the image into a terminal, issue tracker, or chat. If `run-as` is unavailable on a particular device, use Android Studio's Device Explorer for the same app cache file.

4. Use these labels while collecting:
   - `genuine`: a consenting live person, with at least bright/even/low lighting and near/arm-length/far distances.
   - `print_attack`: the camera views a printed photograph; record the print medium and lighting in a local lab note.
   - `screen_replay`: the camera views a photograph or video on a display; record screen type and brightness locally.
5. Use pseudonymous `subject_id` values. For matching, collect a registration image and a separate verification image for the same person; add `reference_id` to the verification entry. Add separately consented genuine images of another person and point a verification sample to the first person's registration image to form an impostor pair.
6. Record the ground truth before running the evaluator. A model decision is never a ground-truth label.

Example command from `face-service`:

```powershell
uv run --with-requirements requirements.txt python evaluate_face_samples.py --manifest evaluation-data/manifest.json --report evaluation-data/reports/evaluation-report.json
```

## Output and interpretation

The report includes, for every sample ID, YuNet exact-one-face status and bounding box, quality result, both MiniFASNet class probabilities/predictions, ensemble liveness decision, SFace reach/success flags, and processing failure reason. It deliberately excludes source paths, pixels, and embeddings.

The summary reports total, evaluated, accepted, rejected, and processing-failure counts per ground-truth group. FRR and FAR use only samples that produced an explicit `liveness.is_real` boolean; decode failures and other samples without a liveness decision are reported separately and excluded from those denominators. Matching pairs that reached the existing `0.60` cosine threshold are reported separately. `offline_comparison_embedding_extracted` is limited to labelled genuine samples and exists only to measure SFace matching independently from the production liveness gate; production continues to skip SFace whenever liveness rejects a capture.

No result is valid until every sample has an explicit ground-truth label. Do not use a report generated from the example manifest as an evaluation result: it contains no images. A meaningful conclusion about the observed `0.0116` score requires the local labelled capture set described above.

## Investigating a YuNet multi-face rejection

For a local reproduction only, set `FACE_LOCAL_DETECTION_DIAGNOSTIC=true` before starting Flask. When a request has zero or multiple YuNet results, the service writes one annotated image with numbered boxes and confidence labels to `face-service/evaluation-data/detection-diagnostics/`. That directory is Git-ignored and is never served over HTTP. The Flask log records the same numeric boxes, scores, dimensions, and pairwise IoU; do not paste the annotated image into a ticket or chat.

The detector retains its configured `0.60` score threshold and `0.30` NMS threshold. It continues to reject every multi-face result, including high-IoU duplicate candidates, until a labelled capture demonstrates that suppression would be safe. In particular, a low-confidence background candidate is not silently ignored merely because it is inconvenient.

OpenCV YuNet returns each result as `[x, y, w, h, 10 landmark coordinates, confidence]`; confidence is element 14, not element 4. The evaluator and local diagnostic annotation use element 14. If an older log showed a "confidence" above 1.0, it was displaying the first landmark x-coordinate and cannot be used to assess the detection's confidence.
