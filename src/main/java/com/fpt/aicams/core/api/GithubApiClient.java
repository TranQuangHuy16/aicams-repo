package com.fpt.aicams.core.api;

import com.fpt.aicams.dto.commit.GithubBranchResponse;
import com.fpt.aicams.dto.commit.GithubCommitResponse;
import com.fpt.aicams.dto.commit.GithubPullRequestResponse;
import com.fpt.aicams.dto.github_account.GithubTokenDto;
import com.fpt.aicams.dto.github_account.GithubUserDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class GithubApiClient {

    private final RestTemplate restTemplate;

    @Value("${spring.security.oauth2.client.registration.github.client-id}")
    private String githubClientId;

    @Value("${spring.security.oauth2.client.registration.github.client-secret}")
    private String githubClientSecret;

    private static final String GITHUB_API_BASE_URL = "https://api.github.com/repos/";

    public GithubTokenDto exchangeCodeForToken(String code) {
        String url = "https://github.com/login/oauth/access_token";

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", githubClientId);
        body.add("client_secret", githubClientSecret);
        body.add("code", code);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<GithubTokenDto> response = restTemplate.postForEntity(url, entity, GithubTokenDto.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null
                || response.getBody().getAccessToken() == null) {
            throw new RuntimeException("Không thể lấy token từ GitHub. Code có thể không hợp lệ hoặc đã hết hạn.");
        }
        return response.getBody();
    }

    public GithubTokenDto refreshGithubToken(String refreshToken) {
        String url = "https://github.com/login/oauth/access_token";

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", githubClientId);
        body.add("client_secret", githubClientSecret);
        body.add("grant_type", "refresh_token");
        body.add("refresh_token", refreshToken);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<GithubTokenDto> response = restTemplate.postForEntity(url, entity, GithubTokenDto.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null
                || response.getBody().getAccessToken() == null) {
            throw new RuntimeException(
                    "Không thể làm mới token từ GitHub. Refresh token có thể đã hết hạn hoặc bị thu hồi.");
        }
        return response.getBody();
    }

    public GithubUserDto getGithubUserInfo(String accessToken) {
        String url = "https://api.github.com/user";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<GithubUserDto> response = restTemplate.exchange(url, HttpMethod.GET, entity,
                GithubUserDto.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Không thể lấy thông tin người dùng từ GitHub.");
        }
        return response.getBody();
    }

    public void createWebhook(String owner, String repo, String accessToken, String payloadUrl, String secret) {
        String url = String.format("https://api.github.com/repos/%s/%s/hooks", owner, repo);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", "application/vnd.github.v3+json");

        Map<String, Object> config = new HashMap<>();
        config.put("url", payloadUrl);
        config.put("content_type", "json");
        config.put("secret", secret);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "web");
        body.put("active", true);
        body.put("events", Arrays.asList("push", "pull_request"));
        body.put("config", config);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                log.warn("Webhook có thể đã tồn tại trên repo {}/{}", owner, repo);
            } else {
                throw new RuntimeException("Lỗi khi tạo Webhook: " + e.getMessage());
            }
        }
    }

    private HttpEntity<Void> createAuthEntity(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Accept", "application/vnd.github+json");
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        return new HttpEntity<>(headers);
    }

    public List<GithubBranchResponse> getBranches(String owner, String repo, String accessToken) {
        String url = UriComponentsBuilder.fromHttpUrl(
                GITHUB_API_BASE_URL + owner + "/" + repo + "/branches")
                .queryParam("per_page", 100).toUriString();

        ResponseEntity<GithubBranchResponse[]> response = restTemplate.exchange(
                url, HttpMethod.GET, createAuthEntity(accessToken), GithubBranchResponse[].class);

        return response.getBody() != null ? Arrays.asList(
                response.getBody()) : Collections.emptyList();
    }

    public List<GithubCommitResponse> getCommitsByBranch(String owner, String repo, String branchName,
            String accessToken) {
        String url = UriComponentsBuilder.fromHttpUrl(
                GITHUB_API_BASE_URL + owner + "/" + repo + "/commits")
                .queryParam("sha", branchName)
                .queryParam("per_page", 100).toUriString();

        ResponseEntity<GithubCommitResponse[]> response = restTemplate.exchange(
                url, HttpMethod.GET, createAuthEntity(accessToken), GithubCommitResponse[].class);

        return response.getBody() != null ? Arrays.asList(
                response.getBody()) : Collections.emptyList();
    }

    public GithubCommitResponse getCommitDetail(String owner, String repo, String commitSha, String accessToken) {
        String url = GITHUB_API_BASE_URL + owner + "/" + repo + "/commits/" + commitSha;
        ResponseEntity<GithubCommitResponse> response = restTemplate.exchange(
                url, HttpMethod.GET, createAuthEntity(accessToken), GithubCommitResponse.class);
        return response.getBody();
    }

    public List<GithubPullRequestResponse> getPullRequests(String owner, String repo, String accessToken) {
        String url = UriComponentsBuilder.fromHttpUrl(
                GITHUB_API_BASE_URL + owner + "/" + repo + "/pulls")
                .queryParam("state", "all")
                .queryParam("per_page", 100).toUriString();

        ResponseEntity<GithubPullRequestResponse[]> response = restTemplate.exchange(
                url, HttpMethod.GET, createAuthEntity(accessToken),
                GithubPullRequestResponse[].class);

        return response.getBody() != null ? Arrays.asList(
                response.getBody()) : Collections.emptyList();
    }

    public List<GithubCommitResponse> getCommitsByPullRequest(String owner, String repo, Integer prNumber,
            String accessToken) {
        String url = UriComponentsBuilder.fromHttpUrl(
                GITHUB_API_BASE_URL + owner + "/" + repo + "/pulls/" + prNumber + "/commits")
                .queryParam("per_page", 100).toUriString();

        ResponseEntity<GithubCommitResponse[]> response = restTemplate.exchange(
                url, HttpMethod.GET, createAuthEntity(accessToken), GithubCommitResponse[].class);

        return response.getBody() != null ? Arrays.asList(
                response.getBody()) : Collections.emptyList();
    }
}
