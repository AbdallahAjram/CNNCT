from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('a_rtchat', '0026_alter_chatgroup_group_name'),
    ]

    operations = [
        migrations.AddField(
            model_name='chatgroup',
            name='firebase_chat_id',
            field=models.CharField(blank=True, max_length=200, null=True, unique=True),
        ),
        migrations.AddField(
            model_name='groupmessages',
            name='firebase_message_id',
            field=models.CharField(blank=True, max_length=200, null=True, unique=True),
        ),
        migrations.AddField(
            model_name='groupmessages',
            name='sender_uid',
            field=models.CharField(blank=True, max_length=128, null=True),
        ),
    ]


