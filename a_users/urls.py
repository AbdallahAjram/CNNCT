from django.urls import path
from a_users.views import *

urlpatterns = [
    path('', profile_view, name="profile"),
    path('edit/', profile_view, name="profile-edit"),
    path('onboarding/', profile_edit_view, name="profile-onboarding"),
    path('settings/', profile_settings_view, name="profile-settings"),
    path('settings/blocked/', profile_blocked_list, name="profile-blocked-list"),
    path('settings/block/<str:username>/', profile_block_user, name="profile-block-user"),
    path('settings/unblock/<str:username>/', profile_unblock_user, name="profile-unblock-user"),
    path('emailchange/', profile_emailchange, name="profile-emailchange"),
    path('usernamechange/', profile_usernamechange, name="profile-usernamechange"),
    path('emailverify/', profile_emailverify, name="profile-emailverify"),
    path('delete/', profile_delete_view, name="profile-delete"),
]
