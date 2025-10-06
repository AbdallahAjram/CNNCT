from channels.generic.websocket import WebsocketConsumer
from django.shortcuts import get_object_or_404
from django.template.loader import render_to_string     
from .models import *
from a_users.models import BlockedUser
from asgiref.sync import async_to_sync
import json
from channels.generic.websocket import WebsocketConsumer
from django.shortcuts import get_object_or_404
from django.template.loader import render_to_string     
from .models import *
import json

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
        async_to_sync(self.channel_layer.group_discard)(
            self.chatroom_name, self.channel_name
        )
        #remove and update users
        if self.user in self.chatroom.users_online.all():
            self.chatroom.users_online.remove(self.user)
            self.update_online_count()
        # Do not call accept() on disconnect

    def receive(self, text_data):
        text_data_json = json.loads(text_data)
        body = text_data_json['body']
        # Enforce max length of 25,000 characters
        if body:
            body = body[:25000]

        # Prevent sending if any recipient has blocked the author
        if self.chatroom.is_private:
            for member in self.chatroom.members.all():
                if member.id == self.user.id:
                    continue
                # If recipient has blocked current user, do not deliver
                if BlockedUser.objects.filter(blocker=member, blocked=self.user).exists():
                    # Silently drop; optionally could send not-delivered notice to sender
                    return

        # Use the chatroom we fetched in connect()
        message = GroupMessages.objects.create(
            body=body,
            author=self.user,
            group=self.chatroom
        )

        context = {
            'message': message,
            'user': self.user,
        }
        html = render_to_string("a_rtchat/partials/chat_messages_p.html", context=context)

        event = {
            'type' : 'message_handler', 
            'message_id' : message.id,
        }

        async_to_sync(self.channel_layer.group_send)(
            self.chatroom_name, event
        )

        # Mark as delivered for other members currently connected (excluding author)
        for member in self.chatroom.members.all():
            if member.id == self.user.id:
                continue
            # If recipient is online in this room, update to delivered immediately
            if member in self.chatroom.users_online.all():
                try:
                    msg = GroupMessages.objects.get(id=message.id)
                    if msg.status < GroupMessages.STATUS_DELIVERED:
                        msg.status = GroupMessages.STATUS_DELIVERED
                        msg.save(update_fields=['status'])
                        # Push a message update to re-render ticks for author
                        upd = { 'type': 'message_update_handler', 'message_id': msg.id }
                        async_to_sync(self.channel_layer.group_send)(self.chatroom_name, upd)
                except GroupMessages.DoesNotExist:
                    pass

    def message_handler(self, event):
        message_id= event['message_id']
        message= GroupMessages.objects.get(id=message_id)
        context = {
            'message': message,
            'user' : self.user,
        }
        html = render_to_string("a_rtchat/partials/chat_messages_p.html",context=context)
        self.send(text_data=html)
    
    def message_update_handler(self, event):
        message_id = event['message_id']
        message = GroupMessages.objects.get(id=message_id)
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