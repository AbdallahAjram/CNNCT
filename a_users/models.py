# a_users/models.py
from django.db import models
from django.db.models.signals import post_save, post_delete
from django.contrib.auth.models import User
from django.conf import settings
from uuid import uuid4
from urllib.parse import quote
from a_core.firebase_admin_client import get_bucket
from django.dispatch import receiver


class Profile(models.Model):
    user = models.OneToOneField(User, on_delete=models.CASCADE)
    image = models.ImageField(upload_to='avatars/', null=True, blank=True)

    # Firebase identity
    firebase_uid = models.CharField(max_length=128, unique=True, null=True, blank=True)

    # Synced Firebase fields
    display_name = models.CharField(max_length=150, null=True, blank=True)
    name = models.CharField(max_length=150, null=True, blank=True)
    email = models.CharField(max_length=254, null=True, blank=True)
    phone_number = models.CharField(max_length=32, null=True, blank=True)
    photo_url = models.CharField(max_length=500, null=True, blank=True)
    updated_at = models.DateTimeField(null=True, blank=True)
    last_online_at = models.DateTimeField(null=True, blank=True)
    about = models.TextField(null=True, blank=True)

    # Legacy (keep temporarily if old code references it)
    displayname = models.CharField(max_length=20, null=True, blank=True)

    def __str__(self):
        return str(self.user)

    @property
    def avatar(self):
        """
        Return a usable avatar URL.

        Order:
        1) Use cached Firestore/GCS URL in self.photo_url if present.
        2) If missing, look up the fixed avatar path in Firebase Storage:
           - check for avatars/{uid}/avatar.jpg
           - attach a firebase download token so the URL is browser-friendly
           - cache it into self.photo_url to avoid re-listing later
        3) Fallback to uploaded image field.
        4) Fallback to default static icon.
        """
        # 1) Use cached URL if we have it
        if getattr(self, "photo_url", None):
            return self.photo_url

        # 2) Discover from Storage if we know the Firebase UID
        try:
            if getattr(self, "firebase_uid", None):
                bucket = get_bucket()
                uid = self.firebase_uid

                # First, try the fixed path: avatars/{uid}/avatar.jpg
                blob_path = f"avatars/{uid}/avatar.jpg"

                # ✅ use get_blob() to check existence; returns None if missing
                blob = bucket.get_blob(blob_path)
                if blob:
                    # Ensure a Firebase download token so the URL works without signing
                    md = blob.metadata or {}
                    if not md.get("firebaseStorageDownloadTokens"):
                        md["firebaseStorageDownloadTokens"] = str(uuid4())
                        blob.metadata = md
                        blob.patch()  # no reload needed

                    token = (blob.metadata or {}).get("firebaseStorageDownloadTokens")
                    if token:
                        encoded = quote(blob.name, safe="")
                        url = f"https://firebasestorage.googleapis.com/v0/b/{bucket.name}/o/{encoded}?alt=media&token={token}"
                    else:
                        # Fallback to public/signed URL if token method isn’t used
                        url = getattr(blob, 'public_url', None) or blob.generate_signed_url(
                            version="v4", expiration=3600 * 24 * 30
                        )

                    if url:
                        self.photo_url = url
                        try:
                            self.save(update_fields=["photo_url"])
                        except Exception:
                            pass
                        return url

                # ❌ Not found at fixed path — fall back to old avatar_*.jpg (migration support)
                prefix = f"avatars/{uid}/"
                blobs = list(bucket.list_blobs(prefix=prefix))
                candidates = [b for b in blobs if b.name.startswith(prefix + "avatar_") and b.name.endswith(".jpg")]
                if candidates:
                    # Choose newest by update time
                    blob = max(candidates, key=lambda b: (b.updated, b.name))

                    # Ensure token on the old blob
                    md = blob.metadata or {}
                    if not md.get("firebaseStorageDownloadTokens"):
                        md["firebaseStorageDownloadTokens"] = str(uuid4())
                        blob.metadata = md
                        blob.patch()

                    token = (blob.metadata or {}).get("firebaseStorageDownloadTokens")
                    if token:
                        encoded = quote(blob.name, safe="")
                        url = f"https://firebasestorage.googleapis.com/v0/b/{bucket.name}/o/{encoded}?alt=media&token={token}"
                    else:
                        url = getattr(blob, 'public_url', None) or blob.generate_signed_url(
                            version="v4", expiration=3600 * 24 * 30
                        )

                    if url:
                        self.photo_url = url
                        try:
                            self.save(update_fields=["photo_url"])
                        except Exception:
                            pass
                        return url

        except Exception as e:
            # Any Storage hiccup falls through to normal fallbacks
            print(f"⚠️ Avatar lookup failed for {getattr(self, 'firebase_uid', 'unknown')}: {e}", flush=True)

        # 3) Fallback to uploaded image field
        if getattr(self, "image", None):
            try:
                return self.image.url
            except Exception:
                pass

        # 4) Default icon
        return f"{settings.STATIC_URL}images/avatar.svg"


class BlockedUser(models.Model):
    blocker = models.ForeignKey(User, on_delete=models.CASCADE, related_name='blocks_made')
    blocked = models.ForeignKey(User, on_delete=models.CASCADE, related_name='blocked_by')
    blocked_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        unique_together = ('blocker', 'blocked')

    def __str__(self):
        return f'{self.blocker.username} blocked {self.blocked.username}'


@receiver(post_save, sender=BlockedUser)
def sync_block_to_firestore(sender, instance, created, **kwargs):
    """
    When a BlockedUser row is created, mirror it to Firestore:
      memberMeta[blocker_uid].iBlockedPeer = True
      memberMeta[blocked_uid].blockedByOther = True
    """
    if not created:
        return

    blocker_prof = getattr(instance.blocker, "profile", None)
    blocked_prof = getattr(instance.blocked, "profile", None)

    blocker_uid = getattr(blocker_prof, "firebase_uid", None)
    blocked_uid = getattr(blocked_prof, "firebase_uid", None)

    if not blocker_uid or not blocked_uid:
        return  # nothing to sync

    try:
        # Import inside function to avoid circular import at module load time
        from a_rtchat.firebase_sync import set_private_block_flags_in_firestore

        set_private_block_flags_in_firestore(blocker_uid, blocked_uid)
    except Exception as e:
        print("⚠️ sync_block_to_firestore failed:", e, flush=True)


@receiver(post_delete, sender=BlockedUser)
def sync_unblock_to_firestore(sender, instance, **kwargs):
    """
    When a BlockedUser row is deleted, clear Firestore flags:
      memberMeta[blocker_uid].iBlockedPeer = False
      memberMeta[blocked_uid].blockedByOther = False
    """
    blocker_prof = getattr(instance.blocker, "profile", None)
    blocked_prof = getattr(instance.blocked, "profile", None)

    blocker_uid = getattr(blocker_prof, "firebase_uid", None)
    blocked_uid = getattr(blocked_prof, "firebase_uid", None)

    if not blocker_uid or not blocked_uid:
        return

    try:
        from a_rtchat.firebase_sync import clear_private_block_flags_in_firestore

        clear_private_block_flags_in_firestore(blocker_uid, blocked_uid)
    except Exception as e:
        print("⚠️ sync_unblock_to_firestore failed:", e, flush=True)
