from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [
        ('a_users', '0002_profile_phone_backfill'),
    ]

    operations = [
        migrations.AlterField(
            model_name='profile',
            name='phone',
            field=models.CharField(max_length=20, unique=True),
        ),
    ]


