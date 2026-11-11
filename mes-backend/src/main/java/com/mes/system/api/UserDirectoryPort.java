package com.mes.system.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 账号只读目录：其他模块需要选人（派工、BOM 任务）时用，避免直接读 sys_user。
 */
public interface UserDirectoryPort {

    record UserRef(long id, String username, String realName, String role) {
    }

    /** 指定角色下的启用账号，按 id 排序 */
    List<UserRef> byRoles(Collection<String> roles);

    Optional<UserRef> find(long id);

    Optional<UserRef> findByUsername(String username);

    /** 拥有某权限的启用账号（角色权限表为准，不写死角色编码） */
    List<UserRef> byPermission(String permission);
}
