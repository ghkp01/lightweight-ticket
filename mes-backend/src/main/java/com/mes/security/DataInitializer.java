package com.mes.security;

import com.mes.masterdata.api.MasterDataPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动时补齐演示账号。
 *
 * <p>口令不再写死在代码里，走 mes.init-password（默认 123456，生产用 MES_INIT_PASSWORD 覆盖）。
 * 这里建出来的账号一律带 must_change_password，第一次登录必须自己改密码，
 * 免得一套演示口令被当成长期口令用下去。</p>
 *
 * <p><b>为什么组织归属、工号、技能也放在这里：</b>账号是「迁移跑完之后」才由本类创建的，
 * 迁移脚本里那些 {@code update sys_user ... where username = 'admin'}、
 * {@code insert ... select ... from sys_user where username = 'admin'} 在全新库上
 * 一行都匹配不到，会静默失效（迁移本身仍然「成功」）。所以凡是依赖账号存在的种子数据，
 * 只能放在账号创建之后的这里补，不能写进迁移。</p>
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final MasterDataPort masterData;
    private final String initPassword;

    public DataInitializer(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder,
                           MasterDataPort masterData,
                           @Value("${mes.init-password:123456}") String initPassword) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.masterData = masterData;
        this.initPassword = initPassword;
    }

    @Override
    public void run(String... args) {
        String password = passwordEncoder.encode(initPassword);
        insert("admin", password, "系统管理员", "ADMIN", "13800000000", "admin@mes.local", null);
        insert("sales", password, "销售-刘洋", "SALES", "13800000001", null, null);
        insert("planner", password, "计划-孙浩", "PLANNER", "13800000002", null, null);
        insert("warehouse", password, "仓库-赵磊", "WAREHOUSE", "13800000003", null, 1L);
        insert("qc", password, "质检-周敏", "QC", "13800000004", null, null);
        // 报价员与技术员：报价 → 评审 → 订单 → BOM 任务的演示链路
        insert("quot", password, "报价-林可", "QUOTER", "13800000005", null, null);
        insert("tech", password, "技术-吴工", "TECH", "13800000006", null, null);
        // 一线操作工：派工候选人、扫码报工、持证校验的演示账号
        insert("worker1", password, "张伟", "OPERATOR", "13800000011", null, null);
        insert("worker2", password, "李强", "OPERATOR", "13800000012", null, null);

        bindOrg("admin", "PLANT01", "系统管理员", "E0001");
        bindOrg("sales", "D04", "销售员", "E0002");
        bindOrg("planner", "D01", "计划员", "E0003");
        bindOrg("warehouse", "D02", "仓管员", "E0004");
        bindOrg("qc", "D03", "质检员", "E0005");
        bindOrg("quot", "D04", "报价员", "E0006");
        bindOrg("tech", "D01", "技术员", "E0007");
        bindOrg("worker1", "T01", "操作工", "E0011");
        bindOrg("worker2", "T03", "操作工", "E0012");

        seedSkills("worker1", "CUT");
        seedSkills("worker2", "ASSY", "PACK");
        seedSkills("qc", "QC");
    }

    private void insert(String username, String password, String realName, String role,
                        String phone, String email, Long warehouseId) {
        // 主数据可能被清空过，绑定的仓库不存在时留空，避免重启时外键校验失败
        Long boundWarehouseId = existingWarehouseId(warehouseId);
        jdbcTemplate.update("""
                insert into sys_user (username, password, real_name, role, phone, email,
                                      warehouse_id, must_change_password)
                values (?, ?, ?, ?, ?, ?, ?, true)
                on conflict (username) do nothing
                """, username, password, realName, role, phone, email, boundWarehouseId);
        // 兼容先建账号、后执行 V3 迁移的数据库
        jdbcTemplate.update("""
                update sys_user set phone = coalesce(phone, ?), email = coalesce(email, ?),
                       warehouse_id = coalesce(warehouse_id, ?)
                where username = ?
                """, phone, email, boundWarehouseId, username);
    }

    /** 仓库存在才返回 id，不存在返回 null（清空主数据后重启也安全；仓库归主数据模块，走 api 查询） */
    private Long existingWarehouseId(Long warehouseId) {
        return warehouseId != null && masterData.warehouse(warehouseId).isPresent() ? warehouseId : null;
    }

    /**
     * 把账号挂到组织上并补工号。只填空值，不覆盖管理员后来在界面上改过的归属。
     */
    private void bindOrg(String username, String orgCode, String position, String employeeNo) {
        try {
            jdbcTemplate.update("""
                    update sys_user u
                       set org_id = coalesce(u.org_id, o.id),
                           position = coalesce(u.position, ?),
                           employee_no = coalesce(u.employee_no, ?)
                      from sys_org o
                     where o.code = ? and u.username = ?
                    """, position, employeeNo, orgCode, username);
        } catch (RuntimeException ex) {
            // 组织树被裁剪过的库（只保留工厂）不影响登录，记一条告警即可
            log.warn("演示账号 {} 的组织归属补写失败：{}", username, ex.getMessage());
        }
    }

    /**
     * 演示技能：仅当该账号一条技能都没有时才补，避免每次重启把管理员手工撤掉的技能又加回来。
     */
    private void seedSkills(String username, String... processCodes) {
        Integer existing = jdbcTemplate.queryForObject("""
                select count(*) from user_skill s join sys_user u on u.id = s.user_id
                 where u.username = ?
                """, Integer.class, username);
        if (existing != null && existing > 0) {
            return;
        }
        for (String processCode : processCodes) {
            jdbcTemplate.update("""
                    insert into user_skill (user_id, process_id, level)
                    select u.id, p.id, 'SENIOR' from sys_user u, process p
                     where u.username = ? and p.code = ?
                    on conflict do nothing
                    """, username, processCode);
        }
    }
}
