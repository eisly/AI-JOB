from __future__ import annotations

import re
from typing import Any

from .models import EDUCATION_LABELS, ROLE_KEYWORDS


def understand(request) -> dict[str, Any]:
    payload = request.context.get("payload") or {}
    profile = payload.get("profile") or {}
    catalog = {str(item.get("code")): str(item.get("name") or item.get("code")) for item in payload.get("skillCatalog") or []}
    profile_skills = set(profile.get("skills") or [])
    items = []
    for posting in payload.get("postings") or []:
        jd = str(posting.get("jdText") or "")
        skills = []
        for code, name in catalog.items():
            if re.search(rf"(?<![A-Za-z0-9_]){re.escape(code)}(?![A-Za-z0-9_])", jd, re.IGNORECASE):
                skills.append(code)
            elif name and name.lower() in jd.lower():
                skills.append(code)
        skills = list(dict.fromkeys(skills))[:50]
        education = None
        for code, label in EDUCATION_LABELS.items():
            if label in jd:
                education = code
                break
        experience = None
        exp_match = re.search(r"(\d{1,2})\s*年(?:以上|及以上|\+)", jd)
        if exp_match:
            experience = int(exp_match.group(1)) * 12
        quotes: dict[str, str] = {}
        if education:
            label = EDUCATION_LABELS[education]
            edu_line = re.search(rf"[^\n。；;]{{0,40}}{re.escape(label)}[^\n。；;]{{0,40}}", jd)
            if edu_line:
                quotes["educationMin"] = edu_line.group(0)[:200]
        if experience:
            exp_line = re.search(r"[^\n。；;]{0,40}" + re.escape(exp_match.group(0)) + r"[^\n。；;]{0,40}", jd)
            if exp_line:
                quotes["experienceMonthsMin"] = exp_line.group(0)[:200]
        if not education:
            education = None
        if not experience:
            experience = None
        matched = [code for code in skills if code in profile_skills]
        score = None
        if skills:
            score = round(len(matched) / len(skills), 4)
        reasons = []
        target = ROLE_KEYWORDS.get(str(profile.get("targetRole") or ""))
        if target:
            reasons.append(f"目标岗位为{target}，与岗位标题「{posting.get('title')}」按输入画像做语义比较")
        if matched:
            reasons.append("已确认技能与 JD 要求重合：" + "、".join(matched[:5]))
        gaps = [
            {"skillCode": code, "suggestion": f"针对 {code} 补充场景练习并提交可追溯回答"}
            for code in skills
            if code not in profile_skills
        ][:20]
        items.append(
            {
                "sourceKey": posting.get("sourceKey"),
                "sourceUrl": posting.get("sourceUrl"),
                "requirements": {
                    "educationMin": education,
                    "graduationYearMin": None,
                    "graduationYearMax": None,
                    "experienceMonthsMin": experience,
                    "skills": skills,
                    "requiredSkillLevels": {},
                    "quotes": quotes,
                },
                "semanticScore": score,
                "reasons": reasons[:5],
                "gaps": gaps,
            }
        )
    return {"items": items}