package com.aijob.backend.job;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

@Service
public class MatchScoreService {

    public BigDecimal skill(List<String> confirmedSkills, List<String> requiredSkills) {
        if (requiredSkills == null || requiredSkills.isEmpty()) {
            return null;
        }
        Set<String> confirmed = new HashSet<>(confirmedSkills);
        Set<String> required = new HashSet<>(requiredSkills);
        long matched = required.stream().filter(confirmed::contains).count();
        return BigDecimal.valueOf(matched).divide(BigDecimal.valueOf(required.size()), 6, RoundingMode.HALF_UP);
    }

    public BigDecimal preference(String targetRole, String jobRole, List<String> cities, String jobCity) {
        if (targetRole == null || jobRole == null || cities == null || cities.isEmpty() || jobCity == null) {
            return null;
        }
        BigDecimal roleFit = targetRole.equals(jobRole) ? BigDecimal.ONE : BigDecimal.ZERO;
        BigDecimal cityFit = cities.contains(jobCity) ? BigDecimal.ONE : BigDecimal.ZERO;
        return roleFit.add(cityFit).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
    }

    public Integer score(BigDecimal skill, BigDecimal semantic, BigDecimal preference) {
        if (skill == null || semantic == null || preference == null) {
            return null;
        }
        BigDecimal weighted = skill.multiply(BigDecimal.valueOf(0.60))
                .add(semantic.multiply(BigDecimal.valueOf(0.25)))
                .add(preference.multiply(BigDecimal.valueOf(0.15)));
        return weighted.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    public Integer searchScore(BigDecimal skill, BigDecimal semantic, BigDecimal preference) {
        if (skill == null && semantic == null) {
            return null;
        }
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal[] values = {skill, semantic, preference};
        BigDecimal[] weights = {BigDecimal.valueOf(0.60), BigDecimal.valueOf(0.25), BigDecimal.valueOf(0.15)};
        for (int index = 0; index < values.length; index++) {
            if (values[index] != null) {
                weighted = weighted.add(values[index].multiply(weights[index]));
                totalWeight = totalWeight.add(weights[index]);
            }
        }
        return weighted.multiply(BigDecimal.valueOf(100)).divide(totalWeight, 0, RoundingMode.HALF_UP).intValue();
    }

    public Map<String, Object> component(String key, BigDecimal value, BigDecimal weight, String provenance) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("key", key);
        result.put("value", value);
        result.put("weight", weight);
        result.put("provenance", provenance);
        return result;
    }
}