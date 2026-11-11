package com.mes.system;

import com.mes.common.BizException;
import com.mes.system.api.SkillUser;
import com.mes.system.api.UserSkillPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 人员工序技能：决定派工时能选谁、报工时能校验谁有资格。
 * 只有被配置过技能的工序才启用持证校验，没配置的工序视为不限制，避免新库一上来全锁死。
 */
@Service
public class UserSkillService implements UserSkillPort {

    private final JdbcTemplate jdbcTemplate;

    public UserSkillService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record SkillItem(Long processId, String level, String remark) {
    }

    public record SkillRequest(List<SkillItem> items) {
    }

    public List<Map<String, Object>> list(long userId) {
        return jdbcTemplate.queryForList("""
                select s.process_id as "processId", p.code as "processCode", p.name as "processName",
                       s.level, s.remark
                from user_skill s
                join rm_process p on p.id = s.process_id
                where s.user_id = ?
                order by p.id
                """, userId);
    }

    @Transactional
    public void save(long userId, SkillRequest request) {
        Integer users = jdbcTemplate.queryForObject(
                "select count(*) from sys_user where id = ?", Integer.class, userId);
        if (users == null || users == 0) {
            throw new BizException(2019, "用户不存在");
        }
        jdbcTemplate.update("delete from user_skill where user_id = ?", userId);
        if (request == null || request.items() == null) {
            return;
        }
        for (SkillItem item : request.items()) {
            if (item == null || item.processId() == null) {
                continue;
            }
            Integer exists = jdbcTemplate.queryForObject(
                    "select count(*) from rm_process where id = ?", Integer.class, item.processId());
            if (exists == null || exists == 0) {
                throw new BizException(1002, "工序不存在，无法配置技能");
            }
            jdbcTemplate.update("""
                    insert into user_skill (user_id, process_id, level, remark)
                    values (?, ?, ?, ?)
                    on conflict (user_id, process_id) do update set level = excluded.level,
                                                                  remark = excluded.remark
                    """, userId, item.processId(), normalizeLevel(item.level()), item.remark());
        }
    }

    /** 该工序是否启用了持证校验（有任何人配了这道工序的技能） */
    @Override
    public boolean processRequiresSkill(Long processId) {
        if (processId == null) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from user_skill where process_id = ?", Integer.class, processId);
        return count != null && count > 0;
    }

    @Override
    public boolean hasSkill(long userId, Long processId) {
        if (processId == null) {
            return true;
        }
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from user_skill where user_id = ? and process_id = ?
                """, Integer.class, userId, processId);
        return count != null && count > 0;
    }

    public long skilledUserCount(Long processId) {
        if (processId == null) {
            return 0;
        }
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from user_skill where process_id = ?", Long.class, processId);
        return count == null ? 0 : count;
    }

    /** 按工序列出持证人员，派工选人用 */
    @Override
    public List<SkillUser> candidates(Long processId) {
        if (processId == null) {
            return new ArrayList<>();
        }
        return jdbcTemplate.query("""
                select u.id, u.username, u.real_name as "realName", u.org_id as "orgId",
                       o.name as "orgName", u.position, s.level
                from user_skill s
                join sys_user u on u.id = s.user_id
                left join sys_org o on o.id = u.org_id
                where s.process_id = ? and u.enabled = true
                order by coalesce(o.sort, 999), u.id
                """, (rs, rowNum) -> new SkillUser(
                rs.getLong("id"), rs.getString("username"), rs.getString("realName"),
                rs.getObject("orgId") == null ? null : rs.getLong("orgId"),
                rs.getString("orgName"), rs.getString("position"), rs.getString("level")),
                processId);
    }

    /** 给一批用户补技能标记，供派工下拉展示 */
    public Map<Long, String> skillsOfProcess(Long processId) {
        Map<Long, String> result = new LinkedHashMap<>();
        if (processId == null) {
            return result;
        }
        jdbcTemplate.query("select user_id, level from user_skill where process_id = ?", rs -> {
            result.put(rs.getLong("user_id"), rs.getString("level"));
        }, processId);
        return result;
    }

    private String normalizeLevel(String level) {
        String value = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            return "NORMAL";
        }
        if (!value.equals("JUNIOR") && !value.equals("NORMAL") && !value.equals("SENIOR")) {
            throw new BizException(1002, "技能等级只能是初级、中级或高级");
        }
        return value;
    }
}
