# iOS staging-server attestation contract

This is a **required server feature**, not an endpoint present in the current GitHub Android/iOS repo.

Only the separately deployed HTTPS staging server may serve this response from:
`GET /api/v1/ios-test/attestation`.

Do not copy these values into the production server. A `true` value is a claim about independently provisioned databases, storage, signing keys, and strict server-side write authorization—not a toggle to bypass an iOS guard.

Replace the placeholder instance ID and test book UUID with actual values. Require `OWNER` or `SUPERADMIN` for the test book, and restrict `/api/v1/sync/push` server-side to `purchase_order`/`purchase_item` UPSERT on allowlisted IDs. Reject other books, other business tables, and DELETE. Never reverse proxy to `sync.830888.xyz`.

The iOS client verifies this JSON before sending staging credentials and again before each push. It also checks test book ID/name, permissions, order/child readback and immutable local upload identity. No real test backend has been provisioned by this PR.
