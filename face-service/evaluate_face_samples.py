"""Local-only evaluation of the production face-processing components.

This utility never sends data over the network and never writes images or
embeddings.  It accepts only an explicitly labelled manifest of local Android
CameraX JPEG captures and writes a JSON report containing decisions and safe
numeric metadata.  It is not an attendance client and cannot create records.
"""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import cv2
import numpy as np

import face_utils


ALLOWED_LABELS = {"genuine", "print_attack", "screen_replay"}
MATCH_ROLES = {"registration", "verification"}
# This is the existing Spring default, used only to label offline results.  It
# does not configure or alter the running service's matching policy.
PRODUCTION_COSINE_THRESHOLD = 0.60
CLASS_NAMES = ("print_attack", "real_face", "replay_attack")


def _path_under(root: Path, candidate: Path) -> bool:
    try:
        candidate.resolve().relative_to(root.resolve())
        return True
    except ValueError:
        return False


def load_manifest(path: Path) -> Tuple[Path, List[Dict[str, Any]]]:
    """Validate the minimal, explicitly-labelled local sample manifest."""
    payload = json.loads(path.read_text(encoding="utf-8"))
    samples = payload.get("samples")
    if not isinstance(samples, list) or not samples:
        raise ValueError("Manifest must contain a non-empty 'samples' list")

    root = path.parent.resolve()
    ids = set()
    for sample in samples:
        if not isinstance(sample, dict):
            raise ValueError("Each sample must be an object")
        sample_id = sample.get("id")
        label = sample.get("ground_truth")
        relative_path = sample.get("path")
        if not isinstance(sample_id, str) or not sample_id or sample_id in ids:
            raise ValueError("Every sample needs a unique non-empty id")
        if label not in ALLOWED_LABELS:
            raise ValueError(f"Sample {sample_id!r} has an unsupported ground_truth label")
        if not isinstance(relative_path, str) or not relative_path:
            raise ValueError(f"Sample {sample_id!r} needs a relative local path")
        file_path = (root / relative_path).resolve()
        if Path(relative_path).is_absolute() or not _path_under(root, file_path):
            raise ValueError(f"Sample {sample_id!r} path must stay under the manifest directory")
        if not file_path.is_file():
            raise ValueError(f"Sample {sample_id!r} image file is missing")
        role = sample.get("comparison_role")
        if role is not None and role not in MATCH_ROLES:
            raise ValueError(f"Sample {sample_id!r} has an unsupported comparison_role")
        if role in MATCH_ROLES and (not isinstance(sample.get("subject_id"), str) or not sample["subject_id"]):
            raise ValueError(f"Sample {sample_id!r} comparison sample needs a pseudonymous subject_id")
        ids.add(sample_id)
    return root, samples


def _reason_for_detection_error(error: ValueError) -> str:
    return "MULTIPLE_FACES" if str(error).startswith("Expected exactly one face") else "NO_FACE"


def _probability_record(values: Optional[np.ndarray]) -> Optional[Dict[str, Any]]:
    if values is None:
        return None
    probabilities = [round(float(value), 6) for value in values]
    predicted = int(np.argmax(values))
    return {
        "probabilities": dict(zip(CLASS_NAMES, probabilities)),
        "predicted_class": CLASS_NAMES[predicted],
    }


def _safe_quality(liveness: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    quality = liveness.get("_diag_quality")
    if not isinstance(quality, dict):
        return None
    # All values originate from the existing numeric quality diagnostics.
    return {key: quality.get(key) for key in (
        "laplacian_var", "laplacian_var_raw", "laplacian_var_whole",
        "brightness_mean", "face_ratio", "blur_threshold", "min_brightness",
        "max_brightness", "min_face_ratio", "quality_result",
    )}


def evaluate_samples(samples: List[Dict[str, Any]], manifest_root: Path) -> Tuple[List[Dict[str, Any]], Dict[str, np.ndarray]]:
    """Run the same YuNet/MiniFASNet/SFace components as the Flask route.

    Only results are retained.  Decoded pixels and embeddings are discarded at
    the end of the process; embeddings remain in memory solely to form local
    comparison pairs.
    """
    # Delayed imports keep manifest/metrics unit tests independent of model files.
    from app import decode_image_upright
    from face_pipeline import FaceVerificationPipeline

    pipeline = FaceVerificationPipeline()
    results: List[Dict[str, Any]] = []
    comparison_embeddings: Dict[str, np.ndarray] = {}

    for sample in samples:
        result: Dict[str, Any] = {
            "id": sample["id"],
            "ground_truth": sample["ground_truth"],
            "lighting": sample.get("lighting", "unspecified"),
            "distance": sample.get("distance", "unspecified"),
            "comparison_role": sample.get("comparison_role"),
            "exactly_one_face_detected": False,
            "quality": None,
            "minifasnet_v2": None,
            "minifasnet_v1se": None,
            "liveness": None,
            "sface_extraction_reached": False,
            "sface_extraction_succeeded": False,
            "offline_comparison_embedding_extracted": False,
            "processing_failure": None,
        }
        image_path = (manifest_root / sample["path"]).resolve()
        try:
            raw = np.frombuffer(image_path.read_bytes(), np.uint8)
            image, orientation = decode_image_upright(raw)
            if image is None:
                result["processing_failure"] = "INVALID_IMAGE"
                results.append(result)
                continue
            result["exif_orientation"] = int(orientation)

            try:
                face_info = pipeline.detector.detect(image)
            except ValueError as error:
                result["processing_failure"] = _reason_for_detection_error(error)
                result["yunet"] = getattr(error, "diagnostics", None)
                results.append(result)
                continue

            result["exactly_one_face_detected"] = True
            result["yunet"] = {
                "bbox": [round(float(value), 2) for value in face_info[:4]],
                "confidence": round(float(face_info[face_utils.YUNET_SCORE_INDEX]), 6),
            }
            liveness = pipeline.liveness_detector.assess(image, face_info[:4])
            result["quality"] = _safe_quality(liveness)
            result["minifasnet_v2"] = _probability_record(liveness.get("_diag_m1_probabilities"))
            result["minifasnet_v1se"] = _probability_record(liveness.get("_diag_m2_probabilities"))
            ensemble = liveness.get("_diag_probabilities")
            result["liveness"] = {
                "ensemble_probabilities": (dict(zip(CLASS_NAMES, [round(float(x), 6) for x in ensemble]))
                                           if ensemble is not None else None),
                "is_real": bool(liveness["is_real"]),
                "score": float(liveness["liveness_score"]),
                "rejection_reason": liveness.get("rejection_reason"),
            }

            # This duplicates only the final align/extract portion of
            # FaceVerificationPipeline.process_detected, avoiding a second liveness inference.
            if liveness["is_real"]:
                result["sface_extraction_reached"] = True
                try:
                    aligned = pipeline.aligner.align(image, face_info)
                    embedding = pipeline.embedding_extractor.extract(aligned)
                    result["sface_extraction_succeeded"] = True
                except (ValueError, cv2.error, RuntimeError):
                    result["processing_failure"] = "SFACE_EXTRACTION_FAILED"
                    embedding = None
            else:
                embedding = None

            # Offline matching is intentionally limited to labelled genuine
            # captures. This tests registration/verification consistency even
            # when liveness rejects one capture; it never changes production's
            # liveness-before-SFace policy and never processes attack samples.
            if sample["ground_truth"] == "genuine" and sample.get("comparison_role") in MATCH_ROLES:
                try:
                    comparison_embedding = embedding
                    if comparison_embedding is None:
                        aligned = pipeline.aligner.align(image, face_info)
                        comparison_embedding = pipeline.embedding_extractor.extract(aligned)
                    comparison_embeddings[sample["id"]] = comparison_embedding
                    result["offline_comparison_embedding_extracted"] = True
                except (ValueError, cv2.error, RuntimeError):
                    result["processing_failure"] = result["processing_failure"] or "SFACE_EXTRACTION_FAILED"
        except (OSError, ValueError, cv2.error, RuntimeError):
            result["processing_failure"] = result["processing_failure"] or "PROCESSING_ERROR"
        results.append(result)
    return results, comparison_embeddings


def cosine_similarity(first: np.ndarray, second: np.ndarray) -> Optional[float]:
    denominator = float(np.linalg.norm(first) * np.linalg.norm(second))
    if denominator == 0.0 or not np.isfinite(denominator):
        return None
    return float(np.dot(first, second) / denominator)


def build_match_pairs(samples: List[Dict[str, Any]], embeddings: Dict[str, np.ndarray]) -> List[Dict[str, Any]]:
    by_id = {sample["id"]: sample for sample in samples}
    pairs = []
    for sample in samples:
        reference_id = sample.get("reference_id")
        if sample.get("comparison_role") != "verification" or not reference_id:
            continue
        reference = by_id.get(reference_id)
        pair: Dict[str, Any] = {
            "verification_id": sample["id"],
            "reference_id": reference_id,
            "expected_same_subject": bool(reference and reference.get("subject_id") == sample.get("subject_id")),
            "cosine_matching_reached": False,
            "similarity": None,
            "matched_at_production_threshold": False,
            "processing_failure": None,
        }
        if reference is None:
            pair["processing_failure"] = "UNKNOWN_REFERENCE"
        elif sample["id"] not in embeddings or reference_id not in embeddings:
            pair["processing_failure"] = "COMPARISON_EMBEDDING_UNAVAILABLE"
        else:
            similarity = cosine_similarity(embeddings[sample["id"]], embeddings[reference_id])
            if similarity is None:
                pair["processing_failure"] = "INVALID_COMPARISON_EMBEDDING"
            else:
                pair["cosine_matching_reached"] = True
                pair["similarity"] = round(similarity, 6)
                pair["matched_at_production_threshold"] = similarity >= PRODUCTION_COSINE_THRESHOLD
        pairs.append(pair)
    return pairs


def _rate(numerator: int, denominator: int) -> Optional[float]:
    return round(numerator / denominator, 6) if denominator else None


def summarize_evaluation(samples: List[Dict[str, Any]], pairs: List[Dict[str, Any]]) -> Dict[str, Any]:
    genuine = [item for item in samples if item["ground_truth"] == "genuine"]
    spoof = [item for item in samples if item["ground_truth"] in {"print_attack", "screen_replay"}]

    def liveness_decision(item: Dict[str, Any]) -> Optional[bool]:
        """Return a decision only when the pipeline actually produced one.

        A missing or malformed liveness object is a processing/evaluation
        failure, never an implicit liveness rejection.
        """
        liveness = item.get("liveness")
        decision = liveness.get("is_real") if isinstance(liveness, dict) else None
        return decision if isinstance(decision, bool) else None

    def liveness_counts(items: List[Dict[str, Any]]) -> Dict[str, int]:
        decisions = [liveness_decision(item) for item in items]
        evaluated = [decision for decision in decisions if decision is not None]
        return {
            "total": len(items),
            "evaluated": len(evaluated),
            "accepted": sum(decision is True for decision in evaluated),
            "rejected": sum(decision is False for decision in evaluated),
            "processing_failures": sum(item.get("processing_failure") is not None for item in items),
        }

    genuine_counts = liveness_counts(genuine)
    spoof_counts = liveness_counts(spoof)
    reached = [pair for pair in pairs if pair["cosine_matching_reached"]]
    same_subject = [pair for pair in reached if pair["expected_same_subject"]]
    impostor = [pair for pair in reached if not pair["expected_same_subject"]]
    return {
        "liveness": {
            "genuine_total": genuine_counts["total"],
            "genuine_evaluated": genuine_counts["evaluated"],
            "genuine_accepted": genuine_counts["accepted"],
            "genuine_rejected": genuine_counts["rejected"],
            "genuine_processing_failures": genuine_counts["processing_failures"],
            "false_rejection_rate": _rate(genuine_counts["rejected"], genuine_counts["evaluated"]),
            "spoof_total": spoof_counts["total"],
            "spoof_evaluated": spoof_counts["evaluated"],
            "spoof_rejected": spoof_counts["rejected"],
            "spoof_accepted": spoof_counts["accepted"],
            "spoof_processing_failures": spoof_counts["processing_failures"],
            "false_acceptance_rate": _rate(spoof_counts["accepted"], spoof_counts["evaluated"]),
        },
        "matching": {
            "cosine_threshold": PRODUCTION_COSINE_THRESHOLD,
            "pairs_total": len(pairs),
            "pairs_reached": len(reached),
            "same_subject_total": len(same_subject),
            "same_subject_accepted": sum(pair["matched_at_production_threshold"] for pair in same_subject),
            "impostor_total": len(impostor),
            "impostor_accepted": sum(pair["matched_at_production_threshold"] for pair in impostor),
        },
        "processing_failures": sum(item["processing_failure"] is not None for item in samples),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Run local labelled CameraX face evaluation")
    parser.add_argument("--manifest", required=True, type=Path, help="Local JSON manifest; images must be beneath it")
    parser.add_argument("--report", required=True, type=Path, help="Local JSON report destination")
    args = parser.parse_args()

    root, samples = load_manifest(args.manifest.resolve())
    results, embeddings = evaluate_samples(samples, root)
    pairs = build_match_pairs(samples, embeddings)
    report = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "local_only": True,
        "notes": [
            "No images, image paths, or embeddings are included in this report.",
            "Offline comparison embeddings are used only for labelled genuine samples and do not alter production liveness-before-SFace behavior.",
        ],
        "samples": results,
        "matching_pairs": pairs,
        "summary": summarize_evaluation(results, pairs),
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("Evaluation complete: report contains decisions only; no images or embeddings were written.")
    print(json.dumps(report["summary"], indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
