package com.dragon.agent.service;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.dragon.agent.entity.UserEntity;
import com.dragon.agent.exception.UsernameAlreadyExistsException;
import com.dragon.agent.repository.UserRepository;

/**
 * 用户服务——注册、查找和密码验证。
 *
 * BCrypt 编码是 CPU 密集操作，调用方应通过 boundedElastic 调度器执行。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder encoder;

    public UserService(UserRepository userRepository, PasswordEncoder encoder) {
        this.userRepository = userRepository;
        this.encoder = encoder;
    }

    /**
     * 注册新用户，密码经 BCrypt 哈希后存入数据库。
     *
     * @param username     用户名
     * @param rawPassword  明文密码
     * @param displayName  显示名称
     * @param email        邮箱
     * @param role         角色（ADMIN / DEPT_ADMIN / USER）
     * @param departmentId 所属部门 ID
     * @return 包含实际角色权限的 UserDetails
     * @throws UsernameAlreadyExistsException 用户名已存在
     */
    public UserDetails register(String username, String rawPassword, String displayName, String email, String role,
            Long departmentId) {
        if (userRepository.existsByUsername(username)) {
            throw new UsernameAlreadyExistsException("用户名 '" + username + "' 已存在");
        }
        String actualRole = role != null ? role : "USER";
        UserEntity entity = new UserEntity(username, encoder.encode(rawPassword));
        entity.setDisplayName(displayName);
        entity.setEmail(email);
        entity.setRole(actualRole);
        entity.setDepartmentId(departmentId);
        entity.setStatus("ACTIVE");
        userRepository.save(entity);
        return buildUserDetails(entity);
    }

    /**
     * 按用户名查找，返回包含实际角色权限的 UserDetails。
     *
     * @return UserDetails，未找到返回 null
     */
    public UserDetails findByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(this::buildUserDetails)
                .orElse(null);
    }

    /**
     * 验证明文密码与 BCrypt 哈希是否匹配。
     */
    public boolean passwordMatches(String rawPassword, String encodedPassword) {
        return encoder.matches(rawPassword, encodedPassword);
    }

    /**
     * 根据用户实体构建 Spring Security UserDetails，使用数据库中的实际角色。
     */
    private UserDetails buildUserDetails(UserEntity entity) {
        String role = entity.getRole() != null ? entity.getRole() : "USER";
        return org.springframework.security.core.userdetails.User.builder()
                .username(entity.getUsername())
                .password(entity.getPasswordHash())
                .authorities("ROLE_" + role)
                .build();
    }
}
