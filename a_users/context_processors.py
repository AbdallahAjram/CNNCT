from .models import Profile

def user_profile_context(request):
    """Inject the latest Profile object into all templates."""
    if request.user.is_authenticated:
        try:
            profile = Profile.objects.select_related("user").get(user=request.user)
            return {"navbar_profile": profile}
        except Profile.DoesNotExist:
            return {"navbar_profile": None}
    return {"navbar_profile": None}
