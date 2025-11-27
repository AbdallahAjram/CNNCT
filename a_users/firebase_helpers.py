from firebase_admin import firestore
from django.contrib.auth import get_user_model
from a_users.models import Profile


def get_db():
    return firestore.client()


def get_or_create_user_by_uid(uid: str, email: str | None = None):
    User = get_user_model()
    prof = Profile.objects.filter(firebase_uid=uid).select_related("user").first()
    if prof:
        return prof.user, prof
    # Fallback: create a Django user bound to this uid
    username = (email or f"{uid}@noemail.local").lower()
    user, _ = User.objects.get_or_create(username=username, defaults={"email": email or ""})
    prof, _ = Profile.objects.get_or_create(user=user, defaults={"firebase_uid": uid})
    if not prof.firebase_uid:
        prof.firebase_uid = uid
        prof.save(update_fields=["firebase_uid"])
    return user, prof


