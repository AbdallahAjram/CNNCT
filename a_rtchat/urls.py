# a_rtchat/urls.py
from django.urls import path

from .views import *


urlpatterns = [
    path('', chat_index, name="home"),
    path('chat/room/<chatroom_name>/',chat_view, name="chatroom"),
    # Specific paths must come before the catch-all username path
    path('chat/new_groupchat/',create_groupchat, name="new-groupchat"),
    path('chat/search/', chat_user_search, name="chat-user-search"),
    path('chat/start/', chat_start_new, name="chat-start-new"),
    path('chat/<username>/',get_or_create_chatroom, name="start-chat"),
    path('chat/edit/<chatroom_name>/',chatroom_edit_view,name="edit-chatroom"),
    path('chat/delete/<chatroom_name>/', chatroom_delete_view, name="chatroom-delete"),
    path('chat/leave/<chatroom_name>/', chatroom_leave_view, name="chatroom-leave"),
    path('chat/message/<int:message_id>/delete/', message_delete, name="message-delete"),
    path('chat/message/<int:message_id>/edit/', message_edit, name="message-edit"),
    path('chat/messages/delete/', messages_delete_bulk, name="messages-delete-bulk"),
]