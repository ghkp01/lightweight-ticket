package com.mes.system.api;

import java.util.List;

/**
 * 人员工序技能对外契约：派工选人、报工资格校验都走它。
 *
 * <p>保存技能、查某人的技能清单属于人员管理内部，不在这层。</p>
 */
public interface UserSkillPort {

    /** 该工序的持证人员；工序为 null 时返回空列表 */
    List<SkillUser> candidates(Long processId);

    /** 该工序是否启用了持证校验（有任何人配了这道工序的技能） */
    boolean processRequiresSkill(Long processId);

    /** 某人是否有这道工序的技能；工序为 null 视为不限 */
    boolean hasSkill(long userId, Long processId);
}
