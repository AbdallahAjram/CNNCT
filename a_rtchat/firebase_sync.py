# a_rtchat/firebase_sync.py
from __future__ import annotations
from typing import Optional, List, Iterable, Dict

from django.utils import timezone
from django.db import transaction
from django.contrib.auth import get_user_model

from a_users.firebase_helpers import get_db, get_or_create_user_by_uid
from a_users.models import Profile

from .models import ChatGroup, GroupMessages

from google.cloud import firestore as _fs


# -------------------------- Utilities --------------------------

def make_pair_key(uid_a: str, uid_b: str) -> str:
    return "#".join(sorted([uid_a, uid_b]))

def guess_private_chat_id(uid_a: str, uid_b: str) -> str:
    return f"priv_{make_pair_key(uid_a, uid_b)}"

def _uids_for_group_members(group: ChatGroup) -> List[str]:
    """Collect firebase_uid for all members that have one."""
    uids = []
    for u in group.members.all():
        uid = getattr(getattr(u, "profile", None), "firebase_uid", None)
        if uid:
            uids.append(uid)
    return uids

def _safe_make_aware(dt):
    """Make Firestore timestamps timezone-aware if needed."""
    if not dt:
        return None
    try:
        # Firestore returns tz-aware UTC datetimes with Admin SDK,
        # but guard just in case.
        if timezone.is_naive(dt):
            return timezone.make_aware(dt, timezone.utc)
    except Exception:
        pass
    return dt


# -------------------------- USER PROFILE NORMALIZATION --------------------------

def normalize_user_payload_to_displayName(payload: dict) -> dict:
    """
    Normalize user profile payload to use displayName (camelCase) only.
    
    Rules:
    - If displayName present and non-empty → keep it (trimmed).
    - Else if displayname present → set displayName = displayname (trim); remove displayname.
    - Ensure we never persist displayname.
    
    Args:
        payload: Dictionary with potential displayName/displayname fields
        
    Returns:
        Normalized dictionary with only displayName (never displayname)
    """
    data = dict(payload or {})
    
    # Pick source value: prefer displayName, fallback to displayname
    dn = (data.get("displayName") or data.get("displayname") or "").strip()
    
    if dn:
        data["displayName"] = dn
    
    # Never persist 'displayname' - remove it if present
    if "displayname" in data:
        data.pop("displayname", None)
    
    return data


def upsert_user_profile(db, uid: str, payload: dict):
    """
    Upsert a user profile to Firestore with normalized displayName.
    
    Args:
        db: Firestore client
        uid: User's Firebase UID
        payload: Profile data dictionary (will be normalized)
    """
    data = normalize_user_payload_to_displayName(payload)
    db.collection("users").document(uid).set(data, merge=True)


def ensure_displayName_self_heal(db, user_ref):
    """
    Self-heal a user document: if displayName is missing but displayname exists,
    copy displayname to displayName and delete displayname.
    
    Args:
        db: Firestore client (unused but kept for API consistency)
        user_ref: Firestore DocumentReference to the user document
    """
    snap = user_ref.get()
    if not snap.exists:
        return
    
    data = snap.to_dict() or {}
    if "displayName" not in data and data.get("displayname"):
        dn = str(data["displayname"]).strip()
        if dn:
            user_ref.set({
                "displayName": dn,
                "displayname": _fs.DELETE_FIELD
            }, merge=True)


def get_profile_dict(db, uid: str) -> dict:
    """
    Read a user profile from Firestore with self-healing.
    
    If displayName is missing but displayname exists, this will:
    1. Return the fixed value (displayName)
    2. Asynchronously self-heal by writing displayName and deleting displayname
    
    Args:
        db: Firestore client
        uid: User's Firebase UID
        
    Returns:
        Dictionary with normalized profile data (always uses displayName, never displayname)
    """
    ref = db.collection("users").document(uid)
    snap = ref.get()
    
    if not snap.exists:
        return {}
    
    data = snap.to_dict() or {}
    dn = data.get("displayName")
    
    # Self-heal if needed
    if not dn and data.get("displayname"):
        dn = str(data["displayname"]).strip()
        if dn:
            # Return fixed value and self-heal asynchronously
            ref.set({
                "displayName": dn,
                "displayname": _fs.DELETE_FIELD
            }, merge=True)
    
    # Ensure we return normalized data
    result = dict(data)
    if dn:
        result["displayName"] = dn.strip()
    result.pop("displayname", None)  # Remove displayname from returned dict
    
    return result


def update_user_last_online_in_firestore(uid: str):
    """
    Best-effort: bump users/{uid}.lastOnlineAt so mobile clients
    can see web presence activity.
    """
    if not uid:
        return
    try:
        db = get_db()
        ref = db.collection("users").document(uid)
        ref.set(
            {
                "lastOnlineAt": _fs.SERVER_TIMESTAMP,
                "updatedAt": _fs.SERVER_TIMESTAMP,
            },
            merge=True,
        )
    except Exception as e:
        print("⚠️ update_user_last_online_in_firestore failed:", e, flush=True)


# -------------------------- GROUP CHATS (Firestore write path) --------------------------

def ensure_firestore_group_chat_for_group(group: ChatGroup) -> Optional[str]:
    """
    Ensure a Firestore 'group' chat doc exists and mirrors the local ChatGroup.
    Writes/updates (merge): type, members, groupName, adminIds, groupDescription, updatedAt.
    Returns the chat_id. Does NOT overwrite memberMeta (preserved via merge).
    """
    if group.is_private:
        return None

    db = get_db()
    chats = db.collection("chats")

    member_uids = _uids_for_group_members(group)
    if len(member_uids) < 2:
        # Don’t create half-empty rooms in Firestore
        return None

    # adminIds (from ChatGroup.admin -> profile.firebase_uid)
    admin_uid = getattr(getattr(getattr(group, "admin", None), "profile", None), "firebase_uid", None)
    admin_ids = [admin_uid] if admin_uid else []

    # Optional description if your model has it; else write empty string
    group_description = getattr(group, "description", "") or ""

    # Optional group photo url (from your model's avatar property/field)
    group_photo_url = None
    try:
        group_photo_url = getattr(group, "avatar", None) or None
    except Exception:
        group_photo_url = None

    base_payload = {
        "type": "group",
        "members": member_uids,
        "groupName": group.groupchat_name or "",
        "adminIds": admin_ids,
        "groupDescription": group_description,
        "updatedAt": _fs.SERVER_TIMESTAMP,
    }
    if group_photo_url:
        base_payload["groupPhotoUrl"] = group_photo_url

    def _bind(chat_id: str):
        group.firebase_chat_id = chat_id
        group.save(update_fields=["firebase_chat_id"])
        return chat_id

    # Helper to upsert minimal memberMeta stubs without clobbering existing fields
    def _ensure_member_meta(chat_ref):
        try:
            meta_update = {"memberMeta": {uid: {} for uid in member_uids}}
            chat_ref.set(meta_update, merge=True)
        except Exception as e:
            print("⚠️ memberMeta upsert failed:", e)

    # Update an existing binding
    if group.firebase_chat_id:
        try:
            ref = chats.document(group.firebase_chat_id)
            # Merge so we preserve memberMeta and any other client-maintained fields.
            ref.set(base_payload, merge=True)
            _ensure_member_meta(ref)
            return group.firebase_chat_id
        except Exception as e:
            print("⚠️ group binding validation failed:", e)

    # Create a new Firestore group chat (auto id)
    try:
        payload = {
            **base_payload,
            "createdAt": _fs.SERVER_TIMESTAMP,
        }
        doc_ref = chats.document()
        doc_ref.set(payload)  # merge not needed on create
        _ensure_member_meta(doc_ref)
        return _bind(doc_ref.id)
    except Exception as e:
        print("❌ ensure_firestore_group_chat_for_group failed:", e)
        return None


def push_group_text_message_to_firestore(group: ChatGroup, sender_uid: str, text: str) -> Optional[str]:
    """
    Push a text message to Firestore for a group chat and return its message id.
    Also updates the chat doc's lastMessage* fields.
    """
    if not text:
        return None

    chat_id = ensure_firestore_group_chat_for_group(group)
    if not chat_id:
        print("❌ push_group_text_message_to_firestore: no chat_id for group", group.id, flush=True)
        return None

    db = get_db()
    chat_ref = db.collection("chats").document(chat_id)
    msgs = chat_ref.collection("messages")
    payload = {
        "type": "text",
        "text": text,
        "senderId": sender_uid,
        "contentType": None,
        "mediaUrl": None,
        "fileName": None,
        "sizeBytes": None,
        "location": None,
        "hiddenFor": [],
        "deleted": False,
        "deletedAt": None,
        "deletedBy": None,
        "editedAt": None,
        "createdAt": _fs.SERVER_TIMESTAMP,        # server timestamp for ordering
        "createdAtClient": timezone.now(),        # client-side for UX
    }

    # Create the message
    doc_ref = msgs.document()  # client-generated id
    doc_ref.set(payload)
    mid = doc_ref.id

    # Update chat’s lastMessage* fields
    chat_ref.set({
        "lastMessageId": mid,
        "lastMessageText": text,
        "lastMessageSenderId": sender_uid,
        "lastMessageStatus": "sent",              # your read receipts can flip these
        "lastMessageIsRead": False,
        "lastMessageTimestamp": _fs.SERVER_TIMESTAMP,
        "updatedAt": _fs.SERVER_TIMESTAMP,
    }, merge=True)

    return mid


def update_firestore_group_doc(
    group: ChatGroup,
    *,
    new_name: Optional[str] = None,
    members_uids: Optional[list[str]] = None,
    admin_uids: Optional[list[str]] = None,
    photo_url: Optional[str] = None,
):
    """
    Merge-update the Firestore group chat document with provided fields.
    Ensures binding exists first. Ignores private rooms.
    """
    if group.is_private:
        return

    db = get_db()
    chat_id = ensure_firestore_group_chat_for_group(group)
    if not chat_id:
        return

    doc_ref = db.collection("chats").document(chat_id)
    data: dict = {"updatedAt": _fs.SERVER_TIMESTAMP}

    if new_name is not None:
        data["groupName"] = new_name
    if photo_url is not None:
        data["groupPhotoUrl"] = photo_url
    if admin_uids is not None:
        data["adminIds"] = admin_uids
    if members_uids is not None:
        data["members"] = members_uids

    if not data:
        return

    try:
        doc_ref.set(data, merge=True)
    except Exception as e:
        print("⚠️ update_firestore_group_doc failed:", e, flush=True)


def remove_user_from_group_chat_in_firestore(group: ChatGroup, user) -> bool:
    """
    Remove a single user's UID from a Firestore group chat's members[].
    Also cleans per-member meta. Returns True on a best-effort success.
    """
    try:
        uid = getattr(getattr(user, "profile", None), "firebase_uid", None)
        if not uid:
            return False

        db = get_db()
        # Prefer existing binding; if missing, try to ensure one *before* local removal.
        chat_id = getattr(group, "firebase_chat_id", None) or ensure_firestore_group_chat_for_group(group)
        if not chat_id:
            # No chat to update — nothing to remove from.
            return False

        chat_ref = db.collection("chats").document(chat_id)
        chat_ref.set({
            "members": _fs.ArrayRemove([uid]),
            f"memberMeta.{uid}": _fs.DELETE_FIELD,
            "updatedAt": _fs.SERVER_TIMESTAMP,
        }, merge=True)

        # Optional: if the group is now <2 members, mark archived to keep clients tidy.
        try:
            snap = chat_ref.get()
            data = snap.to_dict() or {}
            if len(data.get("members") or []) < 2:
                chat_ref.set({"archived": True, "updatedAt": _fs.SERVER_TIMESTAMP}, merge=True)
        except Exception:
            pass
        return True
    except Exception as e:
        print("⚠️ remove_user_from_group_chat_in_firestore failed:", e, flush=True)
        return False


def add_users_to_group_chat_in_firestore(group: ChatGroup, users: Iterable) -> bool:
    """
    Add one or more Django users to a Firestore group chat's members[] via ArrayUnion
    and ensure memberMeta stubs exist for them.

    This is a best-effort helper and will NO-OP if:
    - group is private, or
    - no users have profile.firebase_uid, or
    - no Firestore chat binding can be ensured.
    """
    if group.is_private:
        return False

    uids = []
    for u in users:
        uid = getattr(getattr(u, "profile", None), "firebase_uid", None)
        if uid:
            uids.append(uid)

    if not uids:
        return False

    db = get_db()
    chat_id = getattr(group, "firebase_chat_id", None) or ensure_firestore_group_chat_for_group(group)
    if not chat_id:
        return False

    chat_ref = db.collection("chats").document(chat_id)

    try:
        chat_ref.set(
            {
                "members": _fs.ArrayUnion(uids),
                "updatedAt": _fs.SERVER_TIMESTAMP,
            },
            merge=True,
        )

        meta_update = {"memberMeta": {uid: {} for uid in uids}}
        chat_ref.set(meta_update, merge=True)
        return True
    except Exception as e:
        print("⚠️ add_users_to_group_chat_in_firestore failed:", e, flush=True)
        return False


# -------------------------- PRIVATE CHATS (existing) --------------------------

def ensure_local_group_for_firestore_chat(chat_id: str, chat_doc: dict) -> ChatGroup:
    """
    Create a local ChatGroup mirror for a Firestore chat (private OR group).
    """
    group = ChatGroup.objects.filter(firebase_chat_id=chat_id).first()
    if group:
        return group

    members = chat_doc.get("members") or []
    is_private = (chat_doc.get("type") == "private" and len(members) == 2)

    local_users = []
    for uid in members:
        user, _ = get_or_create_user_by_uid(uid)
        local_users.append(user)

    with transaction.atomic():
        group = ChatGroup.objects.create(
            firebase_chat_id=chat_id,
            is_private=is_private,
            groupchat_name=(None if is_private else chat_doc.get("groupName") or ""),
        )
        for u in local_users:
            group.members.add(u)
    return group


def ensure_firestore_private_chat_for_group(group: ChatGroup) -> Optional[str]:
    """
    Always validate the current binding. If the bound doc is missing or its pairKey/members
    don't match the two users, re-bind by pairKey; if not found, create 'priv_{pairKey}'.
    """
    if not group.is_private:
        return group.firebase_chat_id

    members = list(group.members.all())
    if len(members) != 2:
        return None

    # Pull UIDs from profiles
    uids = []
    for u in members:
        uid = getattr(u.profile, "firebase_uid", None)
        if not uid:
            print(f"❌ ensure_firestore_private_chat_for_group: user {u.id} ({u.username}) has no profile.firebase_uid; cannot bind.")
            return None
        uids.append(uid)

    uid_a, uid_b = uids
    pair_key = make_pair_key(uid_a, uid_b)

    db = get_db()
    chats = db.collection("chats")

    def _bind_to(chat_id: str):
        print(f"✅ Binding ChatGroup(id={group.id}, name={group.group_name}) -> Firestore chat '{chat_id}'")
        group.firebase_chat_id = chat_id
        group.save(update_fields=["firebase_chat_id"])
        return chat_id

    # A) Validate existing binding
    if group.firebase_chat_id:
        try:
            snap = chats.document(group.firebase_chat_id).get()
            if snap.exists:
                data = snap.to_dict() or {}
                ok = (
                    data.get("type") == "private"
                    and set((data.get("members") or [])) == set([uid_a, uid_b])
                    and data.get("pairKey") == pair_key
                )
                if ok:
                    return group.firebase_chat_id
            # Else fall through to re-bind by pairKey
        except Exception as e:
            print("⚠️ validate existing chat id failed:", e)

    # B) Rebind by pairKey (covers legacy ids too)
    try:
        q = chats.where("pairKey", "==", pair_key).limit(1)
        docs = list(q.stream())
        if docs:
            return _bind_to(docs[0].id)
    except Exception as e:
        print("⚠️ query by pairKey failed:", e)

    # C) Create canonical priv_{pairKey}
    chat_id = f"priv_{pair_key}"
    payload = {
        "type": "private",
        "members": [uid_a, uid_b],
        "pairKey": pair_key,
        "createdAt": timezone.now(),   # ok to use Django time here
        "updatedAt": timezone.now(),
    }
    try:
        chats.document(chat_id).set(payload)
    except Exception as e:
        print("⚠️ create priv_* failed:", e)
        return None

    return _bind_to(chat_id)


def push_text_message_to_firestore(group: ChatGroup, sender_uid: str, text: str) -> Optional[str]:
    """
    Push a text message to Firestore for a private chat and return its message id.
    Also updates the chat doc's lastMessage* fields. Assumes both members have profile.firebase_uid.
    """
    if not text:
        return None

    chat_id = ensure_firestore_private_chat_for_group(group)
    if not chat_id:
        print("❌ push_text_message_to_firestore: no chat_id for group", group.id, flush=True)
        return None

    db = get_db()
    chat_ref = db.collection("chats").document(chat_id)
    msgs = chat_ref.collection("messages")
    payload = {
        "type": "text",
        "text": text,
        "senderId": sender_uid,
        "contentType": None,
        "mediaUrl": None,
        "fileName": None,
        "sizeBytes": None,
        "location": None,
        "hiddenFor": [],
        "deleted": False,
        "deletedAt": None,
        "deletedBy": None,
        "editedAt": None,
        # Dual stamps: server + client; server one is key for ordering
        "createdAt": _fs.SERVER_TIMESTAMP,
        "createdAtClient": timezone.now(),
    }

    # Create the message
    doc_ref = msgs.document()  # client-generated id
    doc_ref.set(payload)
    mid = doc_ref.id

    # Update chat’s lastMessage* fields
    chat_ref.set({
        "lastMessageId": mid,
        "lastMessageText": text,
        "lastMessageSenderId": sender_uid,
        "lastMessageStatus": "sent",
        "lastMessageIsRead": False,
        "lastMessageTimestamp": _fs.SERVER_TIMESTAMP,
        "updatedAt": _fs.SERVER_TIMESTAMP,
    }, merge=True)

    return mid


# -------------------------- IMPORTS (read path) --------------------------

def import_messages_for_group_from_firestore(group: ChatGroup, limit: int = 300):
    """
    Private chat import:
    - Validates chat binding.
    - Tries order_by(createdAt). On failure, falls back to scan + client-side sort.
    - Makes timestamps aware.
    """
    chat_id = ensure_firestore_private_chat_for_group(group)
    if not chat_id:
        print(f"❌ import_messages: ChatGroup(id={group.id}) not bound (missing firebase_uid on a member?) — skipping import.")
        return

    db = get_db()
    messages_ref = db.collection("chats").document(chat_id).collection("messages")

    # Try server-side order by createdAt desc
    snaps: list = []
    try:
        snaps = list(
            messages_ref
            .order_by("createdAt", direction=_fs.Query.DESCENDING)
            .limit(limit)
            .stream()
        )
        snaps.reverse()  # oldest → newest
    except Exception as e:
        # Fallback: scan all, sort client-side; then tail 'limit'
        print("⚠️ order_by(createdAt) failed, falling back:", e)
        try:
            all_snaps = list(messages_ref.stream())
        except Exception as ee:
            print("❌ messages stream failed:", ee)
            return

        def ts_of(s):
            d = s.to_dict() or {}
            ts = d.get("createdAt")
            if ts:
                return _safe_make_aware(ts)
            try:
                return _safe_make_aware(s.create_time)
            except Exception:
                return timezone.now()

        all_snaps.sort(key=ts_of)
        snaps = all_snaps[-limit:]  # keep last N (newest)

    for snap in snaps:
        data = snap.to_dict() or {}
        mid = snap.id
        if GroupMessages.objects.filter(firebase_message_id=mid).exists():
            continue

        sender_uid = data.get("senderId")
        sender_user = None
        if sender_uid:
            sender_user, _ = get_or_create_user_by_uid(sender_uid)
        if not sender_user:
            sender_user = group.members.first()

        deleted = bool(data.get("deleted") or False)
        body = "" if deleted else (data.get("text") or "")

        created_at = _safe_make_aware(data.get("createdAt")) or _safe_make_aware(
            getattr(snap, "create_time", None)
        ) or timezone.now()

        GroupMessages.objects.create(
            group=group,
            author=sender_user,
            body=body,
            is_deleted=deleted,
            firebase_message_id=mid,
            sender_uid=sender_uid,
            created=created_at,
        )


def import_group_messages_from_firestore(group: ChatGroup, limit: int = 300):
    """
    Group chat import:
    Requires group.firebase_chat_id and group.is_private == False.
    """
    if group.is_private or not group.firebase_chat_id:
        return

    db = get_db()
    chat_ref = db.collection("chats").document(group.firebase_chat_id)
    msgs_ref = chat_ref.collection("messages")

    snaps: list = []
    try:
        snaps = list(
            msgs_ref
            .order_by("createdAt", direction=_fs.Query.DESCENDING)
            .limit(limit)
            .stream()
        )
        snaps.reverse()
    except Exception as e:
        # Fallback: scan and client-sort
        try:
            all_snaps = list(msgs_ref.stream())
        except Exception as ee:
            print("❌ group messages stream failed:", ee)
            return

        def ts_of(s):
            d = s.to_dict() or {}
            ts = d.get("createdAt")
            if ts:
                return _safe_make_aware(ts)
            try:
                return _safe_make_aware(s.create_time)
            except Exception:
                return timezone.now()

        all_snaps.sort(key=ts_of)
        snaps = all_snaps[-limit:]

    for snap in snaps:
        data = snap.to_dict() or {}
        mid = snap.id
        if GroupMessages.objects.filter(firebase_message_id=mid).exists():
            continue

        sender_uid = data.get("senderId")
        sender_user = None
        if sender_uid:
            sender_user, _ = get_or_create_user_by_uid(sender_uid)
        if not sender_user:
            sender_user = group.members.first()

        deleted = bool(data.get("deleted") or False)
        body = "" if deleted else (data.get("text") or "")
        created_at = _safe_make_aware(data.get("createdAt")) or _safe_make_aware(
            getattr(snap, "create_time", None)
        ) or timezone.now()

        GroupMessages.objects.create(
            group=group,
            author=sender_user,
            body=body,
            is_deleted=deleted,
            firebase_message_id=mid,
            sender_uid=sender_uid,
            created=created_at,
        )


# -------------------------- GROUP CHATS (read-only sync of rooms) --------------------------

def list_user_group_chats_from_firestore(user):
    """Return list of (chat_id, chat_doc) where type==group and members contains user's firebase_uid."""
    uid = getattr(getattr(user, 'profile', None), 'firebase_uid', None)
    if not uid:
        return []
    db = get_db()
    try:
        q = db.collection("chats").where("type", "==", "group").where("members", "array_contains", uid)
        docs = list(q.stream())
    except Exception:
        return []
    out = []
    for d in docs:
        try:
            out.append((d.id, d.to_dict() or {}))
        except Exception:
            continue
    return out


def ensure_local_group_for_firestore_group(chat_id: str, chat_doc: dict) -> ChatGroup:
    """Ensure a local non-private ChatGroup mirrors the Firestore group chat id and members."""
    grp = ChatGroup.objects.filter(firebase_chat_id=chat_id).first()
    members = chat_doc.get("members") or []
    group_name = chat_doc.get("groupName") or ""

    local_users = []
    for m_uid in members:
        try:
            u, _ = get_or_create_user_by_uid(m_uid)
            local_users.append(u)
        except Exception:
            continue

    if grp:
        # ensure it's marked as non-private and name present; add any missing members
        updates = []
        if grp.is_private:
            grp.is_private = False
            updates.append("is_private")
        if group_name and grp.groupchat_name != group_name:
            grp.groupchat_name = group_name
            updates.append("groupchat_name")
        if updates:
            grp.save(update_fields=updates)
        for u in local_users:
            if u not in grp.members.all():
                grp.members.add(u)
        return grp

    with transaction.atomic():
        grp = ChatGroup.objects.create(
            firebase_chat_id=chat_id,
            is_private=False,
            groupchat_name=group_name,
        )
        for u in local_users:
            grp.members.add(u)
    return grp


def sync_group_chats_for_user(user):
    """List Firestore group chats for user and ensure local mirrors exist; return list of local groups."""
    out = []
    for cid, data in list_user_group_chats_from_firestore(user):
        try:
            grp = ensure_local_group_for_firestore_group(cid, data)
            out.append(grp)
        except Exception:
            continue
    return out


# --- BLOCK SYNC (PRIVATE CHATS) ---------------------------------------------


def _canonical_pair_key(uid_a: str, uid_b: str) -> str:
    # Keep same canonical ordering everywhere
    return make_pair_key(uid_a, uid_b)


def _private_chat_ref_for_pair(uid_a: str, uid_b: str): 
    """
    Return (doc_ref, existed_bool) for the private chat in 'chats' with pairKey.
    If missing, create canonical 'priv_{pairKey}' using your existing schema.
    """
    db = get_db()
    pair_key = _canonical_pair_key(uid_a, uid_b)
    chats = db.collection("chats")

    # Try to find by pairKey
    try:
        q = chats.where("pairKey", "==", pair_key).where("type", "==", "private").limit(1)
        docs = list(q.stream())
        if docs:
            return docs[0].reference, True
    except Exception as e:
        print("⚠️ private chat query by pairKey failed:", e, flush=True)

    # Create canonical priv_{pairKey} if not found
    doc_id = f"priv_{pair_key}"
    ref = chats.document(doc_id)
    payload = {
        "type": "private",
        "members": [uid_a, uid_b],
        "pairKey": pair_key,
        # Seed the canonical flags mobile reads
        "memberMeta": {
            uid_a: {"iBlockedPeer": False, "blockedByOther": False},
            uid_b: {"iBlockedPeer": False, "blockedByOther": False},
        },
        "createdAt": _fs.SERVER_TIMESTAMP,
        "updatedAt": _fs.SERVER_TIMESTAMP,
    }
    try:
        ref.set(payload, merge=True)
    except Exception as e:
        print("❌ creating canonical private chat failed:", e, flush=True)
    return ref, False


def set_private_block_flags_in_firestore(blocker_uid: str, blocked_uid: str):
    """
            BLOCK: Use the mobile-canonical flags:
            - memberMeta[blocker_uid].iBlockedPeer = True
            - memberMeta[blocked_uid].blockedByOther = True
    """
    if not blocker_uid or not blocked_uid or blocker_uid == blocked_uid:
        return
    try:
        ref, _ = _private_chat_ref_for_pair(blocker_uid, blocked_uid)
        print(f"🔒 set_block Firestore: blocker={blocker_uid} blocked={blocked_uid}", flush=True)

        ref.update({
            f"memberMeta.{blocker_uid}.iBlockedPeer": True,
            f"memberMeta.{blocked_uid}.blockedByOther": True,
            "updatedAt": _fs.SERVER_TIMESTAMP,
        })
    except Exception as e:
        print("❌ set_private_block_flags_in_firestore failed:", e, flush=True)



def update_private_chat_read_state(reader_uid: str, peer_uid: str, last_read_message_id: Optional[str] = None):
    """
    Update Firestore read state for reader_uid in the private chat they share with peer_uid.
    - memberMeta[reader_uid].lastOpenedAt
    - memberMeta[reader_uid].lastReadMessageId (optional)
    """
    if not reader_uid or not peer_uid or reader_uid == peer_uid:
        return

    try:
        ref, _ = _private_chat_ref_for_pair(reader_uid, peer_uid)
        update_payload = {
            f"memberMeta.{reader_uid}.lastOpenedAt": _fs.SERVER_TIMESTAMP,
            "updatedAt": _fs.SERVER_TIMESTAMP,
        }
        if last_read_message_id:
            update_payload[f"memberMeta.{reader_uid}.lastReadMessageId"] = last_read_message_id

        ref.update(update_payload)
    except Exception as e:
        print("⚠️ update_private_chat_read_state failed:", e, flush=True)


def clear_private_block_flags_in_firestore(uid_a: str, uid_b: str):
    """
    UNBLOCK: Clear the **canonical** flags:
      - memberMeta[uid_a].iBlockedPeer = False
      - memberMeta[uid_b].blockedByOther = False
    """
    if not uid_a or not uid_b or uid_a == uid_b:
        return
    try:
        ref, _ = _private_chat_ref_for_pair(uid_a, uid_b)
        print(f"🔓 clear_block Firestore: a={uid_a} b={uid_b}", flush=True)

        ref.update({
            f"memberMeta.{uid_a}.iBlockedPeer": False,
            f"memberMeta.{uid_b}.blockedByOther": False,
            "updatedAt": _fs.SERVER_TIMESTAMP,
        })
    except Exception as e:
        print("❌ clear_private_block_flags_in_firestore failed:", e, flush=True)


def is_private_block_active_in_firestore(blocker_uid: str, blocked_uid: str) -> bool:
    """
    Return True if Firestore still shows blocker_uid having iBlockedPeer=True
    for blocked_uid in their private chat's memberMeta.

    If anything fails, we return True to be safe (treat as still blocked),
    so we don't accidentally expose someone that *should* be blocked.
    """
    if not blocker_uid or not blocked_uid or blocker_uid == blocked_uid:
        return False

    try:
        ref, _ = _private_chat_ref_for_pair(blocker_uid, blocked_uid)
        snap = ref.get()
        if not snap.exists:
            return False

        data = snap.to_dict() or {}
        meta = data.get("memberMeta") or {}
        me = meta.get(blocker_uid) or {}

        # If iBlockedPeer is present and truthy => still blocked
        return bool(me.get("iBlockedPeer"))
    except Exception as e:
        print("⚠️ is_private_block_active_in_firestore failed:", e, flush=True)
        # Failsafe: assume still blocked if the lookup fails
        return True



def get_private_chat_member_meta(uid_a: str, uid_b: str) -> Dict[str, dict]:
    """
    Return the memberMeta dict for the private chat between the two UIDs.
    """
    if not uid_a or not uid_b or uid_a == uid_b:
        return {}

    try:
        ref, _ = _private_chat_ref_for_pair(uid_a, uid_b)
        snap = ref.get()
        if not snap.exists:
            return {}
        data = snap.to_dict() or {}
        meta = data.get("memberMeta") or {}
        return meta if isinstance(meta, dict) else {}
    except Exception as e:
        print("⚠️ get_private_chat_member_meta failed:", e, flush=True)
        return {}


def has_peer_read_message_for_private_chat(
    group: ChatGroup,
    message: GroupMessages,
    me,
    peer,
    member_meta: Optional[Dict[str, dict]] = None,
) -> bool:
    """
    Return True if `peer` has read `message` in this private chat.
    """
    if not getattr(group, "is_private", False):
        return False

    # We only care about *my* messages (me is the author)
    if message.author_id != getattr(me, "id", None):
        return False

    my_uid = getattr(getattr(me, "profile", None), "firebase_uid", None)
    peer_uid = getattr(getattr(peer, "profile", None), "firebase_uid", None)
    msg_mid = getattr(message, "firebase_message_id", None)

    if not my_uid or not peer_uid or not msg_mid:
        return False

    try:
        meta = member_meta if member_meta is not None else get_private_chat_member_meta(my_uid, peer_uid)
        peer_meta = meta.get(peer_uid) if isinstance(meta, dict) else {}
        last_read_mid = peer_meta.get("lastReadMessageId")
        if not last_read_mid:
            return False

        # If this exact message is the last read one
        if last_read_mid == msg_mid:
            return True

        # Otherwise, treat **all of my messages sent before or at the time the last-read
        # message was created** as read.
        last_read_msg = (
            GroupMessages.objects
            .filter(group=group, firebase_message_id=last_read_mid)
            .only("id", "created")
            .first()
        )

        if not last_read_msg or not last_read_msg.created or not message.created:
            return False

        return message.created <= last_read_msg.created
    except Exception as e:
        print("⚠️ has_peer_read_message_for_private_chat failed:", e, flush=True)
        return False



def get_private_chat_peer_last_read_mid(group: ChatGroup, me, peer) -> Optional[str]:
    """
    Look up memberMeta[peer_uid].lastReadMessageId for this private chat.
    """
    if not getattr(group, "is_private", False):
        return None

    my_uid = getattr(getattr(me, "profile", None), "firebase_uid", None)
    peer_uid = getattr(getattr(peer, "profile", None), "firebase_uid", None)
    if not my_uid or not peer_uid:
        return None

    chat_id = ensure_firestore_private_chat_for_group(group)
    if not chat_id:
        return None

    db = get_db()
    try:
        chat_ref = db.collection("chats").document(chat_id)
        snap = chat_ref.get()
        if not snap.exists:
            return None
        data = snap.to_dict() or {}
        member_meta = data.get("memberMeta") or {}
        peer_meta = member_meta.get(peer_uid) or {}
        return peer_meta.get("lastReadMessageId")
    except Exception as e:
        print("⚠️ get_private_chat_peer_last_read_mid failed:", e, flush=True)
        return None


def mark_last_message_read_in_firestore_for_group(group: ChatGroup, reader_uid: str):
    """
    Flip lastMessageIsRead/Status if the latest message was sent by the peer.
    """
    if not reader_uid or not getattr(group, "is_private", False):
        return

    chat_id = ensure_firestore_private_chat_for_group(group)
    if not chat_id:
        return

    db = get_db()
    chat_ref = db.collection("chats").document(chat_id)
    try:
        snap = chat_ref.get()
        if not snap.exists:
            return
        data = snap.to_dict() or {}
        last_sender = data.get("lastMessageSenderId")
        last_mid = data.get("lastMessageId")
        if not last_mid or not last_sender:
            return
        if last_sender == reader_uid:
            return

        chat_ref.set(
            {
                "lastMessageIsRead": True,
                "lastMessageStatus": "read",
                "updatedAt": _fs.SERVER_TIMESTAMP,
            },
            merge=True,
        )
    except Exception as e:
        print("⚠️ mark_last_message_read_in_firestore_for_group failed:", e, flush=True)


    
def _ensure_aware(dt):
    """Ensure datetime is timezone-aware in the project timezone."""
    if not dt:
        return None
    try:
        if timezone.is_naive(dt):
            return timezone.make_aware(dt, timezone.get_default_timezone())
    except Exception:
        pass
    return dt


def get_group_last_read_timestamp(group: ChatGroup):
    """
    For a non-private (group) chat, return a datetime representing
    'everything up to this point has been seen by someone', based on:
      - chats/{id}.lastMessageIsRead
      - chats/{id}.lastMessageTimestamp
    """
    if getattr(group, "is_private", False):
        return None
    if not getattr(group, "firebase_chat_id", None):
        return None

    db = get_db()
    try:
        chat_ref = db.collection("chats").document(group.firebase_chat_id)
        snap = chat_ref.get()
        if not snap.exists:
            return None
        data = snap.to_dict() or {}
        if not data.get("lastMessageIsRead"):
            return None
        ts = data.get("lastMessageTimestamp")
        return _safe_make_aware(ts)
    except Exception as e:
        print("⚠️ get_group_last_read_timestamp failed:", e, flush=True)
        return None


def has_group_message_been_read(group: ChatGroup, message: GroupMessages) -> bool:
    """
    For group chats, treat all of *my* messages with created <= lastMessageTimestamp
    as 'read' once Firestore says lastMessageIsRead=True.
    """
    if getattr(group, "is_private", False):
        return False

    # Only care about messages that have been mirrored to Firestore
    if not getattr(message, "firebase_message_id", None):
        return False

    msg_created = getattr(message, "created", None)
    if not msg_created:
        return False
    msg_created = _ensure_aware(msg_created)

    last_read_ts = get_group_last_read_timestamp(group)
    if not last_read_ts:
        return False

    return msg_created <= last_read_ts


def mark_group_last_message_read_in_firestore(group: ChatGroup, reader_uid: str):
    """
    For group chats: if the lastMessage was sent by *someone else*, mark it
    as read in Firestore so mobile/web can show read state.
    """
    if not reader_uid or getattr(group, "is_private", False):
        return
    if not getattr(group, "firebase_chat_id", None):
        return

    db = get_db()
    chat_ref = db.collection("chats").document(group.firebase_chat_id)
    try:
        snap = chat_ref.get()
        if not snap.exists:
            return
        data = snap.to_dict() or {}
        last_sender = data.get("lastMessageSenderId")
        last_mid = data.get("lastMessageId")
        if not last_mid or not last_sender:
            return
        if last_sender == reader_uid:
            # I don't mark my own last message as 'read'
            return

        chat_ref.set(
            {
                "lastMessageIsRead": True,
                "lastMessageStatus": "read",
                "updatedAt": _fs.SERVER_TIMESTAMP,
            },
            merge=True,
        )
    except Exception as e:
        print("⚠️ mark_group_last_message_read_in_firestore failed:", e, flush=True)


# -------------------------- MESSAGE DELETE/HIDE HELPERS --------------------------

def _chat_id_for_group(group: ChatGroup) -> Optional[str]:
    """
    Resolve the Firestore chat id for a given ChatGroup, handling private vs group.
    Returns None if no binding can be ensured.
    """
    if group.is_private:
        return ensure_firestore_private_chat_for_group(group)
    return ensure_firestore_group_chat_for_group(group)


def delete_message_in_firestore(message: GroupMessages, deleted_by_uid: Optional[str] = None) -> bool:
    """
    Mark a message as deleted in Firestore:
      - deleted = True
      - deletedAt = server timestamp
      - deletedBy = uid (if provided)
      - text cleared (empty string) to mirror your import behavior
    """
    chat_id = _chat_id_for_group(message.group)
    mid = getattr(message, "firebase_message_id", None)
    if not chat_id or not mid:
        # Nothing to do if we don't know which Firestore doc corresponds
        return False

    db = get_db()
    msg_ref = db.collection("chats").document(chat_id).collection("messages").document(mid)
    payload = {
        "deleted": True,
        "deletedAt": _fs.SERVER_TIMESTAMP,
        "deletedBy": deleted_by_uid,
        "text": "",
    }
    try:
        msg_ref.set(payload, merge=True)
        # Optional: if this was the lastMessage in the chat, clean up summary fields
        try:
            chat_ref = db.collection("chats").document(chat_id)
            snap = chat_ref.get()
            data = snap.to_dict() or {}
            if data.get("lastMessageId") == mid:
                chat_ref.set(
                    {
                        "lastMessageText": "This message was deleted",
                        "lastMessageStatus": "deleted",
                        "lastMessageIsRead": True,
                        "lastMessageTimestamp": _fs.SERVER_TIMESTAMP,
                        "updatedAt": _fs.SERVER_TIMESTAMP,
                    },
                    merge=True,
                )
        except Exception:
            # Don't fail overall if lastMessage* update fails
            pass
        return True
    except Exception as e:
        print("⚠️ delete_message_in_firestore failed:", e, flush=True)
        return False


def hide_message_for_user_in_firestore(message: GroupMessages, user) -> bool:
    """
    Add the current user's UID to hiddenFor[] on the Firestore message.
    This is a per-user "delete for me" flag. Does nothing if user has no firebase_uid.
    """
    uid = getattr(getattr(user, "profile", None), "firebase_uid", None)
    if not uid:
        return False

    chat_id = _chat_id_for_group(message.group)
    mid = getattr(message, "firebase_message_id", None)
    if not chat_id or not mid:
        return False

    db = get_db()
    msg_ref = db.collection("chats").document(chat_id).collection("messages").document(mid)
    try:
        msg_ref.set({"hiddenFor": _fs.ArrayUnion([uid])}, merge=True)
        return True
    except Exception as e:
        print("⚠️ hide_message_for_user_in_firestore failed:", e, flush=True)
        return False
