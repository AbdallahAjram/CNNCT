from django.core.management.base import BaseCommand
from a_rtchat.firebase_sync import import_all_chats_from_firestore


class Command(BaseCommand):
    help = "Backfill Firestore chats and last messages into local DB."

    def add_arguments(self, parser):
        parser.add_argument("--max", type=int, default=500, help="Max chats to scan")

    def handle(self, *args, **opts):
        import_all_chats_from_firestore(max_chats=opts["max"])
        self.stdout.write(self.style.SUCCESS("Firestore chats sync complete."))


