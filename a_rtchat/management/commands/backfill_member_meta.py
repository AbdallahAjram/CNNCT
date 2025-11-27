# a_rtchat/management/commands/backfill_member_meta.py

from django.core.management.base import BaseCommand
from firebase_admin import firestore

# If you have a wrapper client, prefer it:
# from a_core.firebase_admin_client import db as _db
# db = _db
db = firestore.client()

# Which keys we want to ensure exist under memberMeta.<uid>
REQUIRED_BOOL_KEYS = ("iBlockedPeer", "blockedByOther")
SET_LAST_OPENED_AT = True  # set to False if you don't want to backfill lastOpenedAt

def _build_missing_paths(data: dict) -> dict:
    """
    Given a chat doc dict, return a dict of FieldPath updates for any missing
    memberMeta fields. Never overwrites existing values.
    """
    updates = {}

    # Only private chats and exactly two members
    if (data or {}).get("type") != "private":
        return updates

    members = (data or {}).get("members") or []
    if len(members) != 2:
        return updates

    member_meta = (data or {}).get("memberMeta") or {}

    for uid in members:
        sub = member_meta.get(uid) or {}

        # Ensure boolean flags exist (don’t overwrite)
        for key in REQUIRED_BOOL_KEYS:
            if key not in sub:
                updates[f"memberMeta.{uid}.{key}"] = False

        # Optionally ensure lastOpenedAt exists
        if SET_LAST_OPENED_AT and ("lastOpenedAt" not in sub):
            updates[f"memberMeta.{uid}.lastOpenedAt"] = firestore.SERVER_TIMESTAMP

    return updates


class Command(BaseCommand):
    help = (
        "Backfill private chats so memberMeta.<uid> exists with iBlockedPeer/blockedByOther "
        "and optionally lastOpenedAt. Idempotent and does not overwrite existing values."
    )

    def add_arguments(self, parser):
        parser.add_argument(
            "--collection",
            default="chats",
            help="Firestore collection name for chat docs (default: chats)",
        )
        parser.add_argument(
            "--batch-size",
            type=int,
            default=400,
            help="Number of updates per commit (default: 400; Firestore limit is 500).",
        )
        parser.add_argument(
            "--dry-run",
            action="store_true",
            help="Print what would change, but do not write.",
        )
        parser.add_argument(
            "--limit",
            type=int,
            default=0,
            help="Limit number of chat docs processed (0 = no limit).",
        )

    def handle(self, *args, **opts):
        col = opts["collection"]
        batch_size = opts["batch_size"]
        dry = opts["dry_run"]
        limit = opts["limit"]

        chats_ref = db.collection(col)

        # Only private chats
        q = chats_ref.where("type", "==", "private")
        if limit > 0:
            q = q.limit(limit)

        docs = list(q.stream())
        self.stdout.write(self.style.NOTICE(f"Found {len(docs)} private chat(s)."))

        batch = db.batch()
        pending = 0
        updated_count = 0

        for doc in docs:
            data = doc.to_dict() or {}
            updates = _build_missing_paths(data)
            if not updates:
                continue

            updated_count += 1
            self.stdout.write(f"- Will update {doc.id}: {', '.join(updates.keys())}")

            if dry:
                continue

            batch.update(doc.reference, updates)
            pending += 1

            if pending >= batch_size:
                batch.commit()
                self.stdout.write(self.style.SUCCESS(f"Committed {pending} updates"))
                batch = db.batch()
                pending = 0

        if not dry and pending:
            batch.commit()
            self.stdout.write(self.style.SUCCESS(f"Committed {pending} updates"))

        msg = f"Done. {'(dry-run) ' if dry else ''}{updated_count} chat(s) {'would be' if dry else 'were'} updated."
        self.stdout.write(self.style.SUCCESS(msg))
