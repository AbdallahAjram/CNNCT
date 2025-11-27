import firebase_admin
from firebase_admin import credentials, firestore, storage
from django.conf import settings

if not firebase_admin._apps:
    cred = credentials.Certificate(settings.BASE_DIR / "firebase-key.json")
    firebase_admin.initialize_app(cred, {
        "storageBucket": "cnnct-c91f5.firebasestorage.app"
    })

db = firestore.client()

def get_bucket():
    """Lazy-load Firebase Storage bucket."""
    return storage.bucket()
