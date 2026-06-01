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
     * 注册新用户，密码经 BCrypt 哈希后存入 MySQL。
     *
     * @param username    用户名
     * @param rawPassword 明文密码
     * @return 新创建的 UserDetails
     * @throws UsernameAlreadyExistsException 用户名已存在
     */
    public UserDetails register(String username, String rawPassword) {
        if (userRepository.existsByUsername(username)) {
            throw new UsernameAlreadyExistsException("用户名 '" + username + "' 已存在");
        }
        UserEntity entity = new UserEntity(username, encoder.encode(rawPassword));
        userRepository.save(entity);
        return User.builder()
                .username(username)
                .password(entity.getPasswordHash())
                .authorities("ROLE_USER")
                .build();
    }

    /**
     * 按用户名查找。
     *
     * @return UserDetails，未找到返回 null
     */
    public UserDetails findByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(entity -> User.builder()
                        .username(entity.getUsername())
                        .password(entity.getPasswordHash())
                        .authorities("ROLE_USER")
                        .build())
                .orElse(null);
    }

    /**
     * 验证明文密码与哈希是否匹配。
     */
    public boolean passwordMatches(String rawPassword, String encodedPassword) {
        return encoder.matches(rawPassword, encodedPassword);
    }
}
