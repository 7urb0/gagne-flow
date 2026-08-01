package com.gagneflow.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gagneflow.repository.SessionMessageRepository;
import com.gagneflow.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("Session Lifecycle Integration Tests")
class SessionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private SessionMessageRepository sessionMessageRepo;
    private ObjectMapper mapper = new ObjectMapper();
    private String accessToken;

    @BeforeEach
    void setup() throws Exception {
        userRepository.deleteAll();

        String regBody = mapper.writeValueAsString(Map.of(
                "username", "session_test_user",
                "password", "Secure123"
        ));
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(regBody));

        var loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(regBody))
                .andReturn();
        accessToken = mapper.readTree(loginResult.getResponse().getContentAsString())
                .get("token").asText();
    }

    @Nested
    @DisplayName("Session Registration via Chat History")
    class SessionRegistration {

        @Test
        @DisplayName("register chat history creates a session entry")
        void registerHistory() throws Exception {
            String body = mapper.writeValueAsString(Map.of(
                    "sessionId", "session-001"
            ));

            mockMvc.perform(post("/api/chat/history/register")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("list session history after registration")
        void listAfterRegistration() throws Exception {
            String body = mapper.writeValueAsString(Map.of(
                    "sessionId", "listable-session"
            ));
            mockMvc.perform(post("/api/chat/history/register")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body));

            mockMvc.perform(get("/api/chat/history")
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("register history without sessionId returns 400")
        void registerHistoryNoSessionId() throws Exception {
            String body = mapper.writeValueAsString(Map.of(
                    "title", "No Session ID"
            ));

            mockMvc.perform(post("/api/chat/history/register")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("get session info returns pair count")
        void getSessionInfo() throws Exception {
            String body = mapper.writeValueAsString(Map.of(
                    "sessionId", "info-session"
            ));
            mockMvc.perform(post("/api/chat/history/register")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body));

            mockMvc.perform(get("/api/chat/session/info-session")
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sessionId").value("info-session"));
        }

        @Test
        @DisplayName("clear chat accepts valid session ID")
        void clearChat() throws Exception {
            String body = mapper.writeValueAsString(Map.of(
                    "id", "clear-me"
            ));

            mockMvc.perform(post("/api/chat/clear")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("Session Isolation")
    class SessionIsolation {

        @Test
        @DisplayName("different users have isolated session histories")
        void sessionIsolation() throws Exception {
            // User A creates a session
            mockMvc.perform(post("/api/chat/history/register")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of(
                                    "sessionId", "userA-private"))))
                    .andExpect(status().isOk());

            // Create User B via register + login
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of(
                                    "username", "userB_isolated",
                                    "password", "Secure123"))));
            var loginBResult = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of(
                                    "username", "userB_isolated",
                                    "password", "Secure123")))
                    ).andReturn();
            String tokenB = mapper.readTree(loginBResult.getResponse().getContentAsString())
                    .get("token").asText();

            // User B's history should NOT contain user A's session
            String listB = mockMvc.perform(get("/api/chat/history")
                            .header("Authorization", "Bearer " + tokenB))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertFalse(listB.contains("userA-private"));
        }
    }

    @Nested
    @DisplayName("Message Persistence")
    class MessagePersistence {

        @Test
        @DisplayName("save and retrieve messages via repository")
        void saveRetrieveMessages() {
            var msg1 = new com.gagneflow.entity.SessionMessage(
                    1L, "api-session", "user", "What is 2+2?");
            var msg2 = new com.gagneflow.entity.SessionMessage(
                    1L, "api-session", "assistant", "2+2 equals 4");

            sessionMessageRepo.save(msg1);
            sessionMessageRepo.save(msg2);

            List<com.gagneflow.entity.SessionMessage> msgs = sessionMessageRepo
                    .findByUserIdAndSessionId(1L, "api-session");
            assertEquals(2, msgs.size());
        }

        @Test
        @DisplayName("delete session messages works")
        void deleteMessages() {
            sessionMessageRepo.save(new com.gagneflow.entity.SessionMessage(
                    1L, "del-session", "user", "q"));
            sessionMessageRepo.save(new com.gagneflow.entity.SessionMessage(
                    1L, "del-session", "assistant", "a"));

            sessionMessageRepo.deleteByUserIdAndSessionId(1L, "del-session");

            List<com.gagneflow.entity.SessionMessage> remaining = sessionMessageRepo
                    .findByUserIdAndSessionId(1L, "del-session");
            assertTrue(remaining.isEmpty());
        }
    }
}
