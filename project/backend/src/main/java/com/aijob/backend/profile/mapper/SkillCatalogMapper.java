package com.aijob.backend.profile.mapper;

import com.aijob.backend.profile.vo.SkillVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * skill_catalog 查询 Mapper，只返回启用技能。
 */
@Mapper
public interface SkillCatalogMapper {

    @Select("""
            SELECT code, name, parent_code, enabled
            FROM skill_catalog
            WHERE enabled = 1
              AND (#{q} IS NULL OR name LIKE CONCAT('%', #{q}, '%'))
            ORDER BY code
            """)
    List<SkillVO> selectEnabled(@Param("q") String q);
}