from django.dispatch import receiver
from django.db.models.signals import post_save, pre_save
from allauth.account.models import EmailAddress
from django.contrib.auth.models import User
from .models import Profile

@receiver(post_save, sender=User)       
def user_postsave(sender, instance, created, **kwargs):
    user = instance
    
    # add profile if user is created
    if created:
        p = Profile.objects.create(user=user)
        # Ensure phone is set for new users (incremental assignment is handled in migration for existing)
        if not p.phone:
            # naive assign based on last phone assigned
            try:
                last = Profile.objects.exclude(phone__isnull=True).exclude(phone__exact='').order_by('-id').first()
                base = int(last.phone) + 1 if last and str(last.phone).isdigit() else 76900300
            except Exception:
                base = 76900300
            p.phone = str(base)
            p.save(update_fields=['phone'])
    else:
        # update allauth emailaddress if exists 
        try:
            email_address = EmailAddress.objects.get_primary(user)
            if email_address.email != user.email:
                email_address.email = user.email
                email_address.verified = False
                email_address.save()
        except:
            # if allauth emailaddress doesn't exist create one
            EmailAddress.objects.create(
                user = user,
                email = user.email, 
                primary = True,
                verified = False
            )
        
        
@receiver(pre_save, sender=User)
def user_presave(sender, instance, **kwargs):
    if instance.username:
        instance.username = instance.username.lower()