# a_rtchat/models.py
from django.db import models
from django.contrib.auth.models import User
import shortuuid

class ChatGroup(models.Model):
    group_name = models.CharField(max_length=128, unique=True,default=shortuuid.uuid)
    groupchat_name=models.CharField(max_length=128,null=True,blank=True)
    admin=models.ForeignKey(User,related_name='groupchats',blank=True,null=True,on_delete=models.SET_NULL)
    users_online = models.ManyToManyField(User, related_name='online_in_groups',blank=True)
    members= models.ManyToManyField(User, related_name='chat_groups', blank=True)
    is_private = models.BooleanField(default=False)
    
    def __str__(self):  
        return self.group_name
class GroupMessages(models.Model):
    group=models.ForeignKey(ChatGroup,related_name='chat_messages', on_delete=models.CASCADE)
    author=models.ForeignKey(User,on_delete=models.CASCADE)
    body= models.TextField()
    is_deleted = models.BooleanField(default=False)
    edited = models.BooleanField(default=False)
    edited_at = models.DateTimeField(null=True, blank=True)
    created= models.DateTimeField(auto_now_add=True)
    # 0=sent, 1=delivered, 2=read
    STATUS_SENT = 0
    STATUS_DELIVERED = 1
    STATUS_READ = 2
    STATUS_CHOICES = (
        (STATUS_SENT, 'sent'),
        (STATUS_DELIVERED, 'delivered'),
        (STATUS_READ, 'read'),
    )
    status = models.PositiveSmallIntegerField(choices=STATUS_CHOICES, default=STATUS_SENT)
    def __str__(self):
        return f'{self.author.username} : {self.body}'  
    class Meta:
        # Ascending order so initial render is oldest -> newest
        ordering=['created','id']


class ChatReadState(models.Model):
    """Tracks the last time a user viewed a given chat group."""
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='chat_read_states')
    group = models.ForeignKey(ChatGroup, on_delete=models.CASCADE, related_name='read_states')
    last_read_at = models.DateTimeField(null=True, blank=True)
    hidden = models.BooleanField(default=False)

    class Meta:
        unique_together = ('user', 'group')

    def __str__(self):
        return f'{self.user.username} read {self.group.group_name} at {self.last_read_at}'