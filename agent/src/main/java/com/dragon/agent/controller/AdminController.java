package com.dragon.agent.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dragon.agent.service.AdminService;
import com.dragon.agent.support.SecurityHelper;

import reactor.core.publisher.Mono;

/**
 * 管理员接口——部门管理与人员管理。
 *
 * <p>所有权限校验在 {@link AdminService} 中完成，Controller 仅负责 HTTP 层。
 *
 * @author 陈龙
 * @since 2026-06-03
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private AdminService adminService;

    @Autowired
    private SecurityHelper securityHelper;

    // ==================== 部门管理 ====================

    @GetMapping("/departments")
    public Mono<ResponseEntity<List<Map<String, Object>>>> listDepts() {
        return securityHelper.currentUsername()
                .map(username -> ResponseEntity.ok(adminService.listDepartments(username)));
    }

    @PostMapping("/departments")
    public Mono<ResponseEntity<Map<String, Object>>> createDept(@RequestBody Map<String, String> body) {
        return securityHelper.currentUsername()
                .map(username -> ResponseEntity.status(201)
                        .body(adminService.createDepartment(body.get("name"), username)));
    }

    @PutMapping("/departments/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> renameDept(@PathVariable Long id,
            @RequestBody Map<String, String> body) {
        return securityHelper.currentUsername()
                .map(username -> ResponseEntity.ok(adminService.renameDepartment(id, body.get("name"), username)));
    }

    @DeleteMapping("/departments/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> deleteDept(@PathVariable Long id) {
        return securityHelper.currentUsername()
                .map(username -> ResponseEntity.ok(adminService.deleteDepartment(id, username)));
    }

    // ==================== 人员管理 ====================

    @GetMapping("/users")
    public Mono<ResponseEntity<List<Map<String, Object>>>> listUsers() {
        return securityHelper.currentUsername()
                .map(username -> ResponseEntity.ok(adminService.listUsers(username)));
    }

    @PostMapping("/users")
    public Mono<ResponseEntity<Map<String, Object>>> createUser(@RequestBody Map<String, Object> body) {
        return securityHelper.currentUsername().map(creator -> {
            String uname = (String) body.get("username");
            String pwd = (String) body.get("password");
            String displayName = (String) body.getOrDefault("displayName", null);
            String email = (String) body.getOrDefault("email", "");
            String role = (String) body.getOrDefault("role", "USER");
            Long deptId = body.get("departmentId") != null
                    ? Long.valueOf(body.get("departmentId").toString()) : null;
            return ResponseEntity.status(201)
                    .body(adminService.createUser(uname, pwd, displayName, email, role, deptId, creator));
        });
    }

    @DeleteMapping("/users/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> deleteUser(@PathVariable Long id) {
        return securityHelper.currentUsername()
                .map(username -> ResponseEntity.ok(adminService.deleteUser(id, username)));
    }

    @PutMapping("/users/{id}/role")
    public Mono<ResponseEntity<Map<String, Object>>> setUserRole(@PathVariable Long id,
            @RequestBody Map<String, String> body) {
        return securityHelper.currentUsername().map(username -> {
            Long deptId = body.containsKey("departmentId")
                    ? Long.valueOf(body.get("departmentId")) : null;
            return ResponseEntity.ok(adminService.setUserRole(id, body.get("role"), deptId, username));
        });
    }

    @PutMapping("/users/{id}/profile")
    public Mono<ResponseEntity<Map<String, Object>>> editProfile(@PathVariable Long id,
            @RequestBody Map<String, String> body) {
        return securityHelper.currentUsername().map(username -> ResponseEntity.ok(
                adminService.editProfile(id, body.get("displayName"), body.get("email"),
                        body.get("username"), username)));
    }
}
