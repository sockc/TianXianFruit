#!/usr/bin/env python3
"""V0.2.1 isolation guard: production CloudAPI may never send ledger writes."""
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "Sources"
swift = {p.name: p.read_text(encoding="utf-8") for p in root.glob("*.swift")}
assert "StagingPurchaseAPI.swift" in swift
assert "StagingPurchaseModels.swift" in swift
assert "StagingPurchaseCoordinator.swift" in swift

# The only file allowed to name an actual server /sync/push business path.
# Other client sources must retain their original read-only contract.
for filename, body in swift.items():
    if filename != "StagingPurchaseAPI.swift":
        assert '"/api/v1/sync/push"' not in body, (
            f"Business write route found outside staging transport: {filename}")
    for forbidden in (
        '"/customer-analytics/push"',
        '"/api/v1/books/restore"',
        '"/api/v1/books/purge"',
        '"/api/v1/admin/"',
    ):
        assert forbidden not in body, f"Forbidden route in {filename}: {forbidden}"

regular = swift["CloudAPI.swift"]
assert '"/api/v1/sync/pull"' in regular
assert '"/api/v1/books"' in regular
assert '"/api/v1/auth/login"' in regular
assert '"/api/v1/devices/register"' in regular
assert '"/api/v1/sync/push"' not in regular

staging = swift["StagingPurchaseAPI.swift"]
assert '"/api/v1/ios-test/attestation"' in staging
assert '"POST", "/api/v1/sync/push"' in staging
assert 'try await attestation()' in staging
assert "StagingPurchasePolicy.requireProof" in staging
assert "StagingPurchasePolicy.validateURL" in staging
assert "URLSession(configuration: config, delegate: self" in staging

policy = swift["StagingPurchaseModels.swift"]
assert 'forbiddenHost = "sync.830888.xyz"' in policy
assert 'proof.isolatedFromProduction' in policy
assert 'proof.iosPurchaseWriteEnabled' in policy
assert 'proof.allowedBookIDs.contains(book.id)' in policy

coordinator = swift["StagingPurchaseCoordinator.swift"]
assert "try storage.saveStagingJob" in coordinator
assert "to: .sendingOrder" in coordinator
assert "to: .sendingItem" in coordinator
assert "api.verify(" in coordinator
assert "needsReview" in coordinator

assert "return false" in swift["TestWriteModels.swift"]  # never source-draft push
assert "TestDraftStore" in swift["TestWriteLabView.swift"]

print("V0.2.1 PASS: only staging transport may push, production remains read-only.")
