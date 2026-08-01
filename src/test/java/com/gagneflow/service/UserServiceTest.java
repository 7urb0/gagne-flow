package com.gagneflow.service;

import com.gagneflow.entity.User;
import com.gagneflow.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService();
        // 注入 mock 依赖
        try {
            var userRepoField = UserService.class.getDeclaredField("userRepository");
            userRepoField.setAccessible(true);
            userRepoField.set(userService, userRepository);

            var passwordEncoderField = UserService.class.getDeclaredField("passwordEncoder");
            passwordEncoderField.setAccessible(true);
            passwordEncoderField.set(userService, passwordEncoder);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void register_shouldReturnSavedUser_whenInputValid() {
        String username = "testuser";
        String password = "password123";
        User expectedUser = new User(username, "hashed_password");
        expectedUser.setId(1L);

        when(userRepository.existsByUsername(username)).thenReturn(false);
        when(passwordEncoder.encode(password)).thenReturn("hashed_password");
        when(userRepository.save(any(User.class))).thenReturn(expectedUser);

        User result = userService.register(username, password);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals(username, result.getUsername());
        assertEquals("hashed_password", result.getPasswordHash());
        verify(userRepository).existsByUsername(username);
        verify(passwordEncoder).encode(password);
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_shouldThrowException_whenPasswordTooShort() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> userService.register("user", "12345"));
        assertEquals("密码至少需要6个字符", ex.getMessage());
        verify(userRepository, never()).existsByUsername(anyString());
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_shouldThrowException_whenPasswordIsNull() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> userService.register("user", null));
        assertEquals("密码至少需要6个字符", ex.getMessage());
    }

    @Test
    void register_shouldThrowException_whenUsernameAlreadyExists() {
        String username = "existinguser";
        when(userRepository.existsByUsername(username)).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> userService.register(username, "password123"));
        assertEquals("用户名已存在", ex.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    void findByUsername_shouldReturnUser_whenFound() {
        User expected = new User("test", "hash");
        expected.setId(1L);
        when(userRepository.findByUsername("test")).thenReturn(Optional.of(expected));

        User result = userService.findByUsername("test");

        assertNotNull(result);
        assertEquals("test", result.getUsername());
    }

    @Test
    void findByUsername_shouldReturnNull_whenNotFound() {
        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        User result = userService.findByUsername("unknown");

        assertNull(result);
    }

    @Test
    void passwordMatches_shouldReturnTrue_whenPasswordCorrect() {
        User user = new User("test", "stored_hash");
        when(passwordEncoder.matches("correct_password", "stored_hash")).thenReturn(true);

        assertTrue(userService.passwordMatches("correct_password", user));
    }

    @Test
    void passwordMatches_shouldReturnFalse_whenPasswordIncorrect() {
        User user = new User("test", "stored_hash");
        when(passwordEncoder.matches("wrong_password", "stored_hash")).thenReturn(false);

        assertFalse(userService.passwordMatches("wrong_password", user));
    }
}
