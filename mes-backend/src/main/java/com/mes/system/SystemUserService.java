package com.mes.system;

import com.mes.auth.api.LoginPolicyPort;
import com.mes.common.BizException;
import com.mes.common.PageResult;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.security.PasswordCipher;
import com.mes.system.api.UserDirectoryPort;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SystemUserService implements UserDirectoryPort {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final PasswordCipher passwordCipher;
    private final LoginPolicyPort loginPolicy;
    private final MasterDataPort masterData;

    public SystemUserService(JdbcTemplate jdbcTemplate,
                             PasswordEncoder passwordEncoder,
                             PasswordCipher passwordCipher,
                             LoginPolicyPort loginPolicy,
                             MasterDataPort masterData) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.passwordCipher = passwordCipher;
        this.loginPolicy = loginPolicy;
        this.masterData = masterData;
    }

    public record CreateRequest(@NotBlank String username,
                                String password,
                                String encryptedPassword,
                                String keyId,
                                @NotBlank String realName,
                                String phone,
                                String email,
                                @NotBlank String role,
                                Long warehouseId,
                                Long orgId,
                                String position,
                                String employeeNo,
                                Boolean enabled) {
    }

    public record UpdateRequest(@NotBlank String realName,
                                String phone,
                                String email,
                                @NotBlank String role,
                                Long warehouseId,
                                Long orgId,
                                String position,
                                String employeeNo,
                                Boolean enabled) {
    }

    public record ResetPasswordRequest(String password, String encryptedPassword, String keyId) {
    }

    /** 派工选人下拉：只返回启用用户，带组织与岗位，按组织（含下级）与关键字过滤 */
    public List<Map<String, Object>> options(String keyword, Long orgId) {
        StringBuilder sql = new StringBuilder("""
                select u.id, u.username, u.real_name as "realName", u.org_id as "orgId",
                       o.name as "orgName", u.position, u.employee_no as "employeeNo",
                       coalesce(r.name, u.role) as "roleName"
                from sys_user u
                left join sys_org o on o.id = u.org_id
                left join sys_role r on r.code = u.role
                where u.enabled = true
                """);
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and (u.username ilike ? or u.real_name ilike ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (orgId != null) {
            sql.append(" and (u.org_id = ? or o.path like (select path || '%' from sys_org where id = ?)) ");
            args.add(orgId);
            args.add(orgId);
        }
        sql.append(" order by coalesce(o.sort, 999), u.org_id nulls last, u.id");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    public PageResult list(String keyword, Long orgId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        String trimmed = keyword == null ? "" : keyword.trim();
        boolean hasKeyword = !trimmed.isEmpty();
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (hasKeyword) {
            where.append(" and (u.username ilike ? or u.real_name ilike ? or coalesce(u.phone, '') ilike ? "
                    + "or coalesce(u.employee_no, '') ilike ?) ");
            String like = "%" + trimmed + "%";
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (orgId != null) {
            where.append(" and (u.org_id = ? or o.path like (select path || '%' from sys_org where id = ?)) ");
            args.add(orgId);
            args.add(orgId);
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from sys_user u left join sys_org o on o.id = u.org_id " + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((safePage - 1) * safeSize);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select u.id, u.username, u.real_name as "realName", u.phone, u.email, u.role,
                       coalesce(r.name, u.role) as "roleName",
                       u.warehouse_id as "warehouseId",
                       u.org_id as "orgId", o.name as "orgName", o.path as "orgPath",
                       u.position, u.employee_no as "employeeNo",
                       u.enabled, to_char(u.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       u.failed_attempts as "failedAttempts",
                       to_char(u.locked_until, 'YYYY-MM-DD HH24:MI') as "lockedUntil",
                       (u.locked_until is not null and u.locked_until > now()) as "locked",
                       to_char(u.last_login_at, 'YYYY-MM-DD HH24:MI') as "lastLoginAt"
                from sys_user u
                left join sys_role r on r.code = u.role
                left join sys_org o on o.id = u.org_id
                """ + where + " order by u.id limit ? offset ?", pageArgs.toArray());
        // 仓库名归 masterdata，按 id 批量补，不再 join warehouse
        MasterDataViews.fillWarehouseNames(masterData, records, "warehouseId", "warehouseName");
        return new PageResult(records, total == null ? 0 : total, safePage, safeSize);
    }

    @Transactional
    public long create(CreateRequest request) {
        String username = trim(request.username());
        String phone = blankToNull(request.phone());
        String email = blankToNull(request.email());
        if (username.isEmpty()) {
            throw new BizException(1002, "登录账号不能为空");
        }
        String rawPassword = passwordCipher.resolve(
                request.password(), request.encryptedPassword(), request.keyId());
        if (rawPassword.isBlank()) {
            throw new BizException(1002, "初始密码不能为空");
        }
        loginPolicy.validatePassword(rawPassword);
        Integer exists = jdbcTemplate.queryForObject(
                "select count(*) from sys_user where username = ?", Integer.class, username);
        if (exists != null && exists > 0) {
            throw new BizException(4002, "登录账号已存在");
        }
        checkPhone(phone, null);
        checkRole(request.role());
        checkWarehouse(request.warehouseId());
        checkOrg(request.orgId());
        String employeeNo = blankToNull(request.employeeNo());
        checkEmployeeNo(employeeNo, null);
        Long id = jdbcTemplate.queryForObject("""
                insert into sys_user (username, password, real_name, phone, email, role, warehouse_id,
                                      org_id, position, employee_no, enabled, must_change_password)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true)
                returning id
                """, Long.class, username, passwordEncoder.encode(rawPassword),
                trim(request.realName()), phone, email, trim(request.role()),
                request.warehouseId(), request.orgId(), blankToNull(request.position()), employeeNo,
                request.enabled() == null || request.enabled());
        return id == null ? 0 : id;
    }

    @Transactional
    public void update(long id, UpdateRequest request) {
        Map<String, Object> user = load(id);
        String phone = blankToNull(request.phone());
        String email = blankToNull(request.email());
        checkPhone(phone, id);
        checkRole(request.role());
        checkWarehouse(request.warehouseId());
        checkOrg(request.orgId());
        String employeeNo = blankToNull(request.employeeNo());
        checkEmployeeNo(employeeNo, id);
        boolean enabled = request.enabled() == null
                ? Boolean.TRUE.equals(user.get("enabled"))
                : request.enabled();
        jdbcTemplate.update("""
                update sys_user set real_name = ?, phone = ?, email = ?, role = ?, warehouse_id = ?,
                       org_id = ?, position = ?, employee_no = ?, enabled = ?
                where id = ?
                """, trim(request.realName()), phone, email, trim(request.role()),
                request.warehouseId(), request.orgId(), blankToNull(request.position()), employeeNo,
                enabled, id);
    }

    @Transactional
    public void resetPassword(long id, ResetPasswordRequest request) {
        load(id);
        String rawPassword = passwordCipher.resolve(
                request.password(), request.encryptedPassword(), request.keyId());
        if (rawPassword.isBlank()) {
            throw new BizException(1002, "新密码不能为空");
        }
        loginPolicy.validatePassword(rawPassword);
        // 管理员设的是临时口令，用户下次登录必须自己改掉
        jdbcTemplate.update("""
                update sys_user set password = ?, password_updated_at = now(),
                       failed_attempts = 0, locked_until = null, must_change_password = true
                where id = ?
                """, passwordEncoder.encode(rawPassword), id);
    }

    /** 解锁：清掉失败次数与锁定时间，改密后也会自动解锁 */
    @Transactional
    public void unlock(long id) {
        Map<String, Object> user = load(id);
        loginPolicy.unlock(String.valueOf(user.get("username")));
    }

    private Map<String, Object> load(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, username, enabled from sys_user where id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException(4003, "用户不存在");
        }
        return rows.get(0);
    }

    private void checkPhone(String phone, Long selfId) {
        if (phone == null) {
            return;
        }
        Long count = selfId == null
                ? jdbcTemplate.queryForObject("select count(*) from sys_user where phone = ?", Long.class, phone)
                : jdbcTemplate.queryForObject(
                        "select count(*) from sys_user where phone = ? and id <> ?", Long.class, phone, selfId);
        if (count != null && count > 0) {
            throw new BizException(4004, "手机号已被其他用户绑定");
        }
    }

    private void checkRole(String role) {
        if (role == null || role.isBlank()) {
            throw new BizException(1002, "请选择角色");
        }
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from sys_role where code = ?", Integer.class, role.trim());
        if (count == null || count == 0) {
            throw new BizException(1002, "角色不存在");
        }
    }

    private void checkWarehouse(Long warehouseId) {
        if (warehouseId == null) {
            return;
        }
        if (masterData.warehouse(warehouseId).isEmpty()) {
            throw new BizException(1002, "所属仓库不存在");
        }
    }

    private void checkOrg(Long orgId) {
        if (orgId == null) {
            return;
        }
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from sys_org where id = ?", Integer.class, orgId);
        if (count == null || count == 0) {
            throw new BizException(1002, "所属组织不存在");
        }
    }

    private void checkEmployeeNo(String employeeNo, Long selfId) {
        if (employeeNo == null) {
            return;
        }
        Long count = selfId == null
                ? jdbcTemplate.queryForObject(
                        "select count(*) from sys_user where employee_no = ?", Long.class, employeeNo)
                : jdbcTemplate.queryForObject(
                        "select count(*) from sys_user where employee_no = ? and id <> ?",
                        Long.class, employeeNo, selfId);
        if (count != null && count > 0) {
            throw new BizException(1002, "工号已被其他用户使用");
        }
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String blankToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    // ---------------------------------------------------------------- 账号目录（跨模块）

    @Override
    public List<UserRef> byRoles(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ",
                java.util.Collections.nCopies(roles.size(), "?"));
        return jdbcTemplate.query("""
                select id, username, real_name, role from sys_user
                where enabled and role in (""" + placeholders + ") order by id",
                (rs, rowNum) -> new UserRef(rs.getLong("id"), rs.getString("username"),
                        rs.getString("real_name"), rs.getString("role")),
                roles.toArray());
    }

    @Override
    public Optional<UserRef> find(long id) {
        return jdbcTemplate.query("""
                select id, username, real_name, role from sys_user where id = ?
                """, (rs, rowNum) -> new UserRef(rs.getLong("id"), rs.getString("username"),
                rs.getString("real_name"), rs.getString("role")), id).stream().findFirst();
    }

    @Override
    public Optional<UserRef> findByUsername(String username) {
        return jdbcTemplate.query("""
                select id, username, real_name, role from sys_user where username = ?
                """, (rs, rowNum) -> new UserRef(rs.getLong("id"), rs.getString("username"),
                rs.getString("real_name"), rs.getString("role")), username).stream().findFirst();
    }

    @Override
    public List<UserRef> byPermission(String permission) {
        return jdbcTemplate.query("""
                select u.id, u.username, u.real_name, u.role
                from sys_user u
                join sys_role r on r.code = u.role
                join sys_role_permission rp on rp.role_id = r.id
                where u.enabled and rp.permission = ?
                order by u.id
                """, (rs, rowNum) -> new UserRef(rs.getLong("id"), rs.getString("username"),
                rs.getString("real_name"), rs.getString("role")), permission);
    }
}
