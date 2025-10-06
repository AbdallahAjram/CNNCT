# a_rtchat/views.py
from django.shortcuts import render, get_object_or_404, redirect
from django.http import JsonResponse, Http404
from django.contrib.auth.decorators import login_required
from django.contrib import messages
from .models import *
from django.utils import timezone
from .forms import *
from django.views.decorators.http import require_http_methods
from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer
from datetime import datetime, timezone as dt_timezone
from django.contrib.auth.models import User
from django.db.models import Q
from django.views.decorators.csrf import csrf_exempt
from a_users.models import BlockedUser
@login_required
def chat_view(request, chatroom_name='public-chat'):
    chat_group=get_object_or_404(ChatGroup,group_name=chatroom_name)
    # Fetch latest 30 messages then reverse to render oldest -> newest
    latest_thirty = chat_group.chat_messages.order_by('-created', '-id')[:30]
    chat_messages = list(latest_thirty)[::-1]
    form=ChatmessageCreateForm()

    other_user= None
    other_user_online = False
    if chat_group.is_private:
        if request.user not in chat_group.members.all():
            raise Http404()
        for member in chat_group.members.all():
            if member != request.user:
                other_user = member
                other_user_online = chat_group.users_online.filter(id=other_user.id).exists()
                break
        # If current user has blocked the other, do not show the room
        if other_user and BlockedUser.objects.filter(blocker=request.user, blocked=other_user).exists():
            return redirect('home')

    if chat_group.groupchat_name:
        if request.user not in chat_group.members.all():
            if request.user.emailaddress_set.filter(verified=True).exists():
                chat_group.members.add(request.user)
            else:
                messages.warning(request,'You need to verify your email to join the chat!')
                return redirect('profile-settings')
    


    if request.htmx:
        form= ChatmessageCreateForm(request.POST)
        if form.is_valid:
            message = form.save(commit=False)
            message.author=request.user
            message.group= chat_group
            # Block sending if recipient has blocked the author
            if chat_group.is_private and other_user:
                if BlockedUser.objects.filter(blocker=other_user, blocked=request.user).exists():
                    return JsonResponse({'ok': False, 'error': 'blocked'}, status=403)
            message.save()
            context={
                'message':message,
                'user':request.user 
            }
        return render (request,'a_rtchat/partials/chat_messages_p.html',context) 
    
    # Build de-duplicated sidebar lists
    group_chats = request.user.chat_groups.filter(groupchat_name__isnull=False)
    private_chats_qs = request.user.chat_groups.filter(is_private=True)
    unique_private = {}
    for room in private_chats_qs:
        other = None
        for member in room.members.all():
            if member != request.user:
                other = member
                break
        if other:
            # keep the first room per other user
            unique_private.setdefault(other.id, (room, other))

    sidebar_private_chats = list(unique_private.values())  # list of tuples (room, other_user)

    # Mark this chat as read for the current user (create state if it doesn't exist)
    if request.user.is_authenticated:
        from .models import ChatReadState
        read_state, _ = ChatReadState.objects.get_or_create(user=request.user, group=chat_group)
        read_state.last_read_at = timezone.now()
        read_state.hidden = False  # opening the chat should unhide it
        read_state.save(update_fields=['last_read_at'])
        read_state.save(update_fields=['last_read_at','hidden'])

        # Mark inbound messages as READ on opening the chat
        inbound_qs = GroupMessages.objects.filter(group=chat_group).exclude(author=request.user)
        updated_ids = list(inbound_qs.filter(status__lt=GroupMessages.STATUS_READ).values_list('id', flat=True))
        if updated_ids:
            GroupMessages.objects.filter(id__in=updated_ids).update(status=GroupMessages.STATUS_READ)
            # Broadcast updates to re-render ticks in realtime for sender
            channel_layer = get_channel_layer()
            for mid in updated_ids:
                event = {
                    'type': 'message_update_handler',
                    'message_id': mid,
                    'chatroom_name': chat_group.group_name,
                }
                async_to_sync(channel_layer.group_send)(chat_group.group_name, event)

    # Compute unread counts and request flags for sidebar rooms
    from django.db.models import Max
    target_groups = list({g.id for g in group_chats} | {t[0].id for t in sidebar_private_chats})
    read_states = ChatReadState.objects.filter(user=request.user, group_id__in=target_groups)
    state_map = {rs.group_id: rs.last_read_at for rs in read_states}
    hidden_map = {rs.group_id: rs.hidden for rs in read_states}

    # Attach unread_count and latest inbound timestamp to each group chat object
    for g in group_chats:
        last_read = state_map.get(g.id)
        qs = GroupMessages.objects.filter(group=g).exclude(author=request.user)
        if last_read:
            qs = qs.filter(created__gt=last_read)
        g.unread_count = qs.count()
        latest_inbound = GroupMessages.objects.filter(group=g).exclude(author=request.user).order_by('-created').values_list('created', flat=True).first()
        g.latest_inbound_at = latest_inbound

    # Enrich private chats tuples with unread_count and request flag (no last_read yet and has inbound msgs)
    enriched_private = []
    for room, other in sidebar_private_chats:
        # Exclude rooms where current user has blocked the other user
        if BlockedUser.objects.filter(blocker=request.user, blocked=other).exists():
            continue
        last_read = state_map.get(room.id)
        is_hidden = hidden_map.get(room.id, False)
        inbound_qs = GroupMessages.objects.filter(group=room).exclude(author=request.user)
        unread_qs = inbound_qs
        if last_read:
            unread_qs = inbound_qs.filter(created__gt=last_read)
        unread_count = unread_qs.count()
        is_request = (last_read is None and inbound_qs.exists())
        latest_inbound = inbound_qs.order_by('-created').values_list('created', flat=True).first()
        # If hidden, only show if there are unread inbound messages
        if is_hidden and unread_count == 0:
            continue
        enriched_private.append((room, other, unread_count, is_request, latest_inbound))
    # Combine group and private chats and sort by most recent inbound timestamp
    def ts_or_min(ts):
        return ts or datetime(1970, 1, 1, tzinfo=dt_timezone.utc)
    group_chats = list(group_chats)
    combined = []
    for g in group_chats:
        combined.append({
            'kind': 'group',
            'group': g,
            'other': None,
            'unread': getattr(g, 'unread_count', 0),
            'is_request': False,
            'latest': getattr(g, 'latest_inbound_at', None),
        })
    for room, other, unread_count, is_request, latest_inbound in enriched_private:
        combined.append({
            'kind': 'private',
            'group': room,
            'other': other,
            'unread': unread_count,
            'is_request': is_request,
            'latest': latest_inbound,
        })
    combined.sort(key=lambda x: ts_or_min(x['latest']), reverse=True)

    context = {
        'chat_messages':chat_messages,
        'form': form,
        'other_user': other_user,
        'other_user_online': other_user_online,
        'chatroom_name': chatroom_name,
        'chat_group': chat_group,
        'sidebar_chats': combined,
    }
    return render(request,'a_rtchat/chat.html',context)


@login_required
def chat_index(request):
    """Render the chat UI with no room selected (blank state)."""
    # Build de-duplicated sidebar lists (same as chat_view but without a selected room)
    group_chats = request.user.chat_groups.filter(groupchat_name__isnull=False)
    private_chats_qs = request.user.chat_groups.filter(is_private=True)

    unique_private = {}
    for room in private_chats_qs:
        other = None
        for member in room.members.all():
            if member != request.user:
                other = member
                break
        if other:
            unique_private.setdefault(other.id, (room, other))

    sidebar_private_chats = list(unique_private.values())

    # Compute unread counts and request flags for sidebar rooms
    from django.db.models import Max
    from .models import ChatReadState, GroupMessages
    target_groups = list({g.id for g in group_chats} | {t[0].id for t in sidebar_private_chats})
    read_states = ChatReadState.objects.filter(user=request.user, group_id__in=target_groups)
    state_map = {rs.group_id: rs.last_read_at for rs in read_states}
    hidden_map = {rs.group_id: rs.hidden for rs in read_states}

    for g in group_chats:
        last_read = state_map.get(g.id)
        qs = GroupMessages.objects.filter(group=g).exclude(author=request.user)
        if last_read:
            qs = qs.filter(created__gt=last_read)
        g.unread_count = qs.count()
        latest_inbound = GroupMessages.objects.filter(group=g).exclude(author=request.user).order_by('-created').values_list('created', flat=True).first()
        g.latest_inbound_at = latest_inbound

    enriched_private = []
    for room, other in sidebar_private_chats:
        # Exclude rooms where current user has blocked the other user
        try:
            from a_users.models import BlockedUser as BU
            if BU.objects.filter(blocker=request.user, blocked=other).exists():
                continue
        except Exception:
            pass
        last_read = state_map.get(room.id)
        is_hidden = hidden_map.get(room.id, False)
        inbound_qs = GroupMessages.objects.filter(group=room).exclude(author=request.user)
        unread_qs = inbound_qs
        if last_read:
            unread_qs = inbound_qs.filter(created__gt=last_read)
        unread_count = unread_qs.count()
        is_request = (last_read is None and inbound_qs.exists())
        latest_inbound = inbound_qs.order_by('-created').values_list('created', flat=True).first()
        if is_hidden and unread_count == 0:
            continue
        enriched_private.append((room, other, unread_count, is_request, latest_inbound))

    from datetime import datetime, timezone as dt_timezone
    def ts_or_min(ts):
        return ts or datetime(1970, 1, 1, tzinfo=dt_timezone.utc)
    sidebar_private_chats = sorted(
        enriched_private,
        key=lambda t: ((t[2] > 0), ts_or_min(t[4])),
        reverse=True,
    )

    group_chats = list(group_chats)
    group_chats.sort(key=lambda g: ((getattr(g, 'unread_count', 0) > 0), ts_or_min(getattr(g, 'latest_inbound_at', None))), reverse=True)

    context = {
        'chat_messages': [],
        'form': None,
        'other_user': None,
        'chatroom_name': None,
        'chat_group': None,
        'sidebar_group_chats': group_chats,
        'sidebar_private_chats': sidebar_private_chats,
    }
    return render(request, 'a_rtchat/chat.html', context)

@login_required
def get_or_create_chatroom(request, username):
    if request.user.username ==username:
        return redirect('home')
    
    other_user = User.objects.get(username=username)
    # Find an existing private room shared between the two users
    chatroom = (
        ChatGroup.objects.filter(is_private=True, members=request.user)
        .filter(members=other_user)
        .first()
    )
    if not chatroom:
        chatroom = ChatGroup.objects.create(is_private=True)
        chatroom.members.add(other_user, request.user)

    return redirect('chatroom', chatroom.group_name)

@login_required 
def create_groupchat(request):
    form = NewGroupForm()
    
    if request.method =='POST':
        form= NewGroupForm(request.POST)
        if form.is_valid(): 
            new_groupchat = form.save(commit=False)
            new_groupchat.admin=request.user
            new_groupchat.save()
            new_groupchat.members.add(request.user)
            return redirect('chatroom',new_groupchat.group_name)
    context={
        'form':form
    }

        
    return render(request,'a_rtchat/create_groupchat.html',context)
@login_required
def chatroom_edit_view(request,chatroom_name):
    chat_group=get_object_or_404(ChatGroup,group_name=chatroom_name )
    if request.user != chat_group.admin:
        raise Http404()
    form= ChatRoomEditForm(instance=chat_group)

    if request.method == 'POST':
        form = ChatRoomEditForm(request.POST, instance=chat_group)
        if form.is_valid():
            form.save()

            remove_members = request.POST.getlist('remove_members')
            for member_id in remove_members:
                member= User.objects.get(id=member_id)
                chat_group.members.remove(member)

            return redirect('chatroom', chatroom_name)
    context={
        'form':form,
        'chat_group':chat_group

    }

    return render(request, 'a_rtchat/chatroom_edit.html', context)



@login_required
def chatroom_delete_view(request, chatroom_name):
    chat_group = get_object_or_404(ChatGroup, group_name= chatroom_name)
    if request.user != chat_group.admin:
        raise Http404()

    if request.method == "POST":
        chat_group.delete()
        messages.success(request, 'Chatroom deleted')
        return redirect('home')
    

    return render(request, 'a_rtchat/chatroom_delete.html', {'chat_group':chat_group})
@login_required
@login_required
def chatroom_leave_view(request, chatroom_name):
    chat_group = get_object_or_404(ChatGroup, group_name=chatroom_name)

    if request.user not in chat_group.members.all():
        raise Http404()

    if request.method == "POST":
        # For private chats, do not remove membership to preserve future inbound visibility
        if chat_group.is_private:
            from .models import ChatReadState
            rs, _ = ChatReadState.objects.get_or_create(user=request.user, group=chat_group)
            rs.hidden = True
            rs.save(update_fields=['hidden'])
            messages.success(request, 'Conversation hidden. New messages will unhide it.')
        else:
            chat_group.members.remove(request.user)
            messages.success(request, 'You left the Chat')
        return redirect('home')

    # For GET requests, redirect to the chatroom or home, or show a page
    return redirect('chatroom', chatroom_name)

    
@login_required
@require_http_methods(["POST"]) 
def message_delete(request, message_id):
    message = get_object_or_404(GroupMessages, id=message_id)
    if message.author != request.user:
        raise Http404()
    message.is_deleted = True
    message.body = message.body  # no-op to keep body; still stored but hidden
    message.save(update_fields=["is_deleted", "body"])

    # Broadcast updated rendering to the room via channels
    channel_layer = get_channel_layer()
    event = {
        'type': 'message_update_handler',
        'message_id': message.id,
        'chatroom_name': message.group.group_name,
    }
    async_to_sync(channel_layer.group_send)(message.group.group_name, event)

    context = { 'message': message, 'user': request.user }
    return render(request, 'a_rtchat/chat_message.html', context)


@login_required
def chat_user_search(request):
    """HTMX endpoint: search users by username or phone number (if present)."""
    query = (request.GET.get('q') or '').strip()
    results = []
    if query:
        # Basic username search
        qs = User.objects.filter(Q(username__iexact=query) | Q(username__icontains=query))[:10]
        # If phone field exists on Profile or User, include it safely
        try:
            # Normalize digits for phone compare
            digits = ''.join(ch for ch in query if ch.isdigit())
            qs = User.objects.filter(
                Q(username__iexact=query) | Q(username__icontains=query) |
                Q(profile__displayname__icontains=query) |
                Q(profile__phone=digits)
            )[:10]
        except Exception:
            pass
        results = list(qs)
        # Do not show self in results
        results = [u for u in results if u.id != request.user.id]
    context = { 'results': results, 'query': query }
    return render(request, 'a_rtchat/partials/user_search_results.html', context)

@login_required
@require_http_methods(["POST"]) 
def chat_start_new(request):
    """Create or get a private chat with a user by username or phone input."""
    raw = (request.POST.get('q') or '').strip()
    if not raw:
        return JsonResponse({'ok': False, 'error': 'empty'}, status=400)
    # Resolve to a user
    candidate = None
    # Prefer exact username
    try:
        candidate = User.objects.get(username__iexact=raw)
    except User.DoesNotExist:
        # try displayname unique? fallback to contains first match
        user_qs = User.objects.filter(Q(username__icontains=raw) | Q(profile__displayname__icontains=raw))
        candidate = user_qs.exclude(id=request.user.id).first()
    if not candidate or candidate.id == request.user.id:
        # Return friendly partial with not found message
        return render(request, 'a_rtchat/partials/user_search_results.html', { 'results': [], 'query': raw })

    # Reuse existing room or create
    chatroom = (
        ChatGroup.objects.filter(is_private=True, members=request.user)
        .filter(members=candidate)
        .first()
    )
    if not chatroom:
        chatroom = ChatGroup.objects.create(is_private=True)
        chatroom.members.add(candidate, request.user)

    return redirect('chatroom', chatroom.group_name)

@login_required
@require_http_methods(["POST"]) 
def messages_delete_bulk(request):
    ids = request.POST.getlist('ids[]') or request.POST.getlist('ids')
    # normalize to ints and drop invalids
    try:
        id_ints = [int(x) for x in ids]
    except Exception:
        return JsonResponse({'ok': False, 'error': 'bad_ids'}, status=400)
    if not id_ints:
        return JsonResponse({'ok': False, 'error': 'empty'}, status=400)
    # Only allow deleting own messages
    messages_qs = GroupMessages.objects.filter(id__in=id_ints, author=request.user)
    if not messages_qs.exists():
        return JsonResponse({'ok': False, 'error': 'none_owned'}, status=403)
    # Mark as deleted
    messages_qs.update(is_deleted=True)
    # Broadcast updates to re-render each message
    channel_layer = get_channel_layer()
    for mid, gid in messages_qs.values_list('id', 'group__group_name'):
        event = {
            'type': 'message_update_handler',
            'message_id': mid,
            'chatroom_name': gid,
        }
        async_to_sync(channel_layer.group_send)(gid, event)
    return JsonResponse({'ok': True, 'count': messages_qs.count()})
@login_required
@require_http_methods(["POST"]) 
def message_edit(request, message_id):
    message = get_object_or_404(GroupMessages, id=message_id)
    if message.author != request.user:
        raise Http404()
    if message.is_deleted:
        return JsonResponse({'ok': False, 'error': 'deleted'}, status=400)
    new_body = (request.POST.get('body') or '').strip()
    if new_body:
        message.body = new_body[:25000]
        message.edited = True
        message.edited_at = timezone.now()
        message.save(update_fields=["body", "edited", "edited_at"]) 

        channel_layer = get_channel_layer()
        event = {
            'type': 'message_update_handler',
            'message_id': message.id,
            'chatroom_name': message.group.group_name,
        }
        async_to_sync(channel_layer.group_send)(message.group.group_name, event)

    context = { 'message': message, 'user': request.user }
    return render(request, 'a_rtchat/chat_message.html', context)