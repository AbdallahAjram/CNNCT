from pathlib import Path
import logging
import os
from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent

env_path = BASE_DIR / ".env"
load_dotenv(dotenv_path=str(env_path), encoding="utf-8-sig")
SECRET_KEY = os.environ.get("DJANGO_SECRET_KEY")

DEBUG = True  

ALLOWED_HOSTS = ['localhost', '127.0.0.1', '*']

# Redis URL hardcoded or fallback
REDIS_URL = 'redis://user:user@localhost:6379'

INSTALLED_APPS = [
    'daphne',
    'django.contrib.admin',
    'django.contrib.auth',
    'django.contrib.contenttypes',
    'django.contrib.sessions',
    'django.contrib.messages',
    'django.contrib.staticfiles',
    'django_cleanup.apps.CleanupConfig',
    'django_htmx',
    'django.contrib.sites',
    'allauth',
    'allauth.account',
    'a_home',
    'a_users',
    "widget_tweaks",
    'django_browser_reload',
    'a_rtchat',
]

SITE_ID = 1

MIDDLEWARE = [
    'django.middleware.security.SecurityMiddleware',
    'django.contrib.sessions.middleware.SessionMiddleware',
    'django.middleware.common.CommonMiddleware',
    'django.middleware.csrf.CsrfViewMiddleware',
    'django.contrib.auth.middleware.AuthenticationMiddleware',
    'django.contrib.messages.middleware.MessageMiddleware',
    'django.middleware.clickjacking.XFrameOptionsMiddleware',
    'allauth.account.middleware.AccountMiddleware',
    'django_htmx.middleware.HtmxMiddleware',
]

if DEBUG:
    MIDDLEWARE += ['django_browser_reload.middleware.BrowserReloadMiddleware']

AUTHENTICATION_BACKENDS = [
    'a_users.auth_backends.FirebaseBackend',
    'django.contrib.auth.backends.ModelBackend',
    'allauth.account.auth_backends.AuthenticationBackend',
]

ROOT_URLCONF = 'a_core.urls'

TEMPLATES = [
    {
        'BACKEND': 'django.template.backends.django.DjangoTemplates',
        'DIRS': [BASE_DIR / 'templates'],
        'APP_DIRS': True,
        'OPTIONS': {
            'context_processors': [
                'django.template.context_processors.debug',
                'django.template.context_processors.request',
                'django.contrib.auth.context_processors.auth',
                'django.contrib.messages.context_processors.messages',
                'a_home.cprocs.project_title',
                'a_users.context_processors.user_profile_context',
            ],
        },
    },
]

ASGI_APPLICATION = 'a_core.asgi.application'

CHANNEL_LAYERS = {
    "default": {
        "BACKEND": "channels_redis.core.RedisChannelLayer",
        "CONFIG": {
            "hosts": [REDIS_URL],
        },
    },
}

DATABASES = {
    'default': {
        'ENGINE': 'django.db.backends.sqlite3',
        'NAME': BASE_DIR / 'db.sqlite3',
    }
}

AUTH_PASSWORD_VALIDATORS = [
    {'NAME': 'django.contrib.auth.password_validation.UserAttributeSimilarityValidator',},
    {'NAME': 'django.contrib.auth.password_validation.MinimumLengthValidator',},
    {'NAME': 'django.contrib.auth.password_validation.CommonPasswordValidator',},
    {'NAME': 'django.contrib.auth.password_validation.NumericPasswordValidator',},
]

LANGUAGE_CODE = 'en-us'
TIME_ZONE = 'Asia/Beirut'
USE_I18N = True
USE_TZ = True

STATIC_URL = 'static/'
STATICFILES_DIRS = [BASE_DIR / 'static']

MEDIA_URL = 'media/'
MEDIA_ROOT = BASE_DIR / 'media'

DEFAULT_AUTO_FIELD = 'django.db.models.BigAutoField'

LOGIN_URL = '/login/'
LOGIN_REDIRECT_URL = '/'

EMAIL_BACKEND = 'django.core.mail.backends.console.EmailBackend'

ACCOUNT_LOGIN_METHODS = {'email', 'username'}  # keep for allauth forms; backend adds phone
ACCOUNT_SIGNUP_FIELDS = ['email*', 'username*', 'password1*', 'password2*']
SESSION_ENGINE = "django.contrib.sessions.backends.db"
SESSION_COOKIE_SECURE = False   
SESSION_COOKIE_SAMESITE = "Lax"
SESSION_COOKIE_NAME = "sessionid"
CSRF_COOKIE_SECURE = False
CSRF_COOKIE_SAMESITE = "Lax"

# Presence/Online status settings
PRESENCE_ONLINE_WINDOW_SECONDS = 120  #2 minutes

#
OPENAI_API_KEY = os.environ.get("OPENAI_API_KEY")
SMART_REPLIES_MODEL = "gpt-4o-mini"