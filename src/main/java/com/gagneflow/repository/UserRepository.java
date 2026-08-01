package com.gagneflow.repository;

import java.util.Optional;
import com.gagneflow.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository
extends JpaRepository<User, Long> {
    public Optional<User> findByUsername(String var1);

    public boolean existsByUsername(String var1);
}
