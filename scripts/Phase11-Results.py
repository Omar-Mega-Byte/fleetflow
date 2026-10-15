"""Explicit expected-response accounting shared by disposable workload reports."""
from collections import Counter
import math
import re


def operational_samples(prometheus):
    """Keep fixed operator signals; missing/invalid metrics remain unknown, never healthy zeroes."""
    selected = {
        "gc_pause_seconds_total": ("jvm_gc_pause_seconds_sum", sum),
        "gc_pause_count": ("jvm_gc_pause_seconds_count", sum),
        "heap_used_bytes": ("jvm_memory_used_bytes", sum),
        "db_active": ("hikaricp_connections_active", max),
        "db_pending": ("hikaricp_connections_pending", max),
        "db_max": ("hikaricp_connections_max", max),
        "db_available": ("fleetflow_operations_database_available", min),
        "redis_available": ("fleetflow_operations_redis_available", min),
        "outbox_pending": ("fleetflow_outbox_pending", max),
        "outbox_oldest_seconds": ("fleetflow_outbox_oldest_seconds", max),
        "consumer_lag_max": ("kafka_consumer_fetch_manager_records_lag_max", max),
        "sse_active": ("fleetflow_tracking_active", max),
    }
    samples = {}
    for key, (name, aggregate) in selected.items():
        values = []
        for labels, value in re.findall(r"^" + name + r"(\{[^\n]*\})?\s+(\S+)", prometheus, re.MULTILINE):
            if key == "heap_used_bytes" and 'area="heap"' not in labels:
                continue
            try:
                numeric = float(value)
                if math.isfinite(numeric): values.append(numeric)
            except ValueError:
                pass
        samples[key] = aggregate(values) if values else None
    return samples


def response_summary(results, expected_by_kind=None):
    """A 4xx is expected only for an explicitly declared negative-probe kind."""
    expected_by_kind = expected_by_kind or {}
    codes = Counter(str(code) for _, code, _ in results)
    expected_rejections = Counter()
    successful = unexpected = infrastructure = unexpected_client = 0
    for kind, code, _ in results:
        expected = expected_by_kind.get(kind, {200, 204})
        if code in expected:
            if 200 <= code < 300:
                successful += 1
            else:
                expected_rejections[str(code)] += 1
        else:
            unexpected += 1
            if code == 0 or code >= 500:
                infrastructure += 1
            else:
                unexpected_client += 1
    positive_operations = sum(1 for kind, _, _ in results
                              if any(200 <= code < 300 for code in expected_by_kind.get(kind, {200, 204})))
    return {
        "status_counts": dict(sorted(codes.items())),
        "successful_expected_operations": successful,
        "positive_operations": positive_operations,
        "expected_operation_success_ratio": round(successful / positive_operations, 4) if positive_operations else None,
        "expected_rejections": dict(sorted(expected_rejections.items())),
        "unexpected_responses": unexpected,
        "unexpected_client_responses": unexpected_client,
        "infrastructure_failures": infrastructure,
        "conflicts": codes["409"], "rate_limit_responses": codes["429"],
        "unexpected_error_percent": round(100 * unexpected / len(results), 3) if results else None,
    }
