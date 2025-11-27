# ✅ import modules with aliases
from a_users import views as v
from a_users import views_firebase as vfb
from django.urls import path

urlpatterns = [
    path('', vfb.profile_view, name="profile"),             
    path('edit/', vfb.profile_view, name="profile-edit"),
    path('onboarding/', vfb.profile_edit_view, name="profile-onboarding"),
    path('settings/', vfb.profile_settings_view, name="profile-settings"),
    path('settings/blocked/', vfb.profile_blocked_list, name="profile-blocked-list"),
    path('settings/block/<str:username>/', vfb.profile_block_user, name="profile-block-user"),
    path('settings/unblock/<str:username>/', vfb.profile_unblock_user, name="profile-unblock-user"),
    path('emailchange/', vfb.profile_emailchange, name="profile-emailchange"),
    path('usernamechange/', vfb.profile_usernamechange, name="profile-usernamechange"),
    path('emailverify/', vfb.profile_emailverify, name="profile-emailverify"),
    path('delete/', vfb.profile_delete_view, name="profile-delete"),
    path("whoami/", vfb.whoami, name="whoami"),

    path("login/", vfb.firebase_login, name="firebase_login"),
    path("auth/firebase/session-login", vfb.firebase_session_login, name="firebase_session_login"),
]