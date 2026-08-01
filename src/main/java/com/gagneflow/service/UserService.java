package com.gagneflow.service;

import com.gagneflow.entity.User;
import com.gagneflow.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private static final Logger logger = LoggerFactory.getLogger(UserService.class);
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    public User register(String username, String password) {
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("\u5bc6\u7801\u81f3\u5c11\u9700\u89816\u4e2a\u5b57\u7b26");
        }
        if (this.userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("\u7528\u6237\u540d\u5df2\u5b58\u5728");
        }
        User user = new User(username, this.passwordEncoder.encode((CharSequence)password));
        User saved = (User)this.userRepository.save(user);
        logger.info("\u65b0\u7528\u6237\u6ce8\u518c: {} (id={})", (Object)username, (Object)saved.getId());
        return saved;
    }

    public User findByUsername(String username) {
        return this.userRepository.findByUsername(username).orElse(null);
    }

    public boolean passwordMatches(String rawPassword, User user) {
        return this.passwordEncoder.matches((CharSequence)rawPassword, user.getPasswordHash());
    }
}
