from django.contrib.auth import get_user_model
from django.utils import timezone
from google.cloud.firestore_v1 import DocumentSnapshot
from firebase_admin import storage
from a_core.firebase_admin_client import db as _db
from .models import Profile
from a_core.firebase_admin_client import get_bucket
from a_rtchat.firebase_sync import normalize_user_payload_to_displayName, get_profile_dict


def _ensure_aware(dt):
    from google.cloud.firestore_v1._helpers import Timestamp
    if dt is None:
        return None
    if isinstance(dt, Timestamp):
        dt = dt.to_datetime()
    if timezone.is_aware(dt):
        return dt
    return timezone.make_aware(dt, timezone.utc)


def sync_profile_from_firebase(uid: str):
    """Fetch Firebase user data from Firestore and update/create the matching Profile in Django."""
    # Use self-healing read function that normalizes displayName
    data = get_profile_dict(_db, uid)
    
    if not data:
        raise ValueError(f"Firebase user document not found for uid={uid}")

    # Extract allowed fields (now normalized to displayName)
    display = data.get("displayName")
    name = data.get("name")
    email = data.get("email")
    phone_number = data.get("phoneNumber")
    photo_url = data.get("photoUrl")
    updated_at = _ensure_aware(data.get("updatedAt"))
    last_online_at = _ensure_aware(data.get("lastOnlineAt"))

    if hasattr(profile := Profile.objects.filter(firebase_uid=uid).first(), "updated_at"):
        if profile.updated_at and updated_at and updated_at <= profile.updated_at:
            print("⏩ Local profile is newer; skipping Firestore overwrite.", flush=True)
            return profile

    # Build photo URL if missing
    if not photo_url:
        try:
            bucket = get_bucket()
            blob_path = f"avatars/{uid}/avatar.jpg"
            blob = bucket.blob(blob_path)
            photo_url = blob.generate_signed_url(version="v4", expiration=3600 * 24 * 30)
        except Exception as e:
            print(f"⚠️ Could not fetch avatar URL for {uid}: {e}", flush=True)
            photo_url = None

    # Connect to existing user/profile if any
    try:
        profile = Profile.objects.get(firebase_uid=uid)
        user = profile.user
    except Profile.DoesNotExist:
        UserModel = get_user_model()
        username = (email or uid)
        user, _ = UserModel.objects.get_or_create(
            username=username,
            defaults={"email": email or ""},
        )
        profile, _ = Profile.objects.get_or_create(user=user, defaults={"firebase_uid": uid})

    # Update profile fields (use Django's display_name field)
    profile.firebase_uid = uid
    if display is not None:
        profile.display_name = display
    profile.name = name if name is not None else profile.name
    profile.email = email or profile.email
    profile.phone_number = phone_number if phone_number is not None else profile.phone_number
    profile.photo_url = photo_url if photo_url is not None else profile.photo_url
    profile.updated_at = updated_at or profile.updated_at
    profile.last_online_at = last_online_at or profile.last_online_at
    profile.about = (data.get("about") or data.get("info") or profile.about)

    # Optionally mirror user.email
    if email and getattr(user, "email", None) != email:
        user.email = email
        user.save(update_fields=["email"])

    profile.save()
    return profile


def push_profile_to_firebase(profile: Profile):
    """Push Django Profile changes back to Firestore."""
    if not profile.firebase_uid:
        print("⚠️ No Firebase UID — cannot sync to Firestore.", flush=True)
        return

    # Value to push (prefer canonical display_name; fall back to legacy displayname if populated)
    display_value = profile.display_name or getattr(profile, "displayname", None)

    # Build payload - will be normalized by normalize_user_payload_to_displayName
    payload = {
        "displayName": display_value,  # Prefer displayName
        "displayname": display_value,  # Accept displayname too (will be normalized)
        "name": profile.name,
        "email": profile.email,
        "phoneNumber": profile.phone_number,
        "photoUrl": profile.photo_url,
        "about": getattr(profile, "about", None) or getattr(profile, "info", None),
        "updatedAt": timezone.now(),
    }

    # Normalize to ensure only displayName is written (never displayname)
    data = normalize_user_payload_to_displayName(payload)

    print(f"📤 Pushing to users/{profile.firebase_uid}", flush=True)
    try:
        _db.collection("users").document(profile.firebase_uid).set(data, merge=True)
        print(f"✅ Synced profile {profile.user.username} to Firestore.", flush=True)
    except Exception as e:
        print("🔥 Firestore push failed:", e, flush=True)


def ensure_user_from_firebase_by_phone(raw_phone: str):
    """
    Try to find a Firestore user by phoneNumber and ensure a local Profile/User exists.
    Returns Profile or None.
    """
    if not raw_phone:
        return None

    digits = ''.join(ch for ch in raw_phone if ch.isdigit())
    candidates = set()
    if digits:
        candidates.add(digits)
        candidates.add("+" + digits)

    for val in candidates:
        q = _db.collection("users").where("phoneNumber", "==", val).limit(1)
        snaps = q.stream()
        for snap in snaps:
            if not snap.exists:
                continue
            uid = snap.id
            try:
                # Reuse existing sync to materialize into Django
                return sync_profile_from_firebase(uid)
            except Exception:
                pass
    return None
