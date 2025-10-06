from django.forms import ModelForm
from django import forms
from .models import *

class ChatmessageCreateForm(ModelForm):
    class Meta:
        model= GroupMessages
        fields= ['body']
        
        widgets={
            'body':forms.TextInput(attrs={
                'placeholder':'Type a message',
                'class':'px-3 py-1.5 bg-gray-800 text-gray-100 placeholder-gray-400 rounded-lg w-full !w-full',
                'maxlength':'25000',
                'autofocus':True,
                'autocomplete':'off',
                'style':'height: 2.2rem; line-height: 1.2rem;',
            }),
        }


class NewGroupForm(ModelForm):
    class Meta:
        model= ChatGroup
        fields=['groupchat_name']
        widgets={
            'groupchat_name':forms.TextInput(attrs={
                'placeholder':'Add name ...',
                'class':'p-3 bg-gray-800 text-gray-100 placeholder-gray-400 rounded-lg w-full',
                'maxlength': '300',
                'autofocus': True,
            }),
        }

class ChatRoomEditForm(ModelForm):
    class Meta:
        model = ChatGroup   
        fields=['groupchat_name']
        widgets={
            'groupchat_name': forms.TextInput(attrs={
                'class':'p-4 text-xl font-bold mb-4',
                'maxlength':'300'
            }),
        }