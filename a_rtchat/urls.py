# a_rtchat/urls.py
from django.urls import path
from . import views
from .views import *
from a_rtchat.views import chat_archived_index, chat_archive_toggle


urlpatterns = [
    path('', chat_index, name="home"),
    path('chat/archived/', chat_archived_index, name="chat-archived"),
    path('chat/room/<chatroom_name>/', chat_view, name="chatroom"),
    # Group settings (admin-only)
    path('chat/group/<chatroom_name>/settings', group_settings_modal, name='group-settings'),
    path('chat/group/<chatroom_name>/update', group_update_meta, name='group-update'),
    path('chat/group/<chatroom_name>/members', group_update_members, name='group-update-members'),
    # Specific paths must come before the catch-all username path
    path('chat/new_groupchat/', create_groupchat, name="new-groupchat"),
    # Alias for HTMX form target
    path('chat/group/create', create_groupchat, name='create_groupchat'),
    path('chat/search/', chat_user_search, name="chat_user_search"),
    path('chat/start/', chat_start_new, name="chat-start-new"),
    path('chat/edit/<chatroom_name>/', chatroom_edit_view, name="edit-chatroom"),
    path('chat/delete/<chatroom_name>/', chatroom_delete_view, name="chatroom-delete"),
    path('chat/leave/<chatroom_name>/', chatroom_leave_view, name="chatroom-leave"),
    path('chat/clear/<chatroom_name>/', views.chat_clear, name="chatroom-clear"),
    path('chat/message/<int:message_id>/delete/', message_delete, name="message-delete"),
    path('chat/message/<int:message_id>/hide/', views.message_hide_for_me, name="message-hide-me"),
    path('chat/messages/hide-bulk/', views.messages_hide_bulk, name="messages-hide-bulk"),
    path('chat/group/inline', views.group_create_inline, name='group_create_inline'),
    path('chat/user-search-pickable', views.chat_user_search_pickable, name='chat_user_search_pickable'),
    path('chat/message/<int:message_id>/edit/', message_edit, name="message-edit"),
    path('chat/messages/delete/', messages_delete_bulk, name="messages-delete-bulk"),
    path(
        'chat/<slug:chatroom_name>/smart-replies/',
        views.generate_smart_replies,
        name="chat-smart-replies",
    ),
    path('chat/<str:chatroom_name>/archive-toggle/', chat_archive_toggle, name="chat-archive-toggle"),
    path('presence/<username>/', presence_badge, name='presence-badge'),
    path('presence/ping/', views.presence_ping, name='presence-ping'),
    # Catch-all must be last so it doesn't shadow edit/delete/leave
    path('chat/<username>/', get_or_create_chatroom, name="start-chat"),
]
