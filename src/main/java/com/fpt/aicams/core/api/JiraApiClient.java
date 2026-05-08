package com.fpt.aicams.core.api;

import com.fpt.aicams.dto.jira_account.AtlassianProfileResponse;
import com.fpt.aicams.dto.jira_account.AtlassianTokenResponse;
import com.fpt.aicams.dto.jira_cloud.AtlassianProjectResponse;
import com.fpt.aicams.dto.jira_cloud.AtlassianResourceResponse;
import com.fpt.aicams.dto.jira_issue.*;
import lombok.AccessLevel;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.*;
import java.util.Arrays;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class JiraApiClient {

    final RestTemplate restTemplate;

    private HttpHeaders createAuthHeaders(String accessToken, boolean isJsonContent) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
        if (isJsonContent) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return headers;
    }

    @Data
    public static class JiraIssueTypesResponseWrapper {
        private List<JiraProjectDetailResponse.JiraIssueType> issueTypes;
    }

    @Data
    public static class JiraBoardResponseWrapper {
        private List<JiraBoard> values;

        @Data
        public static class JiraBoard {
            private Integer id;
        }
    }

    @Data
    public static class JiraSprintCreateResponse {
        private Long id;
    }

    @Value("${spring.security.oauth2.client.registration.jira.client-id}")
    String clientId;

    @Value("${spring.security.oauth2.client.registration.jira.client-secret}")
    String clientSecret;

    @Value("${spring.security.oauth2.client.registration.jira.redirect-uri}")
    String yamlRedirectUri;

    @Value("${spring.security.oauth2.client.provider.atlassian.token-uri}")
    String tokenUrl;

    @Value("${spring.security.oauth2.client.provider.atlassian.user-info-uri}")
    String profileUrl;

    public AtlassianTokenResponse exchangeCodeForToken(String code, String redirectUri) {
        String actualRedirectUri = redirectUri != null ? redirectUri : resolveRedirectUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String requestBody = String.format(
                "{\"grant_type\":\"authorization_code\",\"client_id\":\"%s\",\"client_secret\":\"%s\",\"code\":\"%s\",\"redirect_uri\":\"%s\"}",
                clientId, clientSecret, code, actualRedirectUri);

        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<AtlassianTokenResponse> response = restTemplate.postForEntity(tokenUrl, entity,
                AtlassianTokenResponse.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Không thể lấy token từ Jira. Code có thể không hợp lệ hoặc đã hết hạn.");
        }
        return response.getBody();
    }

    public AtlassianTokenResponse refreshAccessToken(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String requestBody = String.format(
                "{\"grant_type\":\"refresh_token\",\"client_id\":\"%s\",\"client_secret\":\"%s\",\"refresh_token\":\"%s\"}",
                clientId, clientSecret, refreshToken);

        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<AtlassianTokenResponse> response = restTemplate.postForEntity(tokenUrl, entity,
                AtlassianTokenResponse.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            return response.getBody();
        } else {
            throw new RuntimeException(
                    "Không thể làm mới token từ Jira. Refresh token có thể không hợp lệ hoặc đã hết hạn.");
        }
    }

    public String fetchJiraAccountId(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<AtlassianProfileResponse> response = restTemplate.exchange(
                profileUrl, HttpMethod.GET, entity, AtlassianProfileResponse.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException(
                    "Không thể lấy thông tin người dùng từ Jira. Access token có thể không hợp lệ hoặc đã hết hạn.");
        }
        return response.getBody().getAccountId();
    }

    public List<AtlassianResourceResponse> getAccessibleResources(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Accept", "application/json");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String resourceUrl = "https://api.atlassian.com/oauth/token/accessible-resources";

        ResponseEntity<AtlassianResourceResponse[]> response = restTemplate.exchange(
                resourceUrl, HttpMethod.GET, entity, AtlassianResourceResponse[].class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Không thể lấy danh sách Jira Sites (Workspaces) mà bạn có quyền truy cập");
        }

        return Arrays.asList(response.getBody());
    }

    public List<AtlassianProjectResponse> getProjectsInSite(String siteId, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Accept", "application/json");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String projectUrl = String.format("https://api.atlassian.com/ex/jira/%s/rest/api/3/project", siteId);

        ResponseEntity<AtlassianProjectResponse[]> response = restTemplate.exchange(
                projectUrl, HttpMethod.GET, entity, AtlassianProjectResponse[].class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Không thể lấy danh sách Project từ Jira Site: " + siteId);
        }

        return Arrays.asList(response.getBody());
    }

    private String resolveRedirectUri() {
        if (yamlRedirectUri != null && yamlRedirectUri.contains("{baseUrl}")) {
            String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
            return yamlRedirectUri.replace("{baseUrl}", baseUrl);
        }
        return yamlRedirectUri;
    }

    public List<JiraProjectDetailResponse.JiraIssueType> getIssueTypesForProject(String siteId, String accessToken, String projectKey) {
        HttpEntity<Void> entity = new HttpEntity<>(createAuthHeaders(accessToken, false));
        String url = String.format("https://api.atlassian.com/ex/jira/%s/rest/api/3/issue/createmeta/%s/issuetypes", siteId, projectKey);

        ResponseEntity<JiraIssueTypesResponseWrapper> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, JiraIssueTypesResponseWrapper.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null || response.getBody().getIssueTypes() == null) {
            throw new RuntimeException("Không thể lấy danh sách Issue Types từ Jira Project.");
        }

        return response.getBody().getIssueTypes();
    }

    public Integer getBoardIdByProject(String siteId, String accessToken, String projectKey) {
        HttpEntity<Void> entity = new HttpEntity<>(createAuthHeaders(accessToken, false));
        String url = String.format("https://api.atlassian.com/ex/jira/%s/rest/agile/1.0/board?projectKeyOrId=%s", siteId, projectKey);

        ResponseEntity<JiraBoardResponseWrapper> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, JiraBoardResponseWrapper.class);
        
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            List<JiraBoardResponseWrapper.JiraBoard> values = response.getBody().getValues();
            if (values != null && !values.isEmpty() && values.get(0).getId() != null) {
                return values.get(0).getId();
            }
        }
        throw new RuntimeException("Không tìm thấy Board ID cho project " + projectKey);
    }

    /**
     * Thực hiện creat, update Issue
     */

    public JiraBulkIssueResponse createIssuesBulk(String siteId, String accessToken, JiraBulkIssueRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Accept", "application/json");
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<JiraBulkIssueRequest> entity = new HttpEntity<>(request, headers);
        String url = String.format("https://api.atlassian.com/ex/jira/%s/rest/api/2/issue/bulk", siteId);

        ResponseEntity<JiraBulkIssueResponse> response = restTemplate.postForEntity(url, entity, JiraBulkIssueResponse.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Không thể tạo Bulk Issues Jira.");
        }
        return response.getBody();
    }

    public void updateIssue(String siteId, String accessToken, String issueKey, JiraIssueUpdateRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<JiraIssueUpdateRequest> entity = new HttpEntity<>(request, headers);
        String url = String.format("https://api.atlassian.com/ex/jira/%s/rest/api/2/issue/%s", siteId, issueKey);

        ResponseEntity<Void> response = restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Lỗi khi cập nhật Issue trên Jira: " + issueKey);
        }
    }

    public void deleteIssue(String siteId, String accessToken, String issueKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = String.format("https://api.atlassian.com/ex/jira/%s/rest/api/2/issue/%s?deleteSubtasks=true", siteId, issueKey);

        try {
            ResponseEntity<Void> response = restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Lỗi khi xóa Issue trên Jira: " + issueKey);
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // Ignore 404 Not Found since it's already deleted
        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi xóa Issue trên Jira: " + issueKey, e);
        }
    }

    /**
     * Lấy danh sách các Status (Transitions) hợp lệ mà Issue có thể chuyển sang
     */
    public JiraTransitionResponse getIssueTransitions(String siteId, String accessToken, String issueKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Accept", "application/json");

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = String.format("https://api.atlassian.com/ex/jira/%s/rest/api/2/issue/%s/transitions", siteId, issueKey);

        ResponseEntity<JiraTransitionResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, JiraTransitionResponse.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Lỗi khi lấy danh sách Transition của Issue: " + issueKey);
        }
        return response.getBody();
    }

    /**
     * Thực hiện chuyển đổi trạng thái (Status) của Issue
     */
    public void transitionIssue(String siteId, String accessToken, String issueKey, JiraTransitionRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<JiraTransitionRequest> entity = new HttpEntity<>(request, headers);
        String url = String.format("https://api.atlassian.com/ex/jira/%s/rest/api/2/issue/%s/transitions", siteId, issueKey);

        ResponseEntity<Void> response = restTemplate.postForEntity(url, entity, Void.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Lỗi khi chuyển trạng thái Issue trên Jira: " + issueKey);
        }
    }

    /**
     * Lấy danh sách các Priority hợp lệ mà Issue có thể chuyển sang
     */
    public List<JiraPriorityResponse> getJiraPriorities(String siteId, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Accept", "application/json");

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = String.format("https://api.atlassian.com/ex/jira/%s/rest/api/2/priority", siteId);

        ResponseEntity<JiraPriorityResponse[]> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, JiraPriorityResponse[].class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Không thể lấy danh sách Priorities từ Jira");
        }
        return Arrays.asList(response.getBody());
    }

    /**
     * Thực hiện các tác vụ Create, Update, Delete Sprint
     */

    public Long createSprint(String siteId, String accessToken, Integer boardId, String name, String startDate, String endDate) {
        String body = String.format("{\"name\":\"%s\",\"startDate\":\"%s\",\"endDate\":\"%s\",\"originBoardId\":%d}",
                name, startDate, endDate, boardId);

        HttpEntity<String> entity = new HttpEntity<>(body, createAuthHeaders(accessToken, true));
        String url = String.format("https://api.atlassian.com/ex/jira/%s/rest/agile/1.0/sprint", siteId);

        ResponseEntity<JiraSprintCreateResponse> response = restTemplate.postForEntity(url, entity, JiraSprintCreateResponse.class);
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null && response.getBody().getId() != null) {
            return response.getBody().getId();
        }
        throw new RuntimeException("Lỗi tạo Sprint trên Jira.");
    }

    public void updateSprintJira(String siteId, String accessToken, Long sprintId, String name, String state, String startDate, String endDate) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> body = new HashMap<>();
        if (name != null) body.put("name", name);
        if (state != null) {
            String jiraState = state.equalsIgnoreCase("ACTIVE") ? "active" : (state.equalsIgnoreCase("CLOSED") ? "closed" : "future");
            body.put("state", jiraState);
        }
        if (startDate != null) body.put("startDate", startDate);
        if (endDate != null) body.put("endDate", endDate);

        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);
        String url = String.format("https://api.atlassian.com/ex/jira/%s/rest/agile/1.0/sprint/%d", siteId, sprintId);

        ResponseEntity<Void> response = restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Lỗi cập nhật Sprint trên Jira.");
        }
    }

    public void deleteSprintJira(String siteId, String accessToken, Long sprintId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = String.format("https://api.atlassian.com/ex/jira/%s/rest/agile/1.0/sprint/%d", siteId, sprintId);

        restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
    }

    public void moveIssuesToSprintJira(String siteId, String accessToken, Long sprintId, List<String> issueKeys) {
        if (issueKeys == null || issueKeys.isEmpty()) return;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, List<String>> body = Map.of("issues", issueKeys);
        HttpEntity<Map<String, List<String>>> entity = new HttpEntity<>(body, headers);
        String url = String.format("https://api.atlassian.com/ex/jira/%s/rest/agile/1.0/sprint/%d/issue", siteId, sprintId);

        restTemplate.postForEntity(url, entity, Void.class);
    }

    public void moveIssuesToBacklogJira(String siteId, String accessToken, List<String> issueKeys) {
        if (issueKeys == null || issueKeys.isEmpty()) return;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, List<String>> body = Map.of("issues", issueKeys);
        HttpEntity<Map<String, List<String>>> entity = new HttpEntity<>(body, headers);
        String url = String.format("https://api.atlassian.com/ex/jira/%s/rest/agile/1.0/backlog/issue", siteId);

        restTemplate.postForEntity(url, entity, Void.class);
    }
}
