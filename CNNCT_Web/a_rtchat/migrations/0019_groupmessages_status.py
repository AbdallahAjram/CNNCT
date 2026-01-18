from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [
        ('a_rtchat', '0018_alter_chatgroup_group_name'),
    ]

    operations = [
        migrations.AddField(
            model_name='groupmessages',
            name='status',
            field=models.PositiveSmallIntegerField(choices=[(0, 'sent'), (1, 'delivered'), (2, 'read')], default=0),
        ),
    ]


