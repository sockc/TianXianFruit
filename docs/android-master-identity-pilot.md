# Android master identity compatibility pilot

Baseline: main Android V1.4.7.134. No version bump or release until regression is complete.
Only newly added fruit/store/partner records in a ledger whose local display name begins iOS_TEST_ use explicit random IDs in [2^48, 2^49). Existing rows, system seed records, restores, production path, settlement calculations and sync protocol remain unchanged.
The server RC2 test-create range is [2^50, 2^51). This is collision reduction, not proof of global uniqueness. Existing Android clients must be upgraded before a formal rollout; SQLite AUTOINCREMENT still follows the largest imported ID on old clients. A locally named test ledger is not server authorization; the server separately enforces real test-book scope. No live server deployment or formal creation enablement.
Regression: Kotlin allocator tests, SQLite identity/legacy tests, existing Android CI (debug build, lint, unit tests). Full Android/iOS cross-device references (purchase, business, inventory and finance) must be verified on a disposable ledger before broadening scope.
