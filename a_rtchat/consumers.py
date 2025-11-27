from channels.generic.websocket import WebsocketConsumer
from django.shortcuts import get_object_or_404
from django.template.loader import render_to_string
from asgiref.sync import async_to_sync
from a_users.models import BlockedUser
from .models import ChatGroup, GroupMessages, HiddenMessage
import json
import threading
import logging
from a_rtchat.firebase_sync import (
    push_text_message_to_firestore,
    push_group_text_message_to_firestore,
    has_peer_read_message_for_private_chat,
)

logger = logging.getLogger(__name__)

class ChatroomConsumer(WebsocketConsumer):
    def connect(self):
        self.user = self.scope['user']
        self.chatroom_name = self.scope['url_route']['kwargs']['chatroom_name']
        self.chatroom = get_object_or_404(ChatGroup, group_name=self.chatroom_name)  

        async_to_sync(self.channel_layer.group_add)(
            self.chatroom_name, self.channel_name
        )

        #add and update users that are online
        if self.user not in self.chatroom.users_online.all():
            self.chatroom.users_online.add(self.user)
            self.update_online_count()
        self.accept()

        # Mark inbound messages as delivered now that the recipient is connected to this room
        self.mark_inbound_as_delivered()
    

    def disconnect(self, close_code):
        try:
            async_to_sync(self.channel_layer.group_discard)(
                self.chatroom_name, self.channel_name
            )
            #remove and update users
            if hasattr(self, 'user') and hasattr(self, 'chatroom') and self.user in self.chatroom.users_online.all():
                self.chatroom.users_online.remove(self.user)
                self.update_online_count()
        except Exception:
            logger.exception("Error during WebSocket disconnect")
        # Do not call accept() on disconnect

    def receive(self, text_data):
        data = json.loads(text_data or "{}")
        body = (data.get("body") or "").strip()
        if body:
            body = body[:25000]

        # Blocked check
        if self.chatroom.is_private:
            for member in self.chatroom.members.all():
                if member.id == self.user.id:
                    continue
                # recipient blocked me?
                if BlockedUser.objects.filter(blocker=member, blocked=self.user).exists():
                    return  # silently drop
                # I blocked the recipient?
                if BlockedUser.objects.filter(blocker=self.user, blocked=member).exists():
                    return

        # Persist locally
        message = GroupMessages.objects.create(
            body=body,
            author=self.user,
            group=self.chatroom,
        )
        if self.chatroom.is_private:
            others = self.chatroom.members.exclude(id=self.user.id)
            inbound_qs = GroupMessages.objects.filter(
                group=self.chatroom,
                author__in=others,
                status__lt=GroupMessages.STATUS_READ,
            )
            read_ids = list(inbound_qs.values_list("id", flat=True))
            if read_ids:
                GroupMessages.objects.filter(id__in=read_ids).update(
                    status=GroupMessages.STATUS_READ
                )
                for mid in read_ids:
                    async_to_sync(self.channel_layer.group_send)(
                        self.chatroom_name,
                        {
                            "type": "message_update_handler",
                            "message_id": mid,
                        },
                    )
        # 🔁 Mirror to Firestore (private + group) - non-blocking
        if body:
            sender_uid = getattr(getattr(self.user, "profile", None), "firebase_uid", None)
            if sender_uid:
                # Run Firestore push in background thread to avoid blocking WebSocket
                message_id = message.id
                chatroom_id = self.chatroom.id
                is_private = self.chatroom.is_private
                
                def firestore_push():
                    try:
                        # Re-fetch objects in thread to ensure proper DB connection
                        from .models import ChatGroup, GroupMessages
                        from channels.layers import get_channel_layer

                        chatroom = ChatGroup.objects.get(id=chatroom_id)
                        msg = GroupMessages.objects.get(id=message_id)
                        
                        if is_private:
                            mid = push_text_message_to_firestore(chatroom, sender_uid, body)
                        else:
                            mid = push_group_text_message_to_firestore(chatroom, sender_uid, body)

                        if mid and not msg.firebase_message_id:
                            msg.firebase_message_id = mid
                            msg.sender_uid = sender_uid
                            msg.save(update_fields=["firebase_message_id", "sender_uid"])

                            # 🔁 broadcast an update so ticks flip 1 → 2 grey without reload
                            try:
                                channel_layer = get_channel_layer()
                                async_to_sync(channel_layer.group_send)(
                                    chatroom.group_name,
                                    {
                                        "type": "message_update_handler",
                                        "message_id": msg.id,
                                    },
                                )
                            except Exception:
                                logger.exception("Firestore push broadcast failed")
                    except Exception:
                        logger.exception("Firestore push (WS) failed")


                thread = threading.Thread(target=firestore_push, daemon=True)
                thread.start()

        prev = (
            GroupMessages.objects
            .filter(group=self.chatroom, author=self.user)
            .exclude(id=message.id)
            .order_by('-created', '-id')
            .first()
        )

        same_run = False
        if prev and prev.created and message.created:
            try:
                delta = abs(message.created - prev.created)
                # match _annotate_runs window: 300 seconds
                same_run = delta.total_seconds() <= 300
            except Exception:
                same_run = False

        # If this continues the same run, hide footer on the previous message
        if same_run and prev:
            async_to_sync(self.channel_layer.group_send)(
                self.chatroom_name,
                {
                    'type': 'message_footer_update',
                    'message_id': prev.id,
                }
            )

        # Broadcast the new message (it will show the footer)
        event = {'type': 'message_handler', 'message_id': message.id}
        async_to_sync(self.channel_layer.group_send)(self.chatroom_name, event)

        # Mark delivered for online recipients
        for member in self.chatroom.members.all():
            if member.id == self.user.id:
                continue
            if member in self.chatroom.users_online.all():
                try:
                    if message.status < GroupMessages.STATUS_DELIVERED:
                        message.status = GroupMessages.STATUS_DELIVERED
                        message.save(update_fields=['status'])
                        upd = {'type': 'message_update_handler', 'message_id': message.id}
                        async_to_sync(self.channel_layer.group_send)(self.chatroom_name, upd)
                except GroupMessages.DoesNotExist:
                    pass


    def message_handler(self, event):
        message_id = event['message_id']

        # Skip if user hid this message (delete for me)
        if HiddenMessage.objects.filter(user=self.user, message_id=message_id).exists():
            return

        message = GroupMessages.objects.get(id=message_id)
        self._annotate_peer_read_flag(message)

        # New messages should always show footer/meta,
        # older ones in the run will be fixed by message_footer_update.
        message.show_meta = True
        message.show_avatar = True
        message.show_ticks = True

        context = {
            'message': message,
            'user': self.user,
        }
        html = render_to_string("a_rtchat/partials/chat_messages_p.html", context=context)
        self.send(text_data=html)
    
    def message_footer_update(self, event):
        message_id = event['message_id']
        message = GroupMessages.objects.get(id=message_id)
        self._annotate_peer_read_flag(message)

        # This message is no longer the end of the run → hide footer/meta
        message.show_meta = False
        message.show_avatar = False
        message.show_ticks = False

        context = {
            'message': message,
            'user': self.user,
        }
        html = render_to_string("a_rtchat/chat_message.html", context=context)
        self.send(text_data=html)

    def message_update_handler(self, event):
        message_id = event['message_id']

        # Skip if user hid this message
        if HiddenMessage.objects.filter(user=self.user, message_id=message_id).exists():
            return

        message = GroupMessages.objects.get(id=message_id)
        self._annotate_peer_read_flag(message)
        message.show_meta = True
        message.show_ticks = True
        message.show_avatar = (message.author_id != self.user.id)
        context = {
            'message': message,
            'user': self.user,
        }
        # Send single-item render to replace existing li via OOB swap
        html = render_to_string("a_rtchat/chat_message.html", context=context)
        self.send(text_data=html)
    
    def update_online_count(self):
        online_count = self.chatroom.users_online.count() -1

        event = {
            'type': 'online_count_handler',
            'online_count': online_count
        }
        async_to_sync(self.channel_layer.group_send)(self.chatroom_name, event)

    def online_count_handler(self, event):
        online_count = event['online_count']
        html= render_to_string("a_rtchat/partials/online_count.html", {'online_count' : online_count})
        self.send(text_data=html)

    def mark_inbound_as_delivered(self):
        inbound = GroupMessages.objects.filter(group=self.chatroom).exclude(author=self.user)
        updated = inbound.filter(status__lt=GroupMessages.STATUS_DELIVERED).update(status=GroupMessages.STATUS_DELIVERED)
        if updated:
            for mid in inbound.values_list('id', flat=True):
                upd = { 'type': 'message_update_handler', 'message_id': mid }
                async_to_sync(self.channel_layer.group_send)(self.chatroom_name, upd)

    def _get_other_private_member(self):
        if not getattr(self.chatroom, "is_private", False):
            return None
        if hasattr(self, "_other_member_cache"):
            return self._other_member_cache
        other = None
        for member in self.chatroom.members.all():
            if member != self.user:
                other = member
                break
        self._other_member_cache = other
        return other

    def _annotate_peer_read_flag(self, message):
        message.read_by_peer = False

        # PRIVATE CHAT LOGIC
        if getattr(self.chatroom, "is_private", False):
            if message.author_id != getattr(self.user, "id", None):
                return
            other = self._get_other_private_member()
            if not other:
                return
            try:
                message.read_by_peer = has_peer_read_message_for_private_chat(
                    self.chatroom,
                    message,
                    self.user,
                    other,
                )
            except Exception:
                message.read_by_peer = False
            return

        # GROUP CHAT LOGIC
        # Only show read ticks on *my* messages
        if message.author_id != getattr(self.user, "id", None):
            return

        try:
            from a_rtchat.firebase_sync import has_group_message_been_read
            message.read_by_peer = has_group_message_been_read(self.chatroom, message)
        except Exception:
            message.read_by_peer = False
