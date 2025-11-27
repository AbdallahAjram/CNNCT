import argparse
import os
from typing import List, Tuple

from firebase_admin import credentials, initialize_app, firestore as admin_fs
from google.cloud.firestore_v1 import DELETE_FIELD

SA_PATH = r"C:\Users\aliha\Downloads\django-starter-main-final\django-starter-main\firebase-key.json"

def init_admin():
    # Always initialize explicitly with the service account we expect
    try:
        initialize_app(credentials.Certificate(SA_PATH))
    except ValueError:
        # already initialized
        pass
    db = admin_fs.client()
    info = db._client_info  # internal, but helpful to confirm
    # Best-effort project/client info
    print("== Firestore target ==")
    try:
        print("Project ID:", db.project)
    except Exception:
        pass
    try:
        print("Client email:", info.get("client_email"))
    except Exception:
        pass
    print("======================")
    return db

def find_docs_with_displayname(db) -> List[Tuple[str, dict]]:
    hits = []
    for snap in db.collection("users").stream():
        d = snap.to_dict() or {}
        if "displayname" in d:
            hits.append((snap.id, d))
    return hits

def migrate(db, apply_changes: bool):
    hits = find_docs_with_displayname(db)
    print(f"Docs with 'displayname': {len(hits)}")
    for _id, d in hits[:10]:
        print(f"  - {_id}: displayname={repr(d.get('displayname'))}  displayName={repr(d.get('displayName'))}")
    if not hits:
        return

    if not apply_changes:
        print("\n(DRY-RUN) Would update these docs, but not applying. Run with --apply to commit.")
        return

    batch = db.batch()
    ops = migrated = removed_only = 0

    for idx, (doc_id, data) in enumerate(hits, start=1):
        src = data.get("displayname")
        dst = data.get("displayName")
        should_copy = (not dst or (isinstance(dst, str) and not dst.strip())) and isinstance(src, str) and src.strip()

        update = {"displayname": DELETE_FIELD}
        if should_copy:
            update["displayName"] = src.strip()
            migrated += 1
        else:
            removed_only += 1

        ref = db.collection("users").document(doc_id)
        batch.update(ref, update)
        ops += 1

        if ops >= 400:
            batch.commit()
            batch = db.batch()
            ops = 0

    if ops:
        batch.commit()

    print(f"\nApplied. migrated={migrated}, removed_only={removed_only}")

    # Verify
    remaining = find_docs_with_displayname(db)
    print(f"Remaining docs still having 'displayname': {len(remaining)}")
    if remaining:
        for _id, d in remaining[:20]:
            print(f"  - {_id}: displayname={repr(d.get('displayname'))}, displayName={repr(d.get('displayName'))}")

def main():
    parser = argparse.ArgumentParser(description="Fix users.displayname -> displayName and delete displayname")
    parser.add_argument("--apply", action="store_true", help="Apply changes (otherwise dry-run)")
    args = parser.parse_args()

    db = init_admin()
    migrate(db, apply_changes=args.apply)

if __name__ == "__main__":
    main()
