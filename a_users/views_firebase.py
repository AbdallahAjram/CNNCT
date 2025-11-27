# a_users/views_firebase.py

import json
from django.shortcuts import render, redirect, get_object_or_404
from django.urls import reverse
from django.http import JsonResponse, HttpResponse
from django.views.decorators.http import require_POST
from django.views.decorators.csrf import csrf_exempt
from django.contrib import messages
from django.contrib.auth import get_user_model, login, logout
from django.contrib.auth.decorators import login_required
from django.contrib.auth.views import redirect_to_login

from allauth.account.models import EmailAddress
from allauth.account.forms import default_token_generator
from allauth.account.utils import user_pk_to_url_str
from allauth.account import app_settings

from django.core.mail import send_mail

from firebase_admin import firestore
from firebase_admin import auth as admin_auth

from a_users.firebase_upload import upload_profile_image_to_firebase
from .models import Profile, BlockedUser
from .forms import ProfileForm, EmailForm, UsernameForm

from a_users.sync import sync_profile_from_firebase, push_profile_to_firebase
from a_core.firebase_admin_client import get_bucket

# Firestore private-chat helpers for block flags
from a_rtchat.firebase_sync import (
    set_private_block_flags_in_firestore,
    clear_private_block_flags_in_firestore,
    normalize_user_payload_to_displayName,
)

# ---------- FIREBASE LOGIN & SESSION HANDLING ----------

db = firestore.client()


def firebase_login(request):
    """Render Firebase login page."""
    return render(request, "account/login.html")


@login_required
def whoami(request):
    return JsonResponse({"user": request.user.username})


@csrf_exempt
@require_POST
def firebase_session_login(request):
    """Handle Firebase ID token, create user/profile, and start Django session."""
    try:
        body = json.loads(request.body.decode("utf-8"))
        id_token = body["idToken"]
        decoded = admin_auth.verify_id_token(id_token, check_revoked=True)
    except Exception as e:
        return JsonResponse({"error": f"Invalid ID token: {e}"}, status=400)

    uid = decoded["uid"]
    email = (decoded.get("email") or f"{uid}@noemail.local").lower()

    User = get_user_model()
    user, _ = User.objects.get_or_create(
        username=email,
        defaults={"email": email},
    )
    
    # Ensure email is always synced on subsequent logins
    if user.email != email:
        user.email = email
        user.save(update_fields=["email"])

    profile, _ = Profile.objects.get_or_create(user=user)
    if profile.firebase_uid != uid:
        profile.firebase_uid = uid
        profile.save(update_fields=["firebase_uid"])

    # Django login
    login(request, user, backend="a_users.auth_backends.FirebaseBackend")

    # Touch session to ensure cookie persistence
    request.session["firebase_uid"] = uid
    request.session.save()

    # Try syncing profile data from Firestore (non-blocking)
    try:
        sync_profile_from_firebase(uid)
    except Exception as e:
        print("⚠️ Firebase profile sync failed:", e)

    return JsonResponse({"ok": True})


# ---------- EMAIL CONFIRMATION UTIL ----------

def send_email_confirmation(request, user, signup=False):
    """Custom email confirmation using allauth token logic."""
    email = user.email
    token_generator = default_token_generator
    temp_key = token_generator.make_token(user)
    uid = user_pk_to_url_str(user)
    confirm_url = reverse("account_confirm_email", args=[uid, temp_key])

    send_mail(
        "Email Confirmation",
        f"Please confirm your email: {request.build_absolute_uri(confirm_url)}",
        app_settings.DEFAULT_FROM_EMAIL,
        [email],
    )


# ---------- PROFILE VIEW (SYNCED WITH FIREBASE) ----------

@login_required
def profile_view(request, username=None):
    print("🧭 USING views_firebase.profile_view", flush=True)

    if username:
        profile = get_object_or_404(Profile, user__username__iexact=username)
        is_self = request.user == profile.user
    else:
        profile, _ = Profile.objects.get_or_create(user=request.user)
        is_self = True

    # Ensure firebase_uid if missing but present in session
    if not profile.firebase_uid and request.session.get("firebase_uid"):
        profile.firebase_uid = request.session["firebase_uid"]
        profile.save(update_fields=["firebase_uid"])

    # Sync from Firestore on GET
    if request.method == "GET" and is_self and profile.firebase_uid:
        try:
            sync_profile_from_firebase(profile.firebase_uid)
            profile.refresh_from_db()
        except Exception as e:
            print("⚠️ Firebase sync failed:", e, flush=True)

    # Prepare form for both GET and POST
    form = ProfileForm(instance=profile) if is_self else None

    # ---------- Handle Profile Update ----------
    if request.method == "POST":
        print("🟢 POST triggered", flush=True)
        form = ProfileForm(request.POST, request.FILES, instance=profile)
        print("FILES received:", request.FILES, flush=True)

        if form.is_valid():
            print("✅ Form valid", flush=True)
            profile = form.save(commit=False)

            print("FILES keys:", list(request.FILES.keys()), flush=True)
            image_file = request.FILES.get("image")  # match the ModelForm field name exactly
            print("📸 Image file object:", repr(image_file), flush=True)
            print("👤 Firebase UID:", profile.firebase_uid, flush=True)
            if image_file and profile.firebase_uid:
                try:
                    bucket = get_bucket()

                    print(f"🧹 Cleaning old images under avatars/{profile.firebase_uid}/ ...", flush=True)
                    for blob in bucket.list_blobs(prefix=f"avatars/{profile.firebase_uid}/"):
                        print("   - deleting:", blob.name, flush=True)
                        blob.delete()

                    # Upload new image (versioned path) and get a fresh URL
                    photo_url = upload_profile_image_to_firebase(image_file, profile.firebase_uid)
                    print("📎 NEW photo_url from uploader:", photo_url, flush=True)

                    if photo_url:
                        profile.photo_url = photo_url
                        print("✅ Set profile.photo_url", flush=True)
                    else:
                        print("⚠️ upload_profile_image_to_firebase returned None", flush=True)

                except Exception as e:
                    print("🔥 Firebase upload failed:", e, flush=True)
            else:
                print("⚠️ Skipping upload — no image or no Firebase UID", flush=True)

            # Save locally then push to Firestore and verify
            profile.save()
            try:
                push_profile_to_firebase(profile)
                print("☁️ Pushed profile to Firestore. Verifying read-back…", flush=True)
                from a_core.firebase_admin_client import db as _db
                from a_rtchat.firebase_sync import get_profile_dict
                d = get_profile_dict(_db, profile.firebase_uid)
                print("🔎 Firestore read-back photoUrl:", d.get("photoUrl"), flush=True)
                print("🔎 Firestore read-back displayName:", d.get("displayName"), flush=True)
            except Exception as e:
                print("⚠️ Firestore push failed:", e, flush=True)

            messages.success(request, "Profile updated successfully.")
            return redirect("profile")

        else:
            print("❌ Form invalid:", form.errors, flush=True)

    # Always return a response on GET or invalid POST
    print("DEBUG UID:", profile.firebase_uid, flush=True)
    return render(request, "a_users/profile.html", {"profile": profile, "form": form, "is_self": is_self})


@login_required
def profile_edit_view(request):
    """Legacy redirect to unified profile view."""
    return redirect("profile")


@login_required
def profile_settings_view(request):
    """Account settings (blocked list, etc.)."""
    blocked = (
        BlockedUser.objects.filter(blocker=request.user)
        .select_related("blocked__profile")
        .order_by("-blocked_at")
    )
    return render(request, "a_users/profile_settings.html", {"blocked_users": blocked})


@login_required
def profile_blocked_list(request):
    blocked = (
        BlockedUser.objects.filter(blocker=request.user)
        .select_related("blocked__profile")
        .order_by("-blocked_at")
    )
    return render(
        request,
        "a_users/partials/blocked_list.html",
        {"blocked_users": blocked},
    )


# ---------- BLOCK / UNBLOCK ----------

@login_required
def profile_block_user(request, username):
    """
    Block another user by username, persist to local DB (BlockedUser),
    and mirror pair flags to Firestore private chat doc(s).
    """
    target = get_object_or_404(get_user_model(), username=username)

    if target.id == request.user.id:
        messages.info(request, "You can’t block yourself.")
        return redirect("profile-settings")

    # Create local block record (idempotent)
    BlockedUser.objects.get_or_create(blocker=request.user, blocked=target)
    messages.success(request, f"Blocked @{target.username}.")

    # 🔄 Firestore: set pair flags (iBlockedPeer / blockedByOther) for both sides
    try:
        me_uid = getattr(request.user.profile, "firebase_uid", None)
        peer_uid = getattr(target.profile, "firebase_uid", None)
        if me_uid and peer_uid:
            set_private_block_flags_in_firestore(me_uid, peer_uid)
    except Exception as e:
        print("⚠️ Firestore block flag update failed:", e, flush=True)

    # If invoked via HTMX, redirect away from the chat modal cleanly
    if getattr(request, "htmx", False):
        resp = HttpResponse(status=204)
        resp["HX-Redirect"] = reverse("home")
        return resp

    return redirect("profile-settings")


@login_required
def profile_unblock_user(request, username):
    """Unblock a user and clear the Firestore pair flags."""
    target = get_object_or_404(get_user_model(), username=username)
    BlockedUser.objects.filter(blocker=request.user, blocked=target).delete()
    messages.success(request, f"Unblocked @{target.username}.")

    # 🔄 Firestore: clear flags for both directions
    try:
        a = getattr(request.user.profile, "firebase_uid", None)
        b = getattr(target.profile, "firebase_uid", None)
        if a and b:
            clear_private_block_flags_in_firestore(a, b)
    except Exception as e:
        print("⚠️ Firestore unblock flag update failed:", e, flush=True)

    return redirect("profile-settings")


# ---------- EMAIL, USERNAME, VERIFY, DELETE ----------

@login_required
def profile_emailchange(request):
    """Change email and trigger new confirmation."""
    if request.htmx:
        form = EmailForm(instance=request.user)
        return render(request, "partials/email_form.html", {"form": form})

    if request.method == "POST":
        form = EmailForm(request.POST, instance=request.user)
        if form.is_valid():
            email = form.cleaned_data["email"]
            if (
                get_user_model().objects.filter(email=email)
                .exclude(id=request.user.id)
                .exists()
            ):
                messages.warning(request, f"{email} is already in use.")
                return redirect("profile-settings")

            form.save()
            send_email_confirmation(request, request.user)
            messages.success(request, "Confirmation email sent.")
            return redirect("profile-settings")

        messages.warning(request, "Email not valid or already in use")
    return redirect("profile-settings")


@login_required
def profile_usernamechange(request):
    """Change username."""
    if request.htmx:
        form = UsernameForm(instance=request.user)
        return render(request, "partials/username_form.html", {"form": form})

    if request.method == "POST":
        form = UsernameForm(request.POST, instance=request.user)
        if form.is_valid():
            form.save()
            messages.success(request, "Username updated successfully.")
        else:
            messages.warning(request, "Username not valid or already in use")
    return redirect("profile-settings")


@login_required
def profile_emailverify(request):
    """Resend email verification."""
    send_email_confirmation(request, request.user)
    messages.success(request, "Verification email sent.")
    return redirect("profile-settings")


@login_required
def profile_delete_view(request):
    """Account deletion flow."""
    user = request.user
    if request.method == "POST":
        logout(request)
        user.delete()
        messages.success(request, "Account deleted. Sorry to see you go.")
        return redirect("home")
    return render(request, "a_users/profile_delete.html")
