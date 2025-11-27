# a_users/firebase_upload.py
import io
from typing import Optional
from uuid import uuid4
from urllib.parse import quote
from PIL import Image
from a_core.firebase_admin_client import get_bucket

def upload_profile_image_to_firebase(file_obj, firebase_uid: str) -> Optional[str]:
    """
    Upload the user's avatar to a fixed path:
      avatars/<uid>/avatar.jpg
    Returns the public URL (or bucket URL you already use).
    """
    if not file_obj or not firebase_uid:
        return None

    # Read into Pillow and normalize (optional: resize, convert)
    image = Image.open(file_obj)
    image = image.convert("RGB")

    buf = io.BytesIO()
    image.save(buf, format="JPEG", quality=90, optimize=True)
    buf.seek(0)

    bucket = get_bucket()
    object_name = f"avatars/{firebase_uid}/avatar.jpg"
    blob = bucket.blob(object_name)

    data = buf.read()
    content_type = getattr(file_obj, "content_type", None) or "image/jpeg"
    blob.upload_from_string(data, content_type=content_type)

    # Set download token for Firebase Storage URL generation
    md = blob.metadata or {}
    if not md.get("firebaseStorageDownloadTokens"):
        md["firebaseStorageDownloadTokens"] = str(uuid4())
        blob.metadata = md
        blob.patch()
        blob.reload()

    try:
        blob.make_public()  # keep if your app expects public URLs; otherwise remove
    except Exception:
        pass

    # Generate URL using token method (consistent with avatar property)
    token = blob.metadata.get("firebaseStorageDownloadTokens") if blob.metadata else None
    if token:
        encoded = quote(blob.name, safe="")
        url = f"https://firebasestorage.googleapis.com/v0/b/{bucket.name}/o/{encoded}?alt=media&token={token}"
    else:
        # Fallback to public URL
        url = blob.public_url if hasattr(blob, 'public_url') else None
        if not url:
            # Last resort: generate signed URL
            url = blob.generate_signed_url(version="v4", expiration=3600 * 24 * 30)

    print(f"⬆️  Uploaded to GCS: {object_name}", flush=True)
    print(f"🔗 Generated URL: {url}", flush=True)
    return url
