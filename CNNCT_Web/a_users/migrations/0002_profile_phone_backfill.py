from django.db import migrations, models


def backfill_phone_numbers(apps, schema_editor):
    Profile = apps.get_model('a_users', 'Profile')
    start = 76900300
    counter = 0
    for profile in Profile.objects.order_by('id'):
        if not profile.phone:
            profile.phone = str(start + counter)
            counter += 1
            profile.save(update_fields=['phone'])


class Migration(migrations.Migration):
    dependencies = [
        ('a_users', '0001_initial'),
    ]

    operations = [
        migrations.AddField(
            model_name='profile',
            name='phone',
            field=models.CharField(max_length=20, unique=True, null=True, blank=True),
        ),
        migrations.RunPython(backfill_phone_numbers, migrations.RunPython.noop),
    ]


