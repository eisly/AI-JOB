"""Keep related values and their public evidence together across source merges."""
from datetime import datetime, timezone

GROUPS = {
    "salary": ("salary_text", "salary_min_monthly_cents", "salary_max_monthly_cents"),
    "companySize": ("company_size_text", "company_size"),
    "city": ("city_name", "city_code"),
    "education": ("education_text", "education_min"),
    "experience": ("experience_text", "experience_months_min"),
    "publishedAt": ("published_at",),
    "updatedAt": ("updated_at",),
    "companyName": ("company_name",),
}

def record_fields(posting):
    for key, fields in GROUPS.items():
        value = next((getattr(posting, name) for name in fields
                      if getattr(posting, name) not in (None, "", "SIZE_UNKNOWN")), None)
        if value is not None:
            posting.field_sources.setdefault(key, {
                "sourceUrl": posting.source_url, "sourceName": posting.source_name,
                "sourceType": posting.source_type, "rawText": str(value)[:500],
                "fetchedAt": datetime.now(timezone.utc).isoformat(),
            })

def fill_groups(primary, other):
    record_fields(primary)
    record_fields(other)
    for key, fields in GROUPS.items():
        if all(getattr(primary, name) in (None, "", "SIZE_UNKNOWN") for name in fields):
            for name in fields:
                setattr(primary, name, getattr(other, name))
            if key in other.field_sources:
                primary.field_sources[key] = dict(other.field_sources[key])
