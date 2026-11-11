package com.mes.system.api;

/**
 * 持证人员：派工候选列表的一行（用户 + 所属组织 + 这道工序的技能等级）。
 *
 * <p>原来是 {@code Map<String, Object>}，字段名只活在 SQL 别名里，改一个别名前端就静默拿到 null。
 * 换成 record 后，JSON 字段名由组件名决定，改名会在编译期暴露。</p>
 */
public record SkillUser(long id, String username, String realName, Long orgId,
                        String orgName, String position, String level) {
}
