from django.contrib.auth import get_user_model
from django.contrib.auth.backends import ModelBackend
from django.db.models import Q


def normalize_phone(raw: str) -> str:
    if not raw:
        return ''
    return ''.join(ch for ch in raw if ch.isdigit())


class PhoneOrUsernameOrEmailBackend(ModelBackend):
    """Authenticate with username, email, or profile phone number."""
    def authenticate(self, request, username=None, password=None, **kwargs):
        UserModel = get_user_model()
        login_value = username or kwargs.get('login') or ''
        if not login_value or not password:
            return None

        phone = normalize_phone(login_value)
        user = None
        try:
            # Try username (case-insensitive)
            user = UserModel.objects.get(username__iexact=login_value)
        except UserModel.DoesNotExist:
            # Try email
            try:
                user = UserModel.objects.get(email__iexact=login_value)
            except UserModel.DoesNotExist:
                # Try phone on profile
                if phone:
                    try:
                        user = UserModel.objects.get(profile__phone=phone)
                    except UserModel.DoesNotExist:
                        user = None

        if user and user.check_password(password):
            return user
        return None


