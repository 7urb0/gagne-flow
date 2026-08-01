package com.gagneflow.repository;

import com.gagneflow.entity.SessionMessage;
import com.gagneflow.entity.SessionMeta;
import com.gagneflow.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("Repository Integration Tests")
class RepositoryIntegrationTest {

    @Autowired private TestEntityManager entityManager;
    @Autowired private UserRepository userRepository;
    @Autowired private SessionMetaRepository sessionMetaRepository;
    @Autowired private SessionMessageRepository sessionMessageRepository;

    @Nested
    @DisplayName("UserRepository")
    class UserRepositoryTests {

        @Test
        @DisplayName("save and findById")
        void saveAndFind() {
            User user = new User();
            user.setUsername("teacher01");
            user.setPasswordHash("encoded_password");

            User saved = userRepository.save(user);
            assertNotNull(saved.getId());

            Optional<User> found = userRepository.findById(saved.getId());
            assertTrue(found.isPresent());
            assertEquals("teacher01", found.get().getUsername());
        }

        @Test
        @DisplayName("findByUsername returns correct user")
        void findByUsername() {
            User user = new User();
            user.setUsername("math_teacher");
            user.setPasswordHash("pass123");
            entityManager.persistAndFlush(user);

            Optional<User> found = userRepository.findByUsername("math_teacher");
            assertTrue(found.isPresent());
            assertEquals("math_teacher", found.get().getUsername());
        }

        @Test
        @DisplayName("findByUsername returns empty for nonexistent")
        void findByUsernameNotFound() {
            Optional<User> found = userRepository.findByUsername("nobody");
            assertTrue(found.isEmpty());
        }

        @Test
        @DisplayName("existsByUsername returns true/false")
        void existsByUsername() {
            User user = new User();
            user.setUsername("exists_check");
            user.setPasswordHash("pass");
            entityManager.persistAndFlush(user);

            assertTrue(userRepository.existsByUsername("exists_check"));
            assertFalse(userRepository.existsByUsername("nobody"));
        }

        @Test
        @DisplayName("save with duplicate username throws")
        void duplicateUsername() {
            User u1 = new User();
            u1.setUsername("duplicate");
            u1.setPasswordHash("p1");
            entityManager.persistAndFlush(u1);

            User u2 = new User();
            u2.setUsername("duplicate");
            u2.setPasswordHash("p2");

            assertThrows(Exception.class, () -> {
                userRepository.saveAndFlush(u2);
            });
        }

        @Test
        @DisplayName("findById returns empty Optional for nonexistent")
        void findByIdNotFound() {
            Optional<User> found = userRepository.findById(99999L);
            assertTrue(found.isEmpty());
        }
    }

    @Nested
    @DisplayName("SessionMetaRepository")
    class SessionMetaRepositoryTests {

        private Long userId = 1L;

        @BeforeEach
        void setupUser() {
            User user = new User();
            user.setUsername("session_user");
            user.setPasswordHash("pass");
            entityManager.persistAndFlush(user);
            userId = user.getId();
        }

        @Test
        @DisplayName("save and findByUserIdAndSessionId")
        void saveAndFind() {
            SessionMeta meta = new SessionMeta(userId, "session-001", "Math Lesson Plan");
            entityManager.persistAndFlush(meta);

            SessionMeta found = sessionMetaRepository.findByUserIdAndSessionId(userId, "session-001");
            assertNotNull(found);
            assertEquals("Math Lesson Plan", found.getTitle());
            assertEquals(userId, found.getUserId());
        }

        @Test
        @DisplayName("findByUserIdOrderByUpdateTimeDesc returns sorted")
        void findByUserIdSorted() {
            SessionMeta m1 = new SessionMeta(userId, "older", "Older Session");
            SessionMeta m2 = new SessionMeta(userId, "newer", "Newer Session");
            entityManager.persist(m1);
            entityManager.persist(m2);
            entityManager.flush();

            m2.setUpdateTime(Instant.now().plusSeconds(60));
            entityManager.persistAndFlush(m2);

            List<SessionMeta> sessions = sessionMetaRepository
                    .findByUserIdOrderByUpdateTimeDesc(userId);
            assertEquals(2, sessions.size());
            assertTrue(sessions.get(0).getUpdateTime()
                    .compareTo(sessions.get(1).getUpdateTime()) >= 0);
        }

        @Test
        @DisplayName("returns empty list for nonexistent userId")
        void findByUserIdEmpty() {
            List<SessionMeta> sessions = sessionMetaRepository
                    .findByUserIdOrderByUpdateTimeDesc(99999L);
            assertTrue(sessions.isEmpty());
        }

        @Test
        @DisplayName("findByUserIdAndSessionId returns null for nonexistent")
        void findByUserIdAndSessionIdNotFound() {
            SessionMeta found = sessionMetaRepository
                    .findByUserIdAndSessionId(userId, "nonexistent");
            assertNull(found);
        }
    }

    @Nested
    @DisplayName("SessionMessageRepository")
    class SessionMessageRepositoryTests {

        private Long userId = 1L;

        @BeforeEach
        void setup() {
            User user = new User();
            user.setUsername("msg_user");
            user.setPasswordHash("pass");
            entityManager.persistAndFlush(user);
            userId = user.getId();
        }

        @Test
        @DisplayName("save and findByUserIdAndSessionId returns correct messages")
        void saveAndFind() {
            sessionMessageRepository.save(new SessionMessage(userId, "s1", "user", "hello"));
            sessionMessageRepository.save(new SessionMessage(userId, "s1", "assistant", "hi there"));
            entityManager.flush();

            List<SessionMessage> msgs = sessionMessageRepository
                    .findByUserIdAndSessionId(userId, "s1");
            assertEquals(2, msgs.size());
        }

        @Test
        @DisplayName("messages are ordered by creation time")
        void messagesOrderedByTime() {
            sessionMessageRepository.save(new SessionMessage(userId, "s1", "user", "first"));
            sessionMessageRepository.save(new SessionMessage(userId, "s1", "assistant", "second"));
            sessionMessageRepository.save(new SessionMessage(userId, "s1", "user", "third"));
            entityManager.flush();

            List<SessionMessage> msgs = sessionMessageRepository
                    .findByUserIdAndSessionId(userId, "s1");
            assertEquals(3, msgs.size());
            assertTrue(msgs.get(0).getCreateTime()
                    .compareTo(msgs.get(2).getCreateTime()) <= 0);
        }

        @Test
        @DisplayName("findTopN limits results")
        void findTopNLimits() {
            for (int i = 0; i < 10; i++) {
                sessionMessageRepository.save(new SessionMessage(userId, "s1",
                        i % 2 == 0 ? "user" : "assistant", "msg" + i));
            }
            entityManager.flush();

            List<SessionMessage> top5 = sessionMessageRepository
                    .findTopNByUserIdAndSessionId(userId, "s1", 5);
            assertTrue(top5.size() <= 5);
        }

        @Test
        @DisplayName("findByUserIdAndSessionId returns empty for nonexistent")
        void findByUserIdAndSessionIdEmpty() {
            List<SessionMessage> msgs = sessionMessageRepository
                    .findByUserIdAndSessionId(userId, "no-such-session");
            assertTrue(msgs.isEmpty());
        }

        @Test
        @DisplayName("deleteByUserIdAndSessionId removes all messages")
        void deleteByUserIdAndSessionId() {
            sessionMessageRepository.save(new SessionMessage(userId, "s1", "user", "q"));
            sessionMessageRepository.save(new SessionMessage(userId, "s1", "assistant", "a"));
            entityManager.flush();

            sessionMessageRepository.deleteByUserIdAndSessionId(userId, "s1");
            entityManager.flush();

            List<SessionMessage> remaining = sessionMessageRepository
                    .findByUserIdAndSessionId(userId, "s1");
            assertTrue(remaining.isEmpty());
        }

        @Test
        @DisplayName("deleteAll batch removes selected messages")
        void deleteAllBatch() {
            SessionMessage m1 = sessionMessageRepository.save(
                    new SessionMessage(userId, "s1", "user", "keep"));
            SessionMessage m2 = sessionMessageRepository.save(
                    new SessionMessage(userId, "s1", "assistant", "delete"));
            entityManager.flush();

            sessionMessageRepository.deleteAll(List.of(m2));
            entityManager.flush();

            List<SessionMessage> remaining = sessionMessageRepository
                    .findByUserIdAndSessionId(userId, "s1");
            assertEquals(1, remaining.size());
            assertEquals("keep", remaining.get(0).getContent());
        }

        @Test
        @DisplayName("save with null content violates @Column(nullable=false) constraint")
        void saveNullContent() {
            // content 字段有 @Column(nullable=false) 约束，保存 null 应抛 DataIntegrityViolationException
            SessionMessage msg = new SessionMessage(userId, "s1", "user", null);
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> {
                sessionMessageRepository.saveAndFlush(msg);
            });
        }
    }
}
