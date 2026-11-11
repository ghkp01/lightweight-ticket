package com.mes.auth;

import com.mes.common.ApiResponse;
import com.mes.common.BizException;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.platform.audit.OperLogService;
import com.mes.security.JwtService;
import com.mes.security.PasswordCipher;
import com.mes.security.RsaCryptoService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RsaCryptoService rsaCryptoService;
    private final PasswordCipher passwordCipher;
    private final OperLogService operLogService;
    private final LoginSecurityService loginSecurityService;
    private final MasterDataPort masterData;
    /**
     * 强制改密总开关。关掉时登录接口不再回 mustChangePassword=true，
     * 前端就不会弹改密框 —— 否则只关后端拦截、前端照样强制改，测试环境根本进不去。
     */
    private final boolean forcePasswordChange;

    public AuthController(JdbcTemplate jdbcTemplate,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService,
                          RsaCryptoService rsaCryptoService,
                          PasswordCipher passwordCipher,
                          OperLogService operLogService,
                          LoginSecurityService loginSecurityService,
                          MasterDataPort masterData,
                          @Value("${mes.security.force-password-change:true}") boolean forcePasswordChange) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.rsaCryptoService = rsaCryptoService;
        this.passwordCipher = passwordCipher;
        this.operLogService = operLogService;
        this.loginSecurityService = loginSecurityService;
        this.masterData = masterData;
        this.forcePasswordChange = forcePasswordChange;
    }

    /**
     * 密码字段二选一：password 明文（移动端 / 脚本），encryptedPassword 密文（浏览器）。
     */
    public record LoginRequest(@NotBlank String username,
                               String password,
                               String encryptedPassword,
                               String keyId) {
    }

    public record RefreshRequest(String refreshToken) {
    }

    public record BindPhoneRequest(@NotBlank String phone) {
    }

    public record PasswordRequest(String oldPassword,
                                  String encryptedOldPassword,
                                  String newPassword,
                                  String encryptedNewPassword,
                                  String keyId) {
    }

    /** 浏览器拉公钥做 RSA-OAEP 加密，接口本身不需要登录 */
    @GetMapping("/public-key")
    public ApiResponse publicKey() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("algorithm", "RSA-OAEP-256");
        result.put("keyId", rsaCryptoService.keyId());
        result.put("publicKey", rsaCryptoService.publicKeyPem());
        return ApiResponse.ok(result);
    }

    @PostMapping("/login")
    public ApiResponse login(@Valid @RequestBody LoginRequest request) {
        // 登录时安全上下文还没建立，先告诉操作日志这次是谁在登录
        operLogService.actor(request.username(), "-");
        operLogService.touch("AUTH", null, request.username(), "登录系统：" + request.username());
        operLogService.action("登录");

        List<Map<String, Object>> users = jdbcTemplate.queryForList("""
                select id, username, password, real_name, role, enabled, phone, email,
                       failed_attempts, locked_until,
                       must_change_password as "mustChangePassword"
                from sys_user where username = ? or phone = ?
                order by case when username = ? then 0 else 1 end
                limit 1
                """, request.username(), request.username(), request.username());
        if (users.isEmpty()) {
            throw new BizException(4001, "用户名或密码错误");
        }
        Map<String, Object> user = users.get(0);
        long userId = ((Number) user.get("id")).longValue();
        // 锁定先于密码校验：锁着的时候连试都不让试，避免绕过次数限制
        long lockedMinutes = loginSecurityService.lockedMinutesLeft(user.get("locked_until"));
        if (lockedMinutes > 0) {
            throw new BizException(4021, "账号已锁定，请 " + lockedMinutes + " 分钟后再试，"
                    + "或联系管理员解锁");
        }
        boolean enabled = Boolean.TRUE.equals(user.get("enabled"));
        String rawPassword = passwordCipher.resolve(
                request.password(), request.encryptedPassword(), request.keyId());
        if (!enabled || !passwordEncoder.matches(rawPassword, String.valueOf(user.get("password")))) {
            int remain = loginSecurityService.recordFailure(userId,
                    user.get("failed_attempts") == null ? 0 : ((Number) user.get("failed_attempts")).intValue());
            if (remain == 0) {
                throw new BizException(4021, "密码错误次数过多，账号已锁定 "
                        + loginSecurityService.lockMinutes() + " 分钟");
            }
            throw new BizException(4001, enabled
                    ? "用户名或密码错误，还可尝试 " + remain + " 次"
                    : "用户名或密码错误");
        }
        String username = String.valueOf(user.get("username"));
        String role = String.valueOf(user.get("role"));
        String realName = String.valueOf(user.get("real_name"));
        // 开关关掉时一律不要求改密：后端放行 + 前端不弹框，测试环境才能一直用初始口令
        boolean mustChangePassword = forcePasswordChange && Boolean.TRUE.equals(user.get("mustChangePassword"));
        loginSecurityService.recordSuccess(userId);
        loginSecurityService.purgeExpired();
        String token = jwtService.create(username, realName, role, mustChangePassword);
        String refreshToken = loginSecurityService.issueRefreshToken(username, clientOf());

        operLogService.actor(username, role);
        operLogService.touch("AUTH", null, username, "登录成功：" + realName + "（" + username + "）");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("refreshToken", refreshToken);
        result.put("expiresIn", jwtService.expireSeconds());
        result.put("refreshExpiresIn", loginSecurityService.refreshDays() * 24L * 3600);
        result.putAll(profileOf(username));
        result.put("mustChangePassword", mustChangePassword);
        return ApiResponse.ok(result);
    }

    /** 用刷新令牌换新的访问令牌，同时轮换刷新令牌 */
    @PostMapping("/refresh")
    public ApiResponse refresh(@RequestBody RefreshRequest request) {
        Map<String, Object> rotated = loginSecurityService.rotate(request.refreshToken(), clientOf());
        String username = String.valueOf(rotated.get("username"));
        List<Map<String, Object>> users = jdbcTemplate.queryForList(
                "select real_name, role, enabled, must_change_password as \"mustChangePassword\" "
                        + "from sys_user where username = ?", username);
        if (users.isEmpty() || !Boolean.TRUE.equals(users.get(0).get("enabled"))) {
            throw new BizException(4022, "账号不可用，请重新登录");
        }
        Map<String, Object> user = users.get(0);
        boolean mustChangePassword = forcePasswordChange && Boolean.TRUE.equals(user.get("mustChangePassword"));
        String token = jwtService.create(username, String.valueOf(user.get("real_name")),
                String.valueOf(user.get("role")), mustChangePassword);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("refreshToken", rotated.get("refreshToken"));
        result.put("expiresIn", jwtService.expireSeconds());
        result.put("mustChangePassword", mustChangePassword);
        return ApiResponse.ok(result);
    }

    @PostMapping("/logout")
    public ApiResponse logout(@RequestBody(required = false) RefreshRequest request) {
        if (request != null && request.refreshToken() != null) {
            loginSecurityService.revoke(request.refreshToken());
        }
        return ApiResponse.ok();
    }

    private String clientOf() {
        try {
            org.springframework.web.context.request.ServletRequestAttributes attributes =
                    (org.springframework.web.context.request.ServletRequestAttributes)
                            org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return null;
            }
            String agent = attributes.getRequest().getHeader("User-Agent");
            return agent == null ? null : agent.substring(0, Math.min(agent.length(), 200));
        } catch (Exception e) {
            return null;
        }
    }

    @GetMapping("/me")
    public ApiResponse me() {
        String username = requireLogin();
        return ApiResponse.ok(profileOf(username));
    }

    public record ProfileRequest(String realName, String phone, String email) {
    }

    /** 个人资料自助修改：姓名、手机、邮箱，账号与角色不允许自己改 */
    @PutMapping("/profile")
    public ApiResponse updateProfile(@RequestBody ProfileRequest request) {
        String username = requireLogin();
        String realName = request.realName() == null ? "" : request.realName().trim();
        if (realName.isEmpty()) {
            throw new BizException(1002, "姓名不能为空");
        }
        if (realName.length() > 64) {
            throw new BizException(1002, "姓名不能超过 64 个字符");
        }
        String phone = request.phone() == null || request.phone().trim().isEmpty()
                ? null : request.phone().trim();
        if (phone != null && !phone.matches("1[3-9]\\d{9}")) {
            throw new BizException(1002, "手机号格式不正确");
        }
        if (phone != null) {
            Long count = jdbcTemplate.queryForObject(
                    "select count(*) from sys_user where phone = ? and username <> ?",
                    Long.class, phone, username);
            if (count != null && count > 0) {
                throw new BizException(4004, "手机号已被其他用户绑定");
            }
        }
        String email = request.email() == null || request.email().trim().isEmpty()
                ? null : request.email().trim();
        if (email != null && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new BizException(1002, "邮箱格式不正确");
        }
        jdbcTemplate.update("update sys_user set real_name = ?, phone = ?, email = ? where username = ?",
                realName, phone, email, username);
        return ApiResponse.ok(profileOf(username));
    }

    private Map<String, Object> profileOf(String username) {
        List<Map<String, Object>> users = jdbcTemplate.queryForList("""
                select u.id, u.username, u.real_name as "realName", u.phone, u.email, u.role,
                       coalesce(r.name, u.role) as "roleName",
                       u.warehouse_id as "warehouseId",
                       u.org_id as "orgId", o.name as "orgName", o.path as "orgPath", o.type as "orgType",
                       u.position, u.employee_no as "employeeNo",
                       u.must_change_password as "mustChangePassword"
                from sys_user u
                left join sys_role r on r.code = u.role
                left join sys_org o on o.id = u.org_id
                where u.username = ?
                """, username);
        if (users.isEmpty()) {
            throw new BizException(4003, "用户不存在");
        }
        MasterDataViews.fillWarehouseNames(masterData, users, "warehouseId", "warehouseName");
        Map<String, Object> result = new LinkedHashMap<>(users.get(0));
        result.put("permissions", permissions(String.valueOf(result.get("role"))));
        return result;
    }

    @PostMapping("/bind-phone")
    public ApiResponse bindPhone(@Valid @RequestBody BindPhoneRequest request) {
        String username = requireLogin();
        String phone = request.phone() == null ? "" : request.phone().trim();
        if (phone.isEmpty()) {
            throw new BizException(1002, "手机号不能为空");
        }
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from sys_user where phone = ? and username <> ?", Long.class, phone, username);
        if (count != null && count > 0) {
            throw new BizException(4004, "手机号已被其他用户绑定");
        }
        jdbcTemplate.update("update sys_user set phone = ? where username = ?", phone, username);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phone", phone);
        return ApiResponse.ok(result);
    }

    @PutMapping("/password")
    public ApiResponse changePassword(@Valid @RequestBody PasswordRequest request) {
        String username = requireLogin();
        List<Map<String, Object>> users = jdbcTemplate.queryForList(
                "select password, real_name, role from sys_user where username = ?", username);
        if (users.isEmpty()) {
            throw new BizException(4003, "用户不存在");
        }
        String encoded = String.valueOf(users.get(0).get("password"));
        String oldPassword = passwordCipher.resolve(
                request.oldPassword(), request.encryptedOldPassword(), request.keyId());
        if (!passwordEncoder.matches(oldPassword, encoded)) {
            throw new BizException(4008, "原密码不正确");
        }
        String newPassword = passwordCipher.resolve(
                request.newPassword(), request.encryptedNewPassword(), request.keyId());
        loginSecurityService.validatePassword(newPassword);
        if (newPassword.equals(oldPassword)) {
            throw new BizException(4009, "新密码不能与原密码相同");
        }
        jdbcTemplate.update("""
                update sys_user
                set password = ?, password_updated_at = now(), must_change_password = false
                where username = ?
                """, passwordEncoder.encode(newPassword), username);
        // 改完直接换一张不带「待改密」标记的令牌，前端不用被迫重新登录
        String token = jwtService.create(username,
                String.valueOf(users.get(0).get("real_name")),
                String.valueOf(users.get(0).get("role")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("expiresIn", jwtService.expireSeconds());
        result.put("mustChangePassword", false);
        return ApiResponse.ok(result);
    }

    private List<String> permissions(String role) {
        return jdbcTemplate.queryForList("""
                select rp.permission from sys_role_permission rp
                join sys_role r on r.id = rp.role_id
                where r.code = ? order by rp.permission
                """, String.class, role);
    }

    private String roleName(String role) {
        List<String> names = jdbcTemplate.queryForList(
                "select name from sys_role where code = ?", String.class, role);
        return names.isEmpty() ? role : names.get(0);
    }

    private String requireLogin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new BizException(401, "请先登录");
        }
        return authentication.getName();
    }
}
