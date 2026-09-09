#!/usr/bin/env python3
"""
PHASE 2 COMPREHENSIVE API SMOKE TEST & AUDIT
Tests idempotency, sync, mutations, and data consistency.
"""

import requests
import json
import time
import uuid
from datetime import datetime

BASE_URL = "http://localhost:8000"
TEST_RESULTS = []

def test(name, condition, details=""):
    """Log test result"""
    status = "✓ PASS" if condition else "✗ FAIL"
    TEST_RESULTS.append((name, condition, details))
    print(f"{status} | {name}" + (f" ({details})" if details else ""))
    return condition

def section(title):
    """Print section header"""
    print(f"\n{'='*70}")
    print(f"  {title}")
    print(f"{'='*70}\n")

# ==========================================
# PHASE 2 TEST SUITE
# ==========================================

section("1. REGISTRATION & AUTHENTICATION")

# Test 1: Register new user
email = f"test_{uuid.uuid4().hex[:8]}@example.com"
password = "SecurePass123!"
display_name = "Test User"

reg_resp = requests.post(f"{BASE_URL}/api/auth/register", json={
    "email": email,
    "password": password,
    "display_name": display_name,
})
test("Registration", reg_resp.status_code == 200, f"Status {reg_resp.status_code}")
user_id = reg_resp.json().get("id") if reg_resp.status_code == 200 else None
cookies_reg = reg_resp.cookies

# Test 2: Login
login_resp = requests.post(f"{BASE_URL}/api/auth/login", json={
    "email": email,
    "password": password,
})
test("Login", login_resp.status_code == 200, f"Status {login_resp.status_code}")
cookies_login = login_resp.cookies

# Test 3: Get current user
me_resp = requests.get(f"{BASE_URL}/api/auth/me", cookies=cookies_login)
test("Get current user", me_resp.status_code == 200, f"Status {me_resp.status_code}")
test("User identity", me_resp.json().get("email") == email, "Email matches")

section("2. AUTHENTICATED SYNC")

# Test 4: Get initial library state
sync_resp = requests.get(f"{BASE_URL}/api/auth/sync", cookies=cookies_login)
test("Get library (legacy)", sync_resp.status_code == 200, f"Status {sync_resp.status_code}")
test("Library empty", sync_resp.json().get("favorites") == {}, "Favorites empty")

# Test 5: Delta sync with empty mutations (should get snapshot)
delta_resp = requests.post(f"{BASE_URL}/api/auth/library/sync", 
    json={"base_revision": 0, "mutations": []},
    cookies=cookies_login
)
test("Delta sync (initial)", delta_resp.status_code == 200, f"Status {delta_resp.status_code}")
body = delta_resp.json()
test("Snapshot included", body.get("snapshot") is not None, "snapshot present")
test("Revision 0", body.get("revision") == 0, f"revision={body.get('revision')}")

section("3. DELTA MUTATIONS - SINGLE MUTATIONS")

# Test 6: Favorite a track
mutation_id_1 = str(uuid.uuid4())
track_1 = {"id": "track_001", "title": "Song A", "artist": "Artist A"}

mut_resp = requests.post(f"{BASE_URL}/api/auth/library/sync",
    json={
        "base_revision": 0,
        "mutations": [
            {
                "id": mutation_id_1,
                "operation": "favorite",
                "payload": {"track": track_1, "loved": True}
            }
        ]
    },
    cookies=cookies_login
)
test("Favorite mutation applied", mut_resp.status_code == 200, f"Status {mut_resp.status_code}")
body = mut_resp.json()
test("Mutation acknowledged", mutation_id_1 in body.get("acknowledged_ids", []), "ID in acknowledged_ids")
test("Revision incremented", body.get("revision") == 1, f"revision={body.get('revision')}")

# Verify the favorite was persisted
sync_resp2 = requests.post(f"{BASE_URL}/api/auth/library/sync",
    json={"base_revision": 0, "mutations": []},
    cookies=cookies_login
)
body2 = sync_resp2.json()
favorites = body2.get("snapshot", {}).get("favorites", {})
test("Favorite persisted", "track_001" in favorites, f"Favorites: {list(favorites.keys())}")

section("4. IDEMPOTENCY - RETRY SAME MUTATION")

# Test 7: Retry the same mutation ID (should be idempotent)
mut_resp_retry = requests.post(f"{BASE_URL}/api/auth/library/sync",
    json={
        "base_revision": 1,
        "mutations": [
            {
                "id": mutation_id_1,  # Same ID
                "operation": "favorite",
                "payload": {"track": track_1, "loved": True}
            }
        ]
    },
    cookies=cookies_login
)
test("Retry mutation idempotent", mut_resp_retry.status_code == 200, f"Status {mut_resp_retry.status_code}")
body_retry = mut_resp_retry.json()
test("Still acknowledged", mutation_id_1 in body_retry.get("acknowledged_ids", []), "ID acknowledged")
test("Revision NOT incremented again", body_retry.get("revision") == 1, f"revision={body_retry.get('revision')}")

section("5. IDEMPOTENCY - DUPLICATE IN SAME BATCH")

# Test 8: Send same mutation twice in one batch
mutation_id_2 = str(uuid.uuid4())
track_2 = {"id": "track_002", "title": "Song B", "artist": "Artist B"}

mut_resp_dup = requests.post(f"{BASE_URL}/api/auth/library/sync",
    json={
        "base_revision": 1,
        "mutations": [
            {
                "id": mutation_id_2,
                "operation": "favorite",
                "payload": {"track": track_2, "loved": True}
            },
            {
                "id": mutation_id_2,  # Duplicate!
                "operation": "favorite",
                "payload": {"track": track_2, "loved": True}
            }
        ]
    },
    cookies=cookies_login
)
test("Duplicate in batch handled", mut_resp_dup.status_code == 200, f"Status {mut_resp_dup.status_code}")
body_dup = mut_resp_dup.json()
test("Only counted once", body_dup.get("acknowledged_ids", []).count(mutation_id_2) == 1, 
     f"Acknowledged IDs: {body_dup.get('acknowledged_ids')}")
test("Revision incremented once", body_dup.get("revision") == 2, f"revision={body_dup.get('revision')}")

# Verify only one instance of track_2
sync_resp3 = requests.post(f"{BASE_URL}/api/auth/library/sync",
    json={"base_revision": 0, "mutations": []},
    cookies=cookies_login
)
body3 = sync_resp3.json()
favorites3 = body3.get("snapshot", {}).get("favorites", {})
test("Track B appears once", "track_002" in favorites3, f"Favorites: {list(favorites3.keys())}")

section("6. PLAYLIST OPERATIONS - TRACK ADDITION IDEMPOTENCY")

# Test 9: Create playlist
mutation_id_pl = str(uuid.uuid4())
playlist_1 = {"id": "pl_001", "name": "My Playlist", "tracks": [], "created_at": datetime.utcnow().isoformat()}

mut_resp_pl = requests.post(f"{BASE_URL}/api/auth/library/sync",
    json={
        "base_revision": 2,
        "mutations": [
            {
                "id": mutation_id_pl,
                "operation": "playlist_upsert",
                "payload": {"playlist": playlist_1}
            }
        ]
    },
    cookies=cookies_login
)
test("Playlist created", mut_resp_pl.status_code == 200, f"Status {mut_resp_pl.status_code}")
test("Revision incremented", mut_resp_pl.json().get("revision") == 3, f"revision={mut_resp_pl.json().get('revision')}")

# Test 10: Add track to playlist
mutation_id_add = str(uuid.uuid4())
mut_resp_add = requests.post(f"{BASE_URL}/api/auth/library/sync",
    json={
        "base_revision": 3,
        "mutations": [
            {
                "id": mutation_id_add,
                "operation": "playlist_track_add",
                "payload": {
                    "playlist_id": "pl_001",
                    "track": track_1,
                    "updated_at": datetime.utcnow().isoformat()
                }
            }
        ]
    },
    cookies=cookies_login
)
test("Track added to playlist", mut_resp_add.status_code == 200, f"Status {mut_resp_add.status_code}")

# Test 11: Retry adding the same track (idempotency - should not duplicate)
mut_resp_add_retry = requests.post(f"{BASE_URL}/api/auth/library/sync",
    json={
        "base_revision": 4,
        "mutations": [
            {
                "id": mutation_id_add,  # Same ID
                "operation": "playlist_track_add",
                "payload": {
                    "playlist_id": "pl_001",
                    "track": track_1,
                    "updated_at": datetime.utcnow().isoformat()
                }
            }
        ]
    },
    cookies=cookies_login
)
test("Retry add is idempotent", mut_resp_add_retry.status_code == 200, f"Status {mut_resp_add_retry.status_code}")
body_add_retry = mut_resp_add_retry.json()
test("Revision NOT incremented", body_add_retry.get("revision") == 4, f"revision={body_add_retry.get('revision')}")

# Verify track appears only once in playlist
sync_resp4 = requests.post(f"{BASE_URL}/api/auth/library/sync",
    json={"base_revision": 0, "mutations": []},
    cookies=cookies_login
)
body4 = sync_resp4.json()
playlists = body4.get("snapshot", {}).get("playlists", {})
pl = playlists.get("pl_001", {})
tracks_in_pl = pl.get("tracks", [])
track_1_count = sum(1 for t in tracks_in_pl if t.get("id") == "track_001")
test("Track appears once in playlist", track_1_count == 1, f"Count: {track_1_count}, Tracks: {len(tracks_in_pl)}")

section("7. FAVORITES CONSISTENCY")

# Test 12: Verify favorites are consistent
sync_resp5 = requests.post(f"{BASE_URL}/api/auth/library/sync",
    json={"base_revision": 0, "mutations": []},
    cookies=cookies_login
)
body5 = sync_resp5.json()
favorites5 = body5.get("snapshot", {}).get("favorites", {})
test("Favorites count", len(favorites5) == 2, f"Count: {len(favorites5)}, IDs: {list(favorites5.keys())}")
test("Track 001 favorited", "track_001" in favorites5, "track_001 present")
test("Track 002 favorited", "track_002" in favorites5, "track_002 present")

section("8. REVISIONS & CHANGE LOG")

# Test 13: Fetch changes from revision point
changes_resp = requests.post(f"{BASE_URL}/api/auth/library/sync",
    json={"base_revision": 2, "mutations": []},
    cookies=cookies_login
)
body_changes = changes_resp.json()
changes = body_changes.get("changes", [])
test("Changes fetched", len(changes) > 0, f"Changes count: {len(changes)}")
test("Changes start after revision 2", all(c.get("revision", 0) > 2 for c in changes), 
     f"Revisions: {[c.get('revision') for c in changes]}")

section("9. ACCOUNT ISOLATION")

# Test 14: Create second user and verify isolation
email2 = f"test2_{uuid.uuid4().hex[:8]}@example.com"
reg_resp2 = requests.post(f"{BASE_URL}/api/auth/register", json={
    "email": email2,
    "password": password,
    "display_name": "Test User 2",
})
test("Second user registered", reg_resp2.status_code == 200, f"Status {reg_resp2.status_code}")
cookies_login2 = reg_resp2.cookies

# Verify user 2 sees empty library
sync_resp_u2 = requests.post(f"{BASE_URL}/api/auth/library/sync",
    json={"base_revision": 0, "mutations": []},
    cookies=cookies_login2
)
body_u2 = sync_resp_u2.json()
favorites_u2 = body_u2.get("snapshot", {}).get("favorites", {})
test("User 2 has empty favorites", len(favorites_u2) == 0, f"Count: {len(favorites_u2)}")

# Add different favorites to user 2
mutation_id_u2 = str(uuid.uuid4())
track_u2 = {"id": "track_u2_001", "title": "Different Song", "artist": "Different Artist"}

mut_resp_u2 = requests.post(f"{BASE_URL}/api/auth/library/sync",
    json={
        "base_revision": 0,
        "mutations": [
            {
                "id": mutation_id_u2,
                "operation": "favorite",
                "payload": {"track": track_u2, "loved": True}
            }
        ]
    },
    cookies=cookies_login2
)
test("User 2 mutation applied", mut_resp_u2.status_code == 200, f"Status {mut_resp_u2.status_code}")

# Verify user 1 still has original favorites
sync_resp_u1_check = requests.post(f"{BASE_URL}/api/auth/library/sync",
    json={"base_revision": 0, "mutations": []},
    cookies=cookies_login
)
body_u1_check = sync_resp_u1_check.json()
favorites_u1_check = body_u1_check.get("snapshot", {}).get("favorites", {})
test("User 1 unaffected", len(favorites_u1_check) == 2, f"Count: {len(favorites_u1_check)}")
test("User 1 doesn't see User 2's track", "track_u2_001" not in favorites_u1_check, "Isolation maintained")

# Verify user 2 only has their track
sync_resp_u2_check = requests.post(f"{BASE_URL}/api/auth/library/sync",
    json={"base_revision": 0, "mutations": []},
    cookies=cookies_login2
)
body_u2_check = sync_resp_u2_check.json()
favorites_u2_check = body_u2_check.get("snapshot", {}).get("favorites", {})
test("User 2 has only their track", len(favorites_u2_check) == 1, f"Count: {len(favorites_u2_check)}")
test("User 2 doesn't see User 1's tracks", "track_001" not in favorites_u2_check, "Isolation maintained")

section("10. SUMMARY")

passed = sum(1 for _, result, _ in TEST_RESULTS if result)
total = len(TEST_RESULTS)
print(f"\nTests Passed: {passed}/{total}")

if passed == total:
    print("\n✓ ALL PHASE 2 SMOKE TESTS PASSED")
else:
    print(f"\n✗ {total - passed} TESTS FAILED")
    print("\nFailed Tests:")
    for name, result, details in TEST_RESULTS:
        if not result:
            print(f"  - {name} ({details})")

print("\n" + "="*70)
