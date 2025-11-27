from django.contrib.auth.backends import ModelBackend
from django.contrib.auth.models import User
from a_users.models import Profile
from firebase_admin import auth as admin_auth

class FirebaseBackend(ModelBackend):
    def authenticate(self, request, username=None, password=None, **kwargs):
        try:
            # Lookup in Firebase first
            user_record = admin_auth.get_user_by_email(username)
            profile = Profile.objects.filter(firebase_uid=user_record.uid).first()

            if profile:
                return profile.user
        except Exception:
            return None
