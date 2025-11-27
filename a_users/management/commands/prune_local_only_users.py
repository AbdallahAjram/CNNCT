from django.core.management.base import BaseCommand
from django.contrib.auth import get_user_model
from django.db.models import Q
from a_users.models import Profile

class Command(BaseCommand):
    help = "Deactivate or delete users without Firebase UID (no messages/usage)."

    def add_arguments(self, parser):
        parser.add_argument('--delete', action='store_true', help='Permanently delete instead of deactivate')
        parser.add_argument('--dry-run', action='store_true', help='Only print what would happen')

    def handle(self, *args, **opts):
        User = get_user_model()
        locals_qs = User.objects.filter(profile__firebase_uid__isnull=True)

        # If you have message models, exclude anyone with activity:
        # from a_rtchat.models import Message
        # locals_qs = locals_qs.exclude(id__in=Message.objects.values('author_id').distinct())

        count = 0
        for user in locals_qs.select_related('profile'):
            if opts['dry_run']:
                self.stdout.write(f"[DRY] would {'DELETE' if opts['delete'] else 'DEACTIVATE'} {user.username}")
                continue
            if opts['delete']:
                user.delete()
                self.stdout.write(f"deleted {user.username}")
            else:
                if user.is_active:
                    user.is_active = False
                    user.save(update_fields=['is_active'])
                self.stdout.write(f"deactivated {user.username}")
            count += 1
        self.stdout.write(self.style.SUCCESS(f"Processed {count} users"))
