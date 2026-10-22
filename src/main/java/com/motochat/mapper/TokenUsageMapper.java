package com.motochat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.motochat.entity.TokenUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface TokenUsageMapper extends BaseMapper<TokenUsage> {

    /** 单个用户的累计用量 */
    @Select("""
            SELECT COALESCE(SUM(prompt_tokens), 0)     AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                   COALESCE(SUM(total_tokens), 0)      AS total_tokens,
                   COUNT(*)                            AS request_count
            FROM token_usage
            WHERE username = #{username}
            """)
    Map<String, Object> sumByUsername(@Param("username") String username);

    /** 所有用户分组累计 */
    @Select("""
            SELECT username,
                   COALESCE(SUM(prompt_tokens), 0)     AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                   COALESCE(SUM(total_tokens), 0)      AS total_tokens,
                   COUNT(*)                            AS request_count
            FROM token_usage
            GROUP BY username
            """)
    List<Map<String, Object>> sumGroupByUsername();
}
