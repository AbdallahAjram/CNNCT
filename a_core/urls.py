"""
URL configuration for a_core project.

The `urlpatterns` list routes URLs to views. For more information please see:
    https://docs.djangoproject.com/en/5.0/topics/http/urls/
Examples:
Function views
    1. Add an import:  from my_app import views
    2. Add a URL to urlpatterns:  path('', views.home, name='home')
Class-based views
    1. Add an import:  from other_app.views import Home
    2. Add a URL to urlpatterns:  path('', Home.as_view(), name='home')
Including another URLconf
    1. Import the include() function: from django.urls import include, path
    2. Add a URL to urlpatterns:  path('blog/', include('blog.urls'))
"""
from django.contrib import admin
from django.urls import path, include
from django.conf.urls.static import static
from django.conf import settings
from a_users.views import profile_view
from a_home.views import *
from a_users.views_firebase import firebase_login
from a_users.views_firebase import whoami
from a_users.views_firebase import firebase_session_login
from a_rtchat import views as chat_views

urlpatterns = [
    path('admin/', admin.site.urls),
    path('accounts/', include('allauth.urls')),
    path("presence/ping/", chat_views.presence_ping, name="presence-ping"),
    path('', include('a_rtchat.urls')),
    path('profile/', include('a_users.urls')),
    path("auth/firebase/session-login", firebase_session_login, name="firebase_session_login"),
    path('@<username>/', profile_view, name="profile"),
    path('login/', firebase_login, name='firebase_login_root'),
    path("whoami/", whoami, name="whoami"),
]

# Only used when DEBUG=True, whitenoise can serve files when DEBUG=False
if settings.DEBUG:
    urlpatterns += static(settings.MEDIA_URL, document_root=settings.MEDIA_ROOT)
    urlpatterns += [
        path("__reload__/", include("django_browser_reload.urls")),
    ]