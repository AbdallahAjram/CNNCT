from django.core.management.base import BaseCommand
from django.contrib.auth import get_user_model
from firebase_admin import auth as admin_auth
from a_users.models import Profile

User = get_user_model()

class Command(BaseCommand):
    help = "Sync Firebase users with local Django users and remove stale ones."

    def handle(self, *args, **options):
        all_firebase_users = {}
        page = admin_auth.list_users()
        while page:
            for user in page.users:
                all_firebase_users[user.uid] = user.email
            page = page.get_next_page()

        self.stdout.write(f"✅ Found {len(all_firebase_users)} Firebase users")

        # Remove local users not in Firebase
        for profile in Profile.objects.all():
            if not profile.firebase_uid or profile.firebase_uid not in all_firebase_users:
                self.stdout.write(f"❌ Removing local user {profile.user.username}")
                profile.user.delete()
