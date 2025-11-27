import json
import re

# a_rtchat/views.py
from django.shortcuts import render, get_object_or_404, redirect
from django.http import JsonResponse, Http404, HttpResponseForbidden
from django.contrib.auth.decorators import login_required
from django.contrib import messages
from django.utils import timezone
from django.views.decorators.http import require_http_methods, require_POST
from django.views.decorators.csrf import csrf_exempt
from django.contrib.auth.models import User
from django.contrib.auth import get_user_model
from django.db.models import Q
from django.urls import reverse
from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer
from datetime import datetime, timezone as dt_timezone
from datetime import timedelta
from django.conf import settings
from django.core.files.uploadedfile import UploadedFile
from openai import OpenAI

from .models import ChatGroup, GroupMessages, ChatReadState, HiddenMessage
from .forms import ChatmessageCreateForm, NewGroupForm, ChatRoomEditForm
from a_users.models import BlockedUser
from a_users.sync import ensure_user_from_firebase_by_phone

# Firestore sync helpers (private-only path; backfill only)
from a_rtchat.firebase_sync import (
    ensure_firestore_private_chat_for_group,
    import_messages_for_group_from_firestore,
    push_text_message_to_firestore,
    ensure_firestore_group_chat_for_group,
    push_group_text_message_to_firestore,
    _uids_for_group_members,
    update_firestore_group_doc,
    remove_user_from_group_chat_in_firestore,
    add_users_to_group_chat_in_firestore,
)
from a_core.firebase_admin_client import get_bucket

ONLINE_WINDOW = getattr(settings, "PRESENCE_ONLINE_WINDOW_SECONDS", 180)

def _is_online_from_last_seen(dt):
    if not dt:
        return False
    try:
        if timezone.is_naive(dt):
            dt = timezone.make_aware(dt, timezone.get_default_timezone())
    except Exception:
        pass
    try:
        return (timezone.now() - dt) <= timedelta(seconds=ONLINE_WINDOW)
    except Exception:
        return False


# --- helper: last-message preview ---
def _chat_preview_text(group, maxlen=60):
    """
    Return a short preview of the most recent message in this group.
    Prefers local DB messages (which include imported Firestore messages).
    """
    last = (
        GroupMessages.objects
        .filter(group=group)
        .order_by('-created', '-id')
        .values('body', 'is_deleted')
        .first()
    )
    if not last:
        return ""

    if last['is_deleted']:
        txt = ""  # or "Deleted message"
    else:
        txt = (last['body'] or "").strip()

    if not txt:
        return ""
    return (txt[:maxlen] + "…") if len(txt) > maxlen else txt


def _build_sidebar_chats(user, *, show_archived: bool = False):
    """
    Build sidebar list of chats for `user`.

    - show_archived = False → normal sidebar (exclude archived)
    - show_archived = True  → archived sidebar (only archived)
    """
    group_chats_qs = user.chat_groups.filter(groupchat_name__isnull=False)
    private_chats_qs = user.chat_groups.filter(is_private=True)

    unique_private = {}
    for room in private_chats_qs:
        other = None
        for member in room.members.all():
            if member != user:
                other = member
                break
        if other:
            unique_private.setdefault(other.id, (room, other))
    sidebar_private_chats = list(unique_private.values())

    group_chats = list(group_chats_qs)
    target_groups = list({g.id for g in group_chats} | {t[0].id for t in sidebar_private_chats})
    read_states = ChatReadState.objects.filter(user=user, group_id__in=target_groups)
    state_map = {rs.group_id: rs.last_read_at for rs in read_states}
    hidden_map = {rs.group_id: rs.hidden for rs in read_states}
    archived_map = {rs.group_id: rs.archived for rs in read_states}

    def include_by_archive(group_id):
        is_archived = archived_map.get(group_id, False)
        return is_archived if show_archived else not is_archived

    filtered_group_chats = []
    for g in group_chats:
        if not include_by_archive(g.id):
            continue
        last_read = state_map.get(g.id)
        qs = GroupMessages.objects.filter(group=g).exclude(author=user)
        if last_read:
            qs = qs.filter(created__gt=last_read)
        g.unread_count = qs.count()
        latest_inbound = (
            GroupMessages.objects
            .filter(group=g)
            .exclude(author=user)
            .order_by('-created')
            .values_list('created', flat=True)
            .first()
        )
        g.latest_inbound_at = latest_inbound
        filtered_group_chats.append(g)

    enriched_private = []
    for room, other in sidebar_private_chats:
        try:
            if (
                BlockedUser.objects.filter(blocker=user, blocked=other).exists()
                or BlockedUser.objects.filter(blocker=other, blocked=user).exists()
            ):
                continue
        except Exception:
            pass
        if not getattr(other.profile, "firebase_uid", None):
            continue
        if not include_by_archive(room.id):
            continue
        last_read = state_map.get(room.id)
        is_hidden = hidden_map.get(room.id, False)
        inbound_qs = GroupMessages.objects.filter(group=room).exclude(author=user)
        unread_qs = inbound_qs if not last_read else inbound_qs.filter(created__gt=last_read)
        unread_count = unread_qs.count()
        is_request = (last_read is None and inbound_qs.exists())
        latest_inbound = inbound_qs.order_by('-created').values_list('created', flat=True).first()
        if not show_archived and is_hidden and unread_count == 0:
            continue
        enriched_private.append((room, other, unread_count, is_request, latest_inbound))

    def ts_or_min(ts):
        return ts or datetime(1970, 1, 1, tzinfo=dt_timezone.utc)

    combined = []
    for g in filtered_group_chats:
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

    for item in combined:
        g = item['group']
        item['preview'] = _chat_preview_text(g) or ("Group chat" if item['kind'] == 'group' else "")

    return combined


@login_required
def chat_view(request, chatroom_name='public-chat'):
    chat_group = get_object_or_404(ChatGroup, group_name=chatroom_name)
    form = ChatmessageCreateForm()

    # NEW: are we in "archived sidebar" mode?
    archived_only = (request.GET.get("archived") == "1")

    other_user = None
    other_user_online = False
    if chat_group.is_private:
        if request.user not in chat_group.members.all():
            raise Http404()
        for member in chat_group.members.all():
            if member != request.user:
                other_user = member
                prof = getattr(other_user, "profile", None)
                last_seen = getattr(prof, "last_online_at", None)
                try:
                    uid = getattr(prof, "firebase_uid", None)
                    if uid:
                        from a_users.firebase_helpers import get_db
                        from a_rtchat.firebase_sync import get_profile_dict

                        db = get_db()
                        data = get_profile_dict(db, uid)
                        if data:
                            fs_ts = data.get("lastOnlineAt") or data.get("lastSeenAt")
                            if fs_ts:
                                last_seen = fs_ts
                except Exception:
                    pass

                other_user_online = _is_online_from_last_seen(last_seen)
                break
        if other_user and BlockedUser.objects.filter(blocker=request.user, blocked=other_user).exists():
            return redirect('home')

    # Firestore backfill (private + group)
    try:
        if chat_group.is_private:
            import_messages_for_group_from_firestore(chat_group, limit=300)
        else:
            if chat_group.firebase_chat_id:
                from a_rtchat.firebase_sync import import_group_messages_from_firestore
                import_group_messages_from_firestore(chat_group, limit=300)
    except Exception as e:
        print("⚠️ Firestore backfill failed:", e)

    # Web presence bump so mobile & web see user online
    try:
        prof = getattr(request.user, "profile", None)
        now = timezone.now()

        if prof and hasattr(prof, "last_online_at"):
            prof.last_online_at = now
            prof.save(update_fields=["last_online_at"])

        uid = getattr(prof, "firebase_uid", None)
        if uid:
            from a_rtchat.firebase_sync import update_user_last_online_in_firestore
            update_user_last_online_in_firestore(uid)
    except Exception as e:
        print("⚠️ lastOnlineAt web bump failed:", e, flush=True)

    # Local fetch, excluding per-user hidden messages, then annotate runs
    try:
        hidden_ids = HiddenMessage.objects.filter(
            user=request.user,
            message__group=chat_group
        ).values_list("message_id", flat=True)
    except Exception:
        # Table doesn't exist yet (migration not run) - treat as no hidden messages
        hidden_ids = []

    latest_thirty = (
        chat_group.chat_messages
        .exclude(id__in=hidden_ids)
        .order_by('-created', '-id')[:30]
    )
    chat_messages = list(latest_thirty)[::-1]
    chat_messages = _annotate_runs(chat_messages)

    # --- Read receipts (private chat; Firestore-backed) ---
    peer_last_read_mid = None
    if chat_group.is_private and other_user:
        try:
            from a_rtchat.firebase_sync import (
                get_private_chat_peer_last_read_mid,
                mark_last_message_read_in_firestore_for_group,
            )

            peer_last_read_mid = get_private_chat_peer_last_read_mid(
                chat_group,
                request.user,
                other_user,
            )

            my_uid = getattr(getattr(request.user, "profile", None), "firebase_uid", None)
            if my_uid:
                mark_last_message_read_in_firestore_for_group(chat_group, my_uid)

        except Exception as e:
            print("⚠️ Firestore read-receipt sync failed in chat_view:", e, flush=True)

    # --- peer read receipts ---
    if chat_group.is_private:
        # PRIVATE CHAT LOGIC (unchanged)
        last_read_index = None
        if peer_last_read_mid:
            for idx, msg in enumerate(chat_messages):
                if getattr(msg, "firebase_message_id", None) == peer_last_read_mid:
                    last_read_index = idx
                    break

        implicit_last_read_index = None
        for idx, msg in enumerate(chat_messages):
            if msg.author_id != request.user.id:
                implicit_last_read_index = idx

        effective_last_read_index = None
        for candidate in (last_read_index, implicit_last_read_index):
            if candidate is None:
                continue
            if effective_last_read_index is None or candidate > effective_last_read_index:
                effective_last_read_index = candidate

        logical_read_indices = set()
        for idx, m in enumerate(chat_messages):
            m.is_mine = (m.author_id == request.user.id)
            m.double_tick = bool(getattr(m, "firebase_message_id", None)) and m.is_mine

            is_logically_read = (
                m.is_mine
                and m.double_tick
                and effective_last_read_index is not None
                and idx <= effective_last_read_index
            )
            if is_logically_read:
                logical_read_indices.add(idx)

        ui_last_read_index = None
        if logical_read_indices:
            ui_last_read_index = max(logical_read_indices)

        for idx, m in enumerate(chat_messages):
            m.read_by_peer = idx in logical_read_indices
            m.read_by_peer_ui = ui_last_read_index is not None and idx == ui_last_read_index
    else:
        # GROUP CHAT READ RECEIPTS
        from a_rtchat.firebase_sync import has_group_message_been_read

        for m in chat_messages:
            m.is_mine = (m.author_id == request.user.id)
            m.double_tick = bool(getattr(m, "firebase_message_id", None)) and m.is_mine

            if m.is_mine and m.double_tick:
                try:
                    m.read_by_peer = has_group_message_been_read(chat_group, m)
                except Exception:
                    m.read_by_peer = False
            else:
                m.read_by_peer = False

            # For now we don't highlight a "last read" marker in groups
            m.read_by_peer_ui = False

    # Joining a group must be explicit (no auto-join).
    if chat_group.groupchat_name and request.GET.get("join") == "1":
        if request.user not in chat_group.members.all():
            if request.user.emailaddress_set.filter(verified=True).exists():
                chat_group.members.add(request.user)
                messages.success(request, 'Joined the chat.')
            else:
                messages.warning(request, 'You need to verify your email to join the chat!')
                return redirect('profile-settings')

    # HTMX send
    if request.htmx:
        form = ChatmessageCreateForm(request.POST)
    if form.is_valid():
        message = form.save(commit=False)
        message.author = request.user
        message.group = chat_group

        # ---- Private chat path ----
        if chat_group.is_private:
            if other_user:
                if BlockedUser.objects.filter(blocker=other_user, blocked=request.user).exists():
                    return JsonResponse({'ok': False, 'error': 'blocked'}, status=403)
                if BlockedUser.objects.filter(blocker=request.user, blocked=other_user).exists():
                    return JsonResponse({'ok': False, 'error': 'you_blocked'}, status=403)
            message.save()
            message.show_meta = True
            message.show_avatar = True
            message.show_ticks = True
            try:
                author_uid = getattr(getattr(request.user, "profile", None), "firebase_uid", None)
                if not author_uid:
                    print("❌ private push aborted: sender has no firebase_uid", flush=True)
                if author_uid and message.body:
                    mid = push_text_message_to_firestore(chat_group, author_uid, message.body)
                    if mid and not message.firebase_message_id:
                        message.firebase_message_id = mid
                        message.sender_uid = author_uid
                        message.save(update_fields=["firebase_message_id", "sender_uid"])
            except Exception as e:
                print("⚠️ Firestore push (private) failed:", e, flush=True)
            context = {'message': message, 'user': request.user}
            return render(request, 'a_rtchat/partials/chat_messages_p.html', context)

        # ---- Group chat path ----
        if not chat_group.is_private:
            if request.user not in chat_group.members.all():
                return JsonResponse({'ok': False, 'error': 'not_member'}, status=403)
            message.save()
            message.show_meta = True
            message.show_avatar = True
            message.show_ticks = True
            try:
                sender_uid = getattr(getattr(request.user, "profile", None), "firebase_uid", None)
                if not sender_uid:
                    print("❌ group push aborted: sender has no firebase_uid", flush=True)
                if sender_uid and message.body:
                    # ensure Firestore binding before push (safe no-op if already bound)
                    try:
                        ensure_firestore_group_chat_for_group(chat_group)
                    except Exception as e:
                        print("⚠️ ensure_firestore_group_chat_for_group failed before push:", e, flush=True)
                    mid = push_group_text_message_to_firestore(chat_group, sender_uid, message.body)
                    if mid and not message.firebase_message_id:
                        message.firebase_message_id = mid
                        message.sender_uid = sender_uid
                        message.save(update_fields=["firebase_message_id", "sender_uid"])
                elif not message.body:
                    print("ℹ️ empty group message; nothing pushed", flush=True)
            except Exception as e:
                print("⚠️ Firestore push (group) failed:", e, flush=True)
            context = {'message': message, 'user': request.user}
            return render(request, 'a_rtchat/partials/chat_messages_p.html', context)

        # Should never hit here
        return JsonResponse({'ok': False, 'error': 'invalid_state'}, status=400)

    # Ensure Firestore group chats mirrored locally before sidebar
    try:
        from a_rtchat.firebase_sync import sync_group_chats_for_user
        sync_group_chats_for_user(request.user)
    except Exception as e:
        print("⚠️ Firestore group sync failed:", e)

    # Web presence bump for chat index hits (mobile & web)
    try:
        prof = getattr(request.user, "profile", None)
        now = timezone.now()

        if prof and hasattr(prof, "last_online_at"):
            prof.last_online_at = now
            prof.save(update_fields=["last_online_at"])

        uid = getattr(prof, "firebase_uid", None)
        if uid:
            from a_rtchat.firebase_sync import update_user_last_online_in_firestore
            update_user_last_online_in_firestore(uid)
    except Exception as e:
        print("⚠️ lastOnlineAt web bump failed:", e, flush=True)

    read_state_for_me = None
    is_archived_for_me = False

    if request.user.is_authenticated:
        read_state_for_me, _ = ChatReadState.objects.get_or_create(user=request.user, group=chat_group)
        read_state_for_me.last_read_at = timezone.now()
        read_state_for_me.hidden = False
        read_state_for_me.save(update_fields=['last_read_at', 'hidden'])
        is_archived_for_me = getattr(read_state_for_me, "archived", False)

        inbound_qs = GroupMessages.objects.filter(group=chat_group).exclude(author=request.user)
        updated_ids = list(inbound_qs.filter(status__lt=GroupMessages.STATUS_READ).values_list('id', flat=True))
        if updated_ids:
            GroupMessages.objects.filter(id__in=updated_ids).update(status=GroupMessages.STATUS_READ)
            channel_layer = get_channel_layer()
            for mid in updated_ids:
                async_to_sync(channel_layer.group_send)(
                    chat_group.group_name,
                    {'type': 'message_update_handler', 'message_id': mid, 'chatroom_name': chat_group.group_name}
                )

        # PRIVATE read-state push to Firestore
        if chat_group.is_private and other_user:
            my_uid = getattr(getattr(request.user, "profile", None), "firebase_uid", None)
            peer_uid = getattr(getattr(other_user, "profile", None), "firebase_uid", None)
            if my_uid and peer_uid:
                latest_inbound_msg = inbound_qs.order_by('-created', '-id').first()
                last_read_mid = getattr(latest_inbound_msg, "firebase_message_id", None) if latest_inbound_msg else None
                try:
                    from a_rtchat.firebase_sync import update_private_chat_read_state
                    update_private_chat_read_state(my_uid, peer_uid, last_read_mid)
                except Exception as e:
                    print("⚠️ Firestore read-state update failed in chat_view:", e, flush=True)

        # GROUP: mark last message as read in Firestore too
        if not chat_group.is_private:
            my_uid = getattr(getattr(request.user, "profile", None), "firebase_uid", None)
            if my_uid:
                try:
                    from a_rtchat.firebase_sync import mark_group_last_message_read_in_firestore
                    mark_group_last_message_read_in_firestore(chat_group, my_uid)
                except Exception as e:
                    print("⚠️ Firestore group last-message read update failed in chat_view:", e, flush=True)
    sidebar_chats = _build_sidebar_chats(request.user, show_archived=archived_only)

    context = {
        'chat_messages': chat_messages,
        'form': form,
        'other_user': other_user,
        'other_user_online': other_user_online,
        'chatroom_name': chatroom_name,
        'chat_group': chat_group,
        'sidebar_chats': sidebar_chats,
        'show_archived_sidebar': archived_only,
        'archived_only': archived_only,
        'read_state_for_me': read_state_for_me,
        'is_archived_for_me': is_archived_for_me,
        # Group-specific context
        'group_member_count': (chat_group.members.count() if not chat_group.is_private else 0),
        'group_members': (list(chat_group.members.select_related("profile").all()) if not chat_group.is_private else []),
    }
    return render(request, 'a_rtchat/chat.html', context)


@login_required
@require_POST
def generate_smart_replies(request, chatroom_name):
    """
    Return up to three AI-suggested replies based on selected message IDs.
    """
    try:
        payload = json.loads(request.body.decode("utf-8"))
    except json.JSONDecodeError:
        return JsonResponse({"ok": False, "error": "bad-json"}, status=400)

    ids = payload.get("ids") or []
    if not isinstance(ids, list) or not ids:
        return JsonResponse({"ok": False, "error": "no-ids"}, status=400)

    try:
        ids = [int(x) for x in ids]
    except (TypeError, ValueError):
        return JsonResponse({"ok": False, "error": "bad-ids"}, status=400)

    try:
        chat_group = ChatGroup.objects.get(group_name=chatroom_name)
    except ChatGroup.DoesNotExist:
        return JsonResponse({"ok": False, "error": "chat-not-found"}, status=404)

    if request.user not in chat_group.members.all() and request.user != chat_group.admin:
        return JsonResponse({"ok": False, "error": "forbidden"}, status=403)

    messages_qs = (
        GroupMessages.objects
        .filter(id__in=ids, group=chat_group, is_deleted=False)
        .select_related("author")
        .order_by("created")
    )
    messages = list(messages_qs)
    if not messages:
        return JsonResponse({"ok": False, "error": "no-messages"}, status=400)

    context_lines = []
    for msg in messages:
        who = "YOU" if msg.author_id == request.user.id else "THEM"
        body = (msg.body or "").strip()
        if not body:
            continue
        context_lines.append(f"{who}: {body}")

    conversation_text = "\n".join(context_lines).strip()
    if len(context_lines) < 2 or len(conversation_text) < 16:
        return JsonResponse({"ok": False, "error": "not-enough-context"}, status=400)

    system_prompt = (
        "You generate short, natural WhatsApp-style chat replies.\n"
        "The user will send you a previous conversation between YOU and THEM.\n"
        "Return ONLY a JSON array of 1 to 3 possible short replies, "
        "ordered from most likely to least likely.\n"
        "Each reply must be a single string.\n"
        "Do not include any explanations or additional keys, only the JSON array."
    )
    max_suggestions = 3
    user_prompt = (
        "Conversation between YOU and THEM:\n\n"
        f"{conversation_text}\n\n"
        f"Now suggest {max_suggestions} different, short, natural replies that YOU could send next.\n"
        "Return ONLY a JSON array of reply strings, like:\n"
        '["Sure, sounds good!", "Let me check and get back to you.", "Not sure yet, what do you think?"]'
    )

    api_key = getattr(settings, "OPENAI_API_KEY", "") or None
    if not api_key:
        return JsonResponse({"ok": False, "error": "no-api-key"}, status=500)

    client = OpenAI(api_key=api_key)
    try:
        completion = client.chat.completions.create(
            model=getattr(settings, "SMART_REPLIES_MODEL", "gpt-4o-mini"),
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            temperature=0.7,
            max_tokens=120,
        )
    except Exception as exc:
        print("⚠️ smart replies OpenAI error:", exc, flush=True)
        return JsonResponse({"ok": False, "error": "openai-error"}, status=500)

    raw = (completion.choices[0].message.content or "").strip()
    suggestions: list[str] = []

    try:
        parsed = json.loads(raw)
        if isinstance(parsed, list):
            suggestions = [str(x).strip() for x in parsed if isinstance(x, (str, int, float))]
    except Exception:
        match = re.search(r"\[[\s\S]+\]", raw)
        if match:
            try:
                parsed = json.loads(match.group(0))
                if isinstance(parsed, list):
                    suggestions = [str(x).strip() for x in parsed if isinstance(x, (str, int, float))]
            except Exception:
                pass

    if not suggestions and raw:
        suggestions = [raw]

    suggestions = [s for s in suggestions if s][:max_suggestions]
    if not suggestions:
        return JsonResponse({"ok": False, "error": "no-suggestions"}, status=500)

    return JsonResponse({"ok": True, "suggestions": suggestions})


def _annotate_runs(msgs, window_seconds=300):
    """
    Mark only the *last* message in a consecutive run (same author, close in time)
    to show footer meta (name+time), avatar (for others), and ticks (for me).
    """
    n = len(msgs)
    if n == 0:
        return msgs
    win = timedelta(seconds=window_seconds)

    for i, m in enumerate(msgs):
        next_m = msgs[i + 1] if i + 1 < n else None

        end_of_run = False
        if next_m is None:
            end_of_run = True
        else:
            same_author = (getattr(next_m, "author_id", None) == getattr(m, "author_id", None))
            try:
                close_in_time = abs(next_m.created - m.created) <= win
            except Exception:
                close_in_time = False
            if (not same_author) or (not close_in_time):
                end_of_run = True

        m.show_meta = end_of_run
        m.show_avatar = end_of_run
        m.show_ticks = end_of_run
    return msgs


def _attach_private_read_flag(message, current_user):
    """
    Ensure message.read_by_peer is set appropriately for private chats when rendering
    single messages (non-stream contexts such as delete/edit views).
    """
    message.read_by_peer = False
    group = getattr(message, "group", None)
    if not group or not getattr(group, "is_private", False):
        return
    if message.author_id != getattr(current_user, "id", None):
        return
    other = None
    for member in group.members.all():
        if member != current_user:
            other = member
            break
    if not other:
        return
    try:
        from a_rtchat.firebase_sync import has_peer_read_message_for_private_chat

        message.read_by_peer = has_peer_read_message_for_private_chat(group, message, current_user, other)
    except Exception:
        message.read_by_peer = False


@login_required
def chat_index(request):
    """Render the chat UI with no room selected (blank state)."""
    archived_only = (request.GET.get("archived") == "1")
    # Ensure Firestore group chats mirrored locally before sidebar
    try:
        from a_rtchat.firebase_sync import sync_group_chats_for_user
        sync_group_chats_for_user(request.user)
    except Exception as e:
        print("⚠️ Firestore group sync failed:", e)

    sidebar_chats = _build_sidebar_chats(request.user, show_archived=archived_only)

    context = {
        'chat_messages': [],
        'form': None,
        'other_user': None,
        'chatroom_name': None,
        'chat_group': None,
        'sidebar_chats': sidebar_chats,
        'show_archived_sidebar': archived_only,
        'archived_only': archived_only,
    }
    return render(request, 'a_rtchat/chat.html', context)


@login_required
def chat_archived_index(request):
    """
    Redirect to home with archived=1 query parameter for consistency.
    """
    return redirect('{}?archived=1'.format(reverse('home')))


@login_required
@require_http_methods(["POST"])
def chat_archive_toggle(request, chatroom_name):
    """
    Toggle archive state for current user on this chat group.
    """
    chat_group = get_object_or_404(ChatGroup, group_name=chatroom_name)
    if not chat_group.members.filter(id=request.user.id).exists():
        raise Http404()
    read_state, _ = ChatReadState.objects.get_or_create(user=request.user, group=chat_group)
    new_value = not getattr(read_state, "archived", False)
    read_state.archived = new_value
    # archiving is distinct from "hidden"; keep hidden False here
    read_state.hidden = False
    read_state.save(update_fields=["archived", "hidden"])
    # Always go back to main page (localhost:8000)
    return redirect("home")


@login_required
def get_or_create_chatroom(request, username):
    if request.user.username == username:
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
        # Bind to priv_{uidA#uidB} immediately so first message push (later) will have a chat id
        try:
            ensure_firestore_private_chat_for_group(chatroom)
        except Exception as e:
            print("⚠️ Could not bind priv_* chat:", e)

    return redirect('chatroom', chatroom.group_name)


# a_rtchat/views.py (modify your existing create_groupchat)

@login_required
def create_groupchat(request):
    form = NewGroupForm()
    if request.method == 'POST':
        form = NewGroupForm(request.POST)
        if form.is_valid():
            new_groupchat = form.save(commit=False)
            new_groupchat.admin = request.user   # creator is admin
            new_groupchat.save()
            new_groupchat.members.add(request.user)  # ensure creator is a member

            # NEW: add selected members (comma-separated IDs)
            raw_ids = (request.POST.get('member_ids') or '').strip()
            if raw_ids:
                ids = [x for x in raw_ids.split(',') if x]
                UserModel = get_user_model()
                for uid in ids:
                    try:
                        u = UserModel.objects.get(id=uid)
                        if u != request.user:
                            # (optional) enforce firebase-backed only:
                            # if getattr(u.profile, "firebase_uid", None):
                            new_groupchat.members.add(u)
                    except UserModel.DoesNotExist:
                        pass

            if request.htmx:
                # tell HTMX to redirect the modal to the new room
                # ensure Firestore binding AFTER members are added
                try:
                    ensure_firestore_group_chat_for_group(new_groupchat)
                except Exception as e:
                    print("⚠️ group Firestore bind failed:", e, flush=True)
                resp = JsonResponse({'ok': True})
                resp['HX-Redirect'] = request.build_absolute_uri(reverse('chatroom', args=[new_groupchat.group_name]))
                return resp

            # non-HTMX: bind then redirect
            try:
                ensure_firestore_group_chat_for_group(new_groupchat)
            except Exception as e:
                print("⚠️ group Firestore bind failed:", e, flush=True)
            return redirect('chatroom', new_groupchat.group_name)
        else:
            if request.htmx:
                # Re-render inline form with errors so they show inside the modal
                return render(request, 'a_rtchat/partials/group_create_inline.html', {'form': form}, status=400)
    context = {'form': form}
    return render(request, 'a_rtchat/create_groupchat.html', context)


@login_required
def chatroom_edit_view(request, chatroom_name):
    chat_group = get_object_or_404(ChatGroup, group_name=chatroom_name)
    if request.user != chat_group.admin:
        raise Http404()
    form = ChatRoomEditForm(instance=chat_group)

    if request.method == 'POST':
        form = ChatRoomEditForm(request.POST, instance=chat_group)
        if form.is_valid():
            form.save()

            # Remove members
            remove_members = request.POST.getlist('remove_members')
            for member_id in remove_members:
                try:
                    member = User.objects.get(id=member_id)
                    # Don't allow removing the admin themself here
                    if member != chat_group.admin:
                        chat_group.members.remove(member)
                        # Firestore clean-up for removed users
                        try:
                            remove_user_from_group_chat_in_firestore(chat_group, member)
                        except Exception as e:
                            print("⚠️ Firestore group member removal in edit view failed:", e, flush=True)
                except User.DoesNotExist:
                    pass

            # Add members (comma-separated ids)
            raw_add = (request.POST.get('add_member_ids') or '').strip()
            added_users = []
            if raw_add:
                ids = [x for x in raw_add.split(',') if x]
                UserModel = get_user_model()
                for uid in ids:
                    try:
                        u = UserModel.objects.get(id=uid)
                        if u not in chat_group.members.all():
                            chat_group.members.add(u)
                            added_users.append(u)
                    except UserModel.DoesNotExist:
                        pass

            chat_group.save()

            # 🔥 Firestore: add new members explicitly via ArrayUnion
            if added_users:
                try:
                    add_users_to_group_chat_in_firestore(chat_group, added_users)
                except Exception as e:
                    print("⚠️ add_users_to_group_chat_in_firestore failed in chatroom_edit_view:", e, flush=True)

            # mirror name/photo/adminIds etc. (members is already up-to-date via ArrayUnion)
            try:
                admin_uid = getattr(getattr(chat_group.admin, "profile", None), "firebase_uid", None)
                admin_uids = [admin_uid] if admin_uid else []
                update_firestore_group_doc(
                    chat_group,
                    admin_uids=admin_uids,
                )
            except Exception as e:
                print("⚠️ Firestore group update on edit failed:", e, flush=True)
            if getattr(request, "htmx", False):
                # Close modal & refresh page
                from django.http import HttpResponse
                resp = HttpResponse(status=204)
                resp["HX-Refresh"] = "true"
                return resp
            return redirect('chatroom', chatroom_name)

    # When requested via HTMX, return just the modal body
    if getattr(request, "htmx", False):
        return render(request, 'a_rtchat/partials/group_edit_modal.html', {'form': form, 'chat_group': chat_group})
    context = {'form': form, 'chat_group': chat_group}
    return render(request, 'a_rtchat/chatroom_edit.html', context)


@login_required
def chatroom_delete_view(request, chatroom_name):
    chat_group = get_object_or_404(ChatGroup, group_name=chatroom_name)
    if request.user != chat_group.admin:
        raise Http404()

    if request.method == "POST":
        chat_group.delete()
        messages.success(request, 'Chatroom deleted')
        return redirect('home')

    return render(request, 'a_rtchat/chatroom_delete.html', {'chat_group': chat_group})


@login_required
def group_settings_modal(request, chatroom_name):
    """
    Render the admin-only modal (HTMX) for editing a group's
    name, avatar, and membership.
    """
    group = get_object_or_404(ChatGroup, group_name=chatroom_name)
    if group.is_private or request.user != group.admin:
        raise Http404()
    # members w/ profiles for chips
    members = group.members.select_related("profile").all()
    return render(request, "a_rtchat/partials/group_settings_modal.html", {
        "group": group,
        "members": members,
    })


@login_required
@require_http_methods(["POST"])
def group_update_meta(request, chatroom_name):
    """
    Update group name & avatar (admin-only). Push to Firestore and local model.
    """
    group = get_object_or_404(ChatGroup, group_name=chatroom_name)
    if group.is_private or request.user != group.admin:
        raise Http404()

    new_name = (request.POST.get("group_name") or "").strip()
    avatar_file: UploadedFile | None = request.FILES.get("avatar")
    photo_url = None

    updates = []
    if new_name and new_name != (group.groupchat_name or ""):
        group.groupchat_name = new_name
        updates.append("groupchat_name")

    # Upload avatar to Firebase Storage (groups/<chat_id>/avatar.jpg)
    if avatar_file:
        try:
            # Ensure we have a chat_id on Firestore first
            chat_id = ensure_firestore_group_chat_for_group(group)
            if chat_id:
                bucket = get_bucket()
                blob_path = f"groups/{chat_id}/avatar.jpg"
                blob = bucket.blob(blob_path)
                blob.upload_from_file(avatar_file.file, content_type=avatar_file.content_type or "image/jpeg")
                # Make tokened download URL
                md = blob.metadata or {}
                if not md.get("firebaseStorageDownloadTokens"):
                    from uuid import uuid4
                    md["firebaseStorageDownloadTokens"] = str(uuid4())
                    blob.metadata = md
                    blob.patch()
                token = (blob.metadata or {}).get("firebaseStorageDownloadTokens")
                if token:
                    from urllib.parse import quote
                    encoded = quote(blob.name, safe="")
                    photo_url = f"https://firebasestorage.googleapis.com/v0/b/{bucket.name}/o/{encoded}?alt=media&token={token}"
                    # Save locally if you keep a URL on the model
                    if getattr(group, "avatar", None) is not None:
                        group.avatar = photo_url
                        updates.append("avatar")
        except Exception as e:
            print("⚠️ group avatar upload failed:", e, flush=True)

    if updates:
        group.save(update_fields=updates)

    # Firestore merge-update
    try:
        # Gather admin & member uids for consistency
        admin_uid = getattr(getattr(group.admin, "profile", None), "firebase_uid", None)
        member_uids = [
            getattr(getattr(u, "profile", None), "firebase_uid", None)
            for u in group.members.all()
        ]
        member_uids = [u for u in member_uids if u]
        admin_ids = [admin_uid] if admin_uid else []
        update_firestore_group_doc(
            group,
            new_name=group.groupchat_name,
            members_uids=member_uids,
            admin_uids=admin_ids,
            photo_url=photo_url,
        )
    except Exception as e:
        print("⚠️ Firestore meta update failed:", e, flush=True)

    messages.success(request, "Group updated.")
    # Close modal and refresh header via HX-Redirect back to chatroom.
    # Use a 204 so HTMX never tries to swap JSON into the modal body.
    from django.http import HttpResponse
    resp = HttpResponse(status=204)
    resp["HX-Redirect"] = request.build_absolute_uri(
        reverse("chatroom", args=[group.group_name])
    )
    return resp


@login_required
@require_http_methods(["POST"])
def group_update_members(request, chatroom_name):
    """
    Handle add/remove members from the group settings modal.

    - `add_ids`: hidden input, comma-separated Django user IDs (string).
    - `remove_ids`: checkbox list, multiple Django user IDs.

    After updating `group.members`, we:
      * add the newly added users to Firestore.members via ArrayUnion
      * remove the removed users via remove_user_from_group_chat_in_firestore
      * optionally still recompute adminIds via update_firestore_group_doc
    """
    UserModel = get_user_model()
    group = get_object_or_404(ChatGroup, group_name=chatroom_name, is_private=False)

    # Only allow the group admin to manage membership
    if request.user != group.admin:
        return HttpResponseForbidden("Only the group admin can update members.")

    # --- Parse add_ids (comma-separated list from hidden field) ---
    add_raw = (request.POST.get("add_ids") or "").strip()
    add_ids: list[str] = []
    if add_raw:
        add_ids = [s for s in add_raw.split(",") if s.strip()]

    # --- Parse remove_ids (checkbox list) ---
    remove_ids = request.POST.getlist("remove_ids")  # this is already a list of strings

    added_users = []

    # --- Add members ---
    for sid in add_ids:
        try:
            u = UserModel.objects.get(pk=sid)
        except UserModel.DoesNotExist:
            continue
        if u not in group.members.all():
            group.members.add(u)
            added_users.append(u)

    # --- Remove members (cannot remove admin) ---
    for sid in remove_ids:
        try:
            u = UserModel.objects.get(pk=sid)
        except UserModel.DoesNotExist:
            continue
        if u == group.admin:
            # never remove the admin
            continue
        if u in group.members.all():
            group.members.remove(u)
            # best-effort cleanup in Firestore (ArrayRemove + memberMeta cleanup)
            try:
                remove_user_from_group_chat_in_firestore(group, u)
            except Exception as e:
                print(
                    "⚠️ remove_user_from_group_chat_in_firestore failed in group_update_members:",
                    e,
                    flush=True,
                )

    # Ensure the M2M changes are persisted
    group.save()

    # --- Firestore sync for added users (ArrayUnion into members[]) ---
    if added_users:
        try:
            add_users_to_group_chat_in_firestore(group, added_users)
        except Exception as e:
            print("⚠️ add_users_to_group_chat_in_firestore failed in group_update_members:", e, flush=True)

    # --- Optional: keep adminIds in sync as well ---
    try:
        admin_uid = getattr(getattr(group.admin, "profile", None), "firebase_uid", None)
        admin_uids = [admin_uid] if admin_uid else []
        update_firestore_group_doc(
            group,
            admin_uids=admin_uids,
        )
    except Exception as e:
        print("⚠️ Firestore adminIds update failed in group_update_members:", e, flush=True)

    # Re-render the settings modal with updated members list
    members = list(group.members.select_related("profile").all())
    return render(
        request,
        "a_rtchat/partials/group_settings_modal.html",
        {
            "group": group,
            "members": members,
        },
    )


@login_required
def chatroom_leave_view(request, chatroom_name):
    chat_group = get_object_or_404(ChatGroup, group_name=chatroom_name)

    if request.user not in chat_group.members.all():
        raise Http404()

    if request.method == "POST":
        # For private chats, do not remove membership to preserve future inbound visibility
        if chat_group.is_private:
            rs, _ = ChatReadState.objects.get_or_create(user=request.user, group=chat_group)
            rs.hidden = True
            rs.save(update_fields=['hidden'])
            messages.success(request, 'Conversation hidden. New messages will unhide it.')
        else:
            # 🔁 First, mirror the leave to Firestore so sync won't re-add you.
            try:
                remove_user_from_group_chat_in_firestore(chat_group, request.user)
            except Exception as e:
                print("⚠️ Firestore group member removal failed:", e, flush=True)

            # Then remove locally.
            chat_group.members.remove(request.user)
            # Optionally hide any lingering sidebar entry for safety.
            try:
                rs, _ = ChatReadState.objects.get_or_create(user=request.user, group=chat_group)
                rs.hidden = True
                rs.save(update_fields=['hidden'])
            except Exception:
                pass
            messages.success(request, 'You left the Chat')
        return redirect('home')

    return redirect('chatroom', chatroom_name)


@login_required
@require_http_methods(["POST"])
def message_delete(request, message_id):
    message = get_object_or_404(GroupMessages, id=message_id)
    if message.author != request.user:
        raise Http404()

    # Mark as deleted locally
    message.is_deleted = True
    message.body = message.body  # keep the body but hide
    message.save(update_fields=["is_deleted", "body"])

    # Firestore: delete for ALL
    try:
        from a_rtchat.firebase_sync import delete_message_in_firestore
        deleted_by_uid = getattr(getattr(request.user, "profile", None), "firebase_uid", None)
        delete_message_in_firestore(message, deleted_by_uid=deleted_by_uid)
    except Exception as e:
        print("⚠️ Firestore delete_message_in_firestore failed:", e, flush=True)

    # Broadcast updated rendering to the room via channels
    channel_layer = get_channel_layer()
    event = {
        'type': 'message_update_handler',
        'message_id': message.id,
        'chatroom_name': message.group.group_name,
    }
    async_to_sync(channel_layer.group_send)(message.group.group_name, event)

    context = {'message': message, 'user': request.user}
    _attach_private_read_flag(message, request.user)
    return render(request, 'a_rtchat/chat_message.html', context)


@login_required
def chat_user_search(request):
    """HTMX endpoint: search users by username or phone number (mobile+web)."""
    UserModel = get_user_model()
    raw = (request.GET.get('q') or '').strip()
    # normalize: strip leading '@' for usernames, '+' and spaces for phone
    query = raw.lstrip('@').strip()
    digits = ''.join(ch for ch in query if ch.isdigit())

    results = []
    if query:
        # Match username, display_name, name, email, and phone
        q = (
            Q(username__iexact=query) |
            Q(username__icontains=query) |
            Q(profile__display_name__icontains=query) |
            Q(profile__name__icontains=query) |               # NEW: name
            Q(email__iexact=query) |
            Q(profile__email__iexact=query)
        )
        # add the phone filter only if we actually have a phone-like query
        if digits and len(digits) >= 4:
            q |= Q(profile__phone_number__icontains=digits)

        qs = (
            UserModel.objects
            .filter(q, profile__firebase_uid__isnull=False)  # only Firebase-backed
            .select_related("profile")
            .order_by('username')[:10]
        )

        my_blocked_ids = set(
            BlockedUser.objects.filter(blocker=request.user).values_list("blocked_id", flat=True)
        )
        blocked_me_ids = set(
            BlockedUser.objects.filter(blocked=request.user).values_list("blocker_id", flat=True)
        )

        results = [
            u for u in qs
            if u.id != request.user.id
            and u.id not in my_blocked_ids
            and u.id not in blocked_me_ids
        ]

        # Firestore fallback: only if no local hit AND looks like a phone
        if not results and digits and len(digits) >= 7:
            prof = ensure_user_from_firebase_by_phone(query)
            if prof and prof.user_id != request.user.id:
                u = prof.user
                if (
                    u.id not in my_blocked_ids
                    and u.id not in blocked_me_ids
                ):
                    results = [u]

    print(f"[DIRECT SEARCH] q='{query}' results={len(results)}", flush=True)
    mode = request.GET.get('mode')
    if mode == 'pickable':
        return render(request, 'a_rtchat/partials/user_search_pickable.html', {
            'results': results,
            'query': query,
        })
    return render(request, 'a_rtchat/partials/user_search_results.html', {
        'results': results,
        'query': query,
    })


@login_required
@require_http_methods(["POST"])
def chat_start_new(request):
    raw = (request.POST.get('q') or '').strip()
    if not raw:
        return JsonResponse({'ok': False, 'error': 'empty'}, status=400)

    UserModel = get_user_model()
    candidate = None

    # Prefer exact username (email in your setup)
    try:
        candidate = UserModel.objects.get(username__iexact=raw)
    except UserModel.DoesNotExist:
        # fallbacks: display_name, name, or contains username
        candidate = (
            UserModel.objects.filter(
                Q(username__icontains=raw) |
                Q(profile__display_name__icontains=raw) |
                Q(profile__name__icontains=raw)
            )
            .exclude(id=request.user.id)
            .select_related('profile')
            .first()
        )

    if not candidate:
        digits = ''.join(ch for ch in raw if ch.isdigit())
        if digits:
            prof = ensure_user_from_firebase_by_phone(raw)
            if prof:
                candidate = prof.user

    # Block starting chats with local-only users
    if candidate and not getattr(candidate.profile, "firebase_uid", None):
        candidate = None

    # Do not start chats if either side has blocked the other
    if candidate:
        if (
            BlockedUser.objects.filter(blocker=request.user, blocked=candidate).exists()
            or BlockedUser.objects.filter(blocker=candidate, blocked=request.user).exists()
        ):
            return render(request, 'a_rtchat/partials/user_search_results.html', {
                'results': [], 'query': raw
            })

    if not candidate or candidate.id == request.user.id:
        return render(request, 'a_rtchat/partials/user_search_results.html', {
            'results': [], 'query': raw
        })

    # Reuse existing private chat or create one
    chatroom = (
        ChatGroup.objects.filter(is_private=True, members=request.user)
        .filter(members=candidate)
        .first()
    )
    if not chatroom:
        chatroom = ChatGroup.objects.create(is_private=True)
        chatroom.members.add(candidate, request.user)
        # Bind to priv_{uidA#uidB} immediately (so later pushes have a chat id)
        try:
            ensure_firestore_private_chat_for_group(chatroom)
        except Exception as e:
            print("⚠️ Could not bind priv_* chat:", e)

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
    
    # Get deleted_by_uid for Firestore
    deleted_by_uid = getattr(getattr(request.user, "profile", None), "firebase_uid", None)
    
    # Mark as deleted locally and push to Firestore
    messages_list = list(messages_qs)
    for message in messages_list:
        message.is_deleted = True
        message.body = message.body  # keep the body but hide
        message.save(update_fields=["is_deleted", "body"])
        
        # Firestore: delete for ALL
        try:
            from a_rtchat.firebase_sync import delete_message_in_firestore
            delete_message_in_firestore(message, deleted_by_uid=deleted_by_uid)
        except Exception as e:
            print("⚠️ Firestore delete_message_in_firestore failed for message", message.id, ":", e, flush=True)
    
    # Broadcast updates to re-render each message
    channel_layer = get_channel_layer()
    for message in messages_list:
        event = {
            'type': 'message_update_handler',
            'message_id': message.id,
            'chatroom_name': message.group.group_name,
        }
        async_to_sync(channel_layer.group_send)(message.group.group_name, event)
    return JsonResponse({'ok': True, 'count': len(messages_list)})


@login_required
def group_create_inline(request):
    # returns the inline group-creation UI for the modal
    print("[GROUP INLINE] served", flush=True)
    return render(request, 'a_rtchat/partials/group_create_inline.html', {})

@login_required
def chat_user_search_pickable(request):
    """HTMX endpoint: search users, render pickable (checkbox) rows."""
    UserModel = get_user_model()
    raw = (request.GET.get('q') or '').strip()
    query = raw.lstrip('@').strip()
    digits = ''.join(ch for ch in query if ch.isdigit())

    results = []
    if query:
        # Same matching as direct search: username, display_name, name, email, phone
        q = (
            Q(username__iexact=query) |
            Q(username__icontains=query) |
            Q(profile__display_name__icontains=query) |
            Q(profile__name__icontains=query) |               # NEW: name
            Q(email__iexact=query) |
            Q(profile__email__iexact=query)
        )
        if digits and len(digits) >= 4:
            q |= Q(profile__phone_number__icontains=digits)

        qs = (
            UserModel.objects
            .filter(q, profile__firebase_uid__isnull=False)  # keep parity with your rule
            .select_related("profile")
            .order_by('username')[:10]
        )
        my_blocked_ids = set(
            BlockedUser.objects.filter(blocker=request.user).values_list("blocked_id", flat=True)
        )
        blocked_me_ids = set(
            BlockedUser.objects.filter(blocked=request.user).values_list("blocker_id", flat=True)
        )
        results = [
            u for u in qs
            if u.id != request.user.id
            and u.id not in my_blocked_ids
            and u.id not in blocked_me_ids
        ]

    print(f"[GROUP SEARCH] q='{query}' results={len(results)}", flush=True)
    return render(request, 'a_rtchat/partials/user_search_pickable.html', {
        'results': results,
        'query': query,
    })

@login_required
@require_http_methods(["POST"])
def message_hide_for_me(request, message_id):
    msg = get_object_or_404(GroupMessages, id=message_id)
    # Optional: ensure user is in the group
    if request.user not in msg.group.members.all():
        return JsonResponse({"ok": False, "error": "forbidden"}, status=403)

    HiddenMessage.objects.get_or_create(user=request.user, message=msg)
    return JsonResponse({"ok": True})


@login_required
@require_http_methods(["POST"])
def messages_hide_bulk(request):
    ids = request.POST.getlist("ids[]") or request.POST.getlist("ids")
    try:
        id_ints = [int(x) for x in ids]
    except Exception:
        return JsonResponse({"ok": False, "error": "bad_ids"}, status=400)
    if not id_ints:
        return JsonResponse({"ok": False, "error": "empty"}, status=400)

    msgs = GroupMessages.objects.filter(id__in=id_ints).select_related("group")
    # Optional: enforce membership
    msgs = [m for m in msgs if request.user in m.group.members.all()]

    existing = HiddenMessage.objects.filter(
        user=request.user,
        message__in=msgs
    ).values_list("message_id", flat=True)
    existing_ids = set(existing)

    to_create = [
        HiddenMessage(user=request.user, message=m)
        for m in msgs
        if m.id not in existing_ids
    ]
    if to_create:
        HiddenMessage.objects.bulk_create(to_create, ignore_conflicts=True)

    return JsonResponse({"ok": True, "count": len(to_create)})


@login_required
@require_http_methods(["POST"])
def chat_clear(request, chatroom_name):
    """
    Clear chat: hide all messages in the chatroom for the current user.
    Works like 'delete for me' but for all messages at once.
    """
    chat_group = get_object_or_404(ChatGroup, group_name=chatroom_name)
    # Ensure user is in the group
    if request.user not in chat_group.members.all():
        return JsonResponse({"ok": False, "error": "forbidden"}, status=403)

    # Get all messages in this chatroom
    all_messages = GroupMessages.objects.filter(group=chat_group)
    
    # Get already hidden message IDs
    existing = HiddenMessage.objects.filter(
        user=request.user,
        message__group=chat_group
    ).values_list("message_id", flat=True)
    existing_ids = set(existing)

    # Create HiddenMessage records for all messages not already hidden
    to_create = [
        HiddenMessage(user=request.user, message=m)
        for m in all_messages
        if m.id not in existing_ids
    ]
    
    if to_create:
        HiddenMessage.objects.bulk_create(to_create, ignore_conflicts=True)

    return JsonResponse({"ok": True, "count": len(to_create)})


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

    context = {'message': message, 'user': request.user}
    _attach_private_read_flag(message, request.user)
    return render(request, 'a_rtchat/chat_message.html', context)


from django.contrib.auth.decorators import login_required
from django.shortcuts import render, get_object_or_404
from django.contrib.auth.models import User

@login_required
def presence_badge(request, username):
    u = get_object_or_404(User, username=username)
    prof = getattr(u, "profile", None)
    last_seen = getattr(prof, "last_online_at", None)

    # Try Firestore first (freshest), then fall back to DB
    try:
        uid = getattr(prof, "firebase_uid", None)
        if uid:
            # lightweight direct read with self-healing; no heavy sync
            from a_users.firebase_helpers import get_db
            from a_rtchat.firebase_sync import get_profile_dict
            db = get_db()
            data = get_profile_dict(db, uid)
            if data:
                fs_ts = data.get("lastOnlineAt") or data.get("lastSeenAt")
                if fs_ts:
                    last_seen = fs_ts  # Firestore Admin SDK returns tz-aware UTC datetime
    except Exception:
        # if Firestore read fails, we just use the DB value
        pass

    online = _is_online_from_last_seen(last_seen)
    return render(request, "a_rtchat/partials/presence_badge.html", {"online": online})


@login_required
def presence_ping(request):
    """
    Update the current user's last_online_at locally and in Firestore.
    Triggered by HTMX polling on authenticated pages.
    """
    print(f"[presence_ping] user={getattr(request.user, 'id', None)}", flush=True)
    prof = getattr(request.user, "profile", None)
    now = timezone.now()

    if prof is not None and hasattr(prof, "last_online_at"):
        prof.last_online_at = now
        prof.save(update_fields=["last_online_at"])

    try:
        uid = getattr(prof, "firebase_uid", None)
        if uid:
            from a_rtchat.firebase_sync import update_user_last_online_in_firestore

            update_user_last_online_in_firestore(uid)
    except Exception as e:
        print("⚠️ presence_ping Firestore bump failed:", e, flush=True)

    return JsonResponse({"ok": True})
