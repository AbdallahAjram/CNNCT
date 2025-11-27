from django.shortcuts import render, redirect, get_object_or_404
from django.urls import reverse
from allauth.account.forms import default_token_generator
from allauth.account.utils import user_pk_to_url_str
from allauth.account import app_settings
from django.core.mail import send_mail
from django.contrib.auth.decorators import login_required
from django.contrib.auth import logout
from django.contrib.auth.models import User
from django.contrib.auth.views import redirect_to_login
from django.contrib import messages
from .forms import *
from .models import BlockedUser


def _clean_blocked_users_for_user(user):
    """
    Return the list of BlockedUser rows for `user`, pruning any entries that are
    no longer blocked on Firestore (e.g., unblocked on mobile).
    """
    qs = (
        BlockedUser.objects
        .filter(blocker=user)
        .select_related('blocked__profile')
        .order_by('-blocked_at')
    )
    cleaned = []

    for bu in list(qs):
        blocker_prof = getattr(bu.blocker, "profile", None)
        blocked_prof = getattr(bu.blocked, "profile", None)

        blocker_uid = getattr(blocker_prof, "firebase_uid", None)
        blocked_uid = getattr(blocked_prof, "firebase_uid", None)

        # If either profile lacks a firebase_uid, keep the row local-only
        if not blocker_uid or not blocked_uid:
            cleaned.append(bu)
            continue

        try:
            from a_rtchat.firebase_sync import is_private_block_active_in_firestore

            still_blocked = is_private_block_active_in_firestore(blocker_uid, blocked_uid)
        except Exception as e:
            print("⚠️ Firestore block check failed:", e, flush=True)
            still_blocked = True

        if still_blocked:
            cleaned.append(bu)
        else:
            bu.delete()

    return cleaned

def send_email_confirmation(request, user, signup=False):
    """
    Replacement for allauth's send_email_confirmation
    """
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

def profile_view(request, username=None):
    # Merge view/edit: if viewing own profile, allow edit in same template
    if username:
        profile = get_object_or_404(User, username=username).profile
        is_self = request.user.is_authenticated and request.user == profile.user
    else:
        try:
            profile = request.user.profile
        except:
            return redirect_to_login(request.get_full_path())
        is_self = True

    form = None
    if is_self:
        form = ProfileForm(instance=profile)
        if request.method == 'POST':
            form = ProfileForm(request.POST, request.FILES, instance=profile)
            if form.is_valid():
                form.save()
                return redirect('profile')

    return render(request, 'a_users/profile.html', {'profile': profile, 'form': form, 'is_self': is_self})

@login_required
def profile_edit_view(request):
    # Redirect to merged profile page
    return redirect('profile')

@login_required
def profile_settings_view(request):
    blocked = _clean_blocked_users_for_user(request.user)
    return render(request, 'a_users/profile_settings.html', { 'blocked_users': blocked })

@login_required
def profile_blocked_list(request):
    blocked = _clean_blocked_users_for_user(request.user)
    return render(request, 'a_users/partials/blocked_list.html', { 'blocked_users': blocked })

@login_required
def profile_block_user(request, username):
    target = get_object_or_404(User, username=username)
    if target.id == request.user.id:
        return redirect('profile-settings')
    BlockedUser.objects.get_or_create(blocker=request.user, blocked=target)
    messages.success(request, f'Blocked @{target.username}.')
    # If called via HTMX from chat, redirect back to chat index silently
    if getattr(request, 'htmx', False):
        from django.http import HttpResponse
        resp = HttpResponse(status=204)
        resp['HX-Redirect'] = reverse('home')
        return resp
    return redirect('profile-settings')

@login_required
def profile_unblock_user(request, username):
    target = get_object_or_404(User, username=username)
    BlockedUser.objects.filter(blocker=request.user, blocked=target).delete()
    messages.success(request, f'Unblocked @{target.username}.')
    return redirect('profile-settings')

@login_required
def profile_emailchange(request):
    
    if request.htmx:
        form = EmailForm(instance=request.user)
        return render(request, 'partials/email_form.html', {'form':form})
    
    if request.method == 'POST':
        form = EmailForm(request.POST, instance=request.user)

        if form.is_valid():
            
            # Check if the email already exists
            email = form.cleaned_data['email']
            if User.objects.filter(email=email).exclude(id=request.user.id).exists():
                messages.warning(request, f'{email} is already in use.')
                return redirect('profile-settings')
            
            form.save() 
            
            # Then Signal updates emailaddress and set verified to False
            
            # Then send confirmation email 
            send_email_confirmation(request, request.user)
            
            return redirect('profile-settings')
        else:
            messages.warning(request, 'Email not valid or already in use')
            return redirect('profile-settings')
        
    return redirect('profile-settings')

@login_required
def profile_usernamechange(request):
    if request.htmx:
        form = UsernameForm(instance=request.user)
        return render(request, 'partials/username_form.html', {'form':form})
    
    if request.method == 'POST':
        form = UsernameForm(request.POST, instance=request.user)
        
        if form.is_valid():
            form.save()
            messages.success(request, 'Username updated successfully.')
            return redirect('profile-settings')
        else:
            messages.warning(request, 'Username not valid or already in use')
            return redirect('profile-settings')
    
    return redirect('profile-settings')    

@login_required
def profile_emailverify(request):
    send_email_confirmation(request, request.user)
    return redirect('profile-settings')

@login_required
def profile_delete_view(request):
    user = request.user
    if request.method == "POST":
        logout(request)
        user.delete()
        messages.success(request, 'Account deleted, what a pity')
        return redirect('home')
    
    return render(request, 'a_users/profile_delete.html')