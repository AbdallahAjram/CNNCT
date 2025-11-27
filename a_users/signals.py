from django.dispatch import receiver
from django.db.models.signals import post_save, pre_save
from django.contrib.auth import get_user_model

try:
    from allauth.account.models import EmailAddress
except Exception:
    EmailAddress = None

from .models import Profile

User = get_user_model()


@receiver(post_save, sender=User)
def user_postsave(sender, instance, created, **kwargs):
    user = instance

    # Ensure every User has a Profile
    if created:
        p = Profile.objects.create(user=user)
        # You can leave phone_number blank — Firebase sync will fill it later
        if not p.phone_number:
            p.phone_number = ""
            p.save(update_fields=["phone_number"])

    # Keep allauth EmailAddress in sync (for both created and updated users)
    sync_allauth_email_address(sender, instance, created, **kwargs)


def sync_allauth_email_address(sender, instance, created, **kwargs):
    """
    Keep django-allauth EmailAddress in sync with User.email.

    - Do nothing if allauth isn't available.
    - If user.email is blank, skip.
    - If EmailAddress doesn't exist yet, create it.
    - Ensure exactly one primary EmailAddress = User.email.
    """
    if EmailAddress is None:
        return

    user = instance
    email = (user.email or "").strip().lower()
    if not email:
        # No email to sync
        return

    # Try to find a matching EmailAddress for this user+email
    email_address = EmailAddress.objects.filter(
        user=user, email__iexact=email
    ).first()

    if email_address is None:
        # Either no EmailAddress yet, or only stale ones
        # Option 1: reuse an existing row for this user, if any
        existing = EmailAddress.objects.filter(user=user).first()
        if existing:
            email_address = existing
            email_address.email = email
        else:
            email_address = EmailAddress(user=user, email=email)

        email_address.primary = True
        # If you don't want auto-verification, set this to False
        email_address.verified = True
        email_address.save()
    else:
        # Make sure flags are correct
        changed = False
        if not email_address.primary:
            email_address.primary = True
            changed = True
        if not email_address.verified:
            email_address.verified = True
            changed = True
        if changed:
            email_address.save(update_fields=["primary", "verified"])

    # Demote any other EmailAddress rows for this user
    EmailAddress.objects.filter(user=user).exclude(pk=email_address.pk).update(
        primary=False
    )


@receiver(pre_save, sender=User)
def user_presave(sender, instance, **kwargs):
    # Always lowercase username for consistency
    if instance.username:
        instance.username = instance.username.lower()
