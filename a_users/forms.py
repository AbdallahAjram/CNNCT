from django.forms import ModelForm
from django import forms
from django.contrib.auth.models import User
from .models import Profile

class ProfileForm(ModelForm):
    class Meta:
        model = Profile
        fields = ['image', 'display_name', 'about' ]
        widgets = {
            'image': forms.FileInput(),
            'display_name' : forms.TextInput(attrs={'placeholder': 'Add display name'}),
            'about' : forms.Textarea(attrs={'rows':3, 'placeholder': 'Add about'})
        }
        
        
class EmailForm(ModelForm):
    email = forms.EmailField(required=True)

    class Meta:
        model = User
        fields = ['email']


class UsernameForm(ModelForm):
    class Meta:
        model = User
        fields = ['username']
